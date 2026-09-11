package com.example.subtitleplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.floor

/**
 * 歌词识别（日语特化，sherpa-onnx 离线 zipformer）。
 *
 * 2.0 改进：
 * - 模型下载按「角色 + 候选文件名」解析（HF 镜像仓库的 joiner 只有 fp32 版，
 *   之前写死 int8 名导致必 404）；hf-mirror 国内源优先，拒绝 HTML 错误页
 * - **实时识别**：每识别完一个 30s 窗口，立即把已识别行写进 lrc 文件
 *   （边识别边落盘，取消也保留已完成部分），并通过 onWindowDone 回调
 *   实时上抛（UI 预览 + 逐窗翻译联动）
 * - **台本热词**：提供台本文本时生成 sherpa hotwords 文件
 *   （modified_beam_search + 词组 1.5 分），专有名词识别率显著提升
 */
object SpeechRecManager {

    private const val MODEL_DIR = "asr_model"
    private const val HOTWORDS_FILE = "asr_hotwords.txt"

    /** 热词条数上限：太多会拖慢 modified_beam_search 且收益递减。 */
    private const val MAX_HOTWORDS = 120

    /** 模型文件按「角色」定义，每个角色可有多个候选文件名（镜像仓库命名不一）。 */
    private data class ModelFile(val role: String, val candidates: List<String>)

    private val modelFiles = listOf(
        ModelFile("encoder", listOf("encoder-epoch-99-avg-1.int8.onnx")),
        ModelFile("decoder", listOf("decoder-epoch-99-avg-1.onnx")),
        // 镜像仓库 joiner 只有 fp32 版：两个候选都试，任一下载成功即可
        ModelFile("joiner", listOf("joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx")),
        ModelFile("tokens", listOf("tokens.txt"))
    )

    /** 模型镜像源（按序尝试）。hf-mirror 国内可直连，放最前；HF 原站回退。 */
    private val modelSources = listOf(
        "https://hf-mirror.com/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://hf-mirror.com/k2-fsa/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/"
    )

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR)

    private const val TOKENS_FILE = "tokens.txt"

    /** 某角色已就绪的文件名（不存在返回 null）。 */
    private fun resolvedFile(context: Context, role: String): File? {
        val mf = modelFiles.firstOrNull { it.role == role } ?: return null
        for (name in mf.candidates) {
            val f = File(modelDir(context), name)
            if (f.exists() && f.length() > 1024) return f
        }
        return null
    }

    fun isModelReady(context: Context): Boolean =
        modelFiles.all { resolvedFile(context, it.role) != null }

    private fun isSaneContent(bytes: ByteArray, n: Int, name: String): Boolean {
        // onnx 是二进制（protobuf），tokens.txt 是文本：只要不是 '<' 开头的 HTML 即可
        if (n > 0 && bytes[0] == '<'.code.toByte()) return false
        if (name == TOKENS_FILE && n < 1024) return false
        return true
    }

    /**
     * 下载模型（后台线程）。onProgress(第几个文件 0-based, 当前文件百分比)。
     * 每个文件按 候选名 × 镜像源 全组合尝试；单个源限时防挂死。
     */
    fun downloadModel(
        context: Context,
        onProgress: (fileIndex: Int, filePercent: Int) -> Unit,
        onDone: (ok: Boolean, errMsg: String?) -> Unit,
        isCancelled: () -> Boolean = { cancelFlagForTest }
    ) {
        Thread {
            val dir = modelDir(context).apply { mkdirs() }
            for ((idx, mf) in modelFiles.withIndex()) {
                val existing = resolvedFile(context, mf.role)
                if (existing != null) {
                    onProgress(idx, 100)
                    continue
                }
                var downloaded: File? = null
                var lastErr: String? = null
                // 整体重试 4 轮（镜像小文件请求时好时坏，实测需要多试）；
                // 退避递增 3/6/9 秒，防忙时连续撞墙
                attempt@ for (attempt in 0 until 4) {
                    if (attempt > 0) {
                        PlaybackLog.log("asr model retry #${attempt + 1} for ${mf.role}")
                        Thread.sleep(3000L * attempt)
                    }
                    for (base in modelSources) {
                        for (name in mf.candidates) {
                            if (isCancelledHook()) break@attempt
                            try {
                                val (conn, total) = openDownload(base + name)
                                val tmp = File(dir, "$name.tmp")
                                var acc = 0L
                                var sane = true
                                try {
                                    conn.inputStream.use { input ->
                                        tmp.outputStream().use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var first = true
                                            while (true) {
                                                // 收满长度立即结束：hf-mirror 等代理不会主动断流，
                                                // 等 EOF 会卡到读超时（2.0 实测）
                                                if (total > 0 && acc >= total) break
                                                val n = input.read(buf)
                                                if (n < 0) break
                                                if (first && n > 0 && buf[0] == '<'.code.toByte()) {
                                                    sane = false
                                                    break
                                                }
                                                first = false
                                                out.write(buf, 0, n)
                                                acc += n
                                                if (total > 0) {
                                                    onProgress(idx, ((acc * 100) / total).toInt().coerceIn(0, 100))
                                                }
                                            }
                                        }
                                    }
                                } catch (e: java.net.SocketTimeoutException) {
                                    // 【2.1】tokens.txt 这类非 LFS 小文件走 /api/resolve-cache
                                    // （Cloudflare，Transfer-Encoding: chunked）：既没有
                                    // Content-Length 也不主动断流。2.0 只能等 EOF → 挂到读超时 →
                                    // 抛异常删掉已下数据重试 → 永远卡在 tokens.txt。
                                    // 超时说明服务器不再发数据，按「读完」处理；长度不对下面会拦。
                                    PlaybackLog.log("asr model read timeout as EOF name=$name acc=$acc total=$total")
                                }
                                conn.disconnect()
                                if (!sane || acc < 1024) {
                                    tmp.delete()
                                    lastErr = "$name 内容异常（可能 404 页面）"
                                    continue
                                }
                                if (total > 0 && acc < total) {
                                    // 长度不足：超时兜底绝不能把截断的文件当成功
                                    tmp.delete()
                                    lastErr = "$name 长度不足 $acc/$total"
                                    continue
                                }
                                val target = File(dir, name)
                                if (!tmp.renameTo(target)) {
                                    tmp.copyTo(target, overwrite = true)
                                    tmp.delete()
                                }
                                downloaded = target
                                onProgress(idx, 100)
                                PlaybackLog.log("asr model OK ${mf.role} <- $base$name (${acc}B)")
                                break@attempt
                            } catch (e: Exception) {
                                lastErr = "$name ${e.javaClass.simpleName}: ${e.message}"
                                File(dir, "$name.tmp").delete()
                            }
                        }
                    }
                }
                if (downloaded == null) {
                    PlaybackLog.log("asr model download FAIL ${mf.role}: $lastErr")
                    onDone(false, "${mf.role} 文件（$lastErr）")
                    return@Thread
                }
            }
            PlaybackLog.log("asr model download ALL DONE")
            onDone(true, null)
        }.start()
    }

    
    /**
     * 打开下载连接（手动跟随重定向，最多 5 跳），并尽力解析出文件总长度。
     *
     * 为什么不交给 HttpURLConnection 自动跟随：hf-mirror 对非 LFS 小文件
     * （就是 tokens.txt）返回 307 → /api/resolve-cache，最终响应是 Cloudflare 的
     * chunked（无 Content-Length）。自动跟随会把中间 307 上的 Content-Range 丢掉，
     * 于是拿不到长度、只能死等 EOF —— 这正是 2.0「下载总是卡在 tokens.txt」的根因。
     * 手动跟随既能在 307 响应头读到 Content-Range/X-Linked-Size，又能继续取到数据流。
     */
    private fun openDownload(url: String): Pair<HttpURLConnection, Long> {
        var urlStr = url
        var total = -1L
        var hops = 0
        while (true) {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "ShiYin/2.1")
            // 明确要未压缩：gzip 会让 Content-Length 与实际读到的字节数对不上
            c.setRequestProperty("Accept-Encoding", "identity")
            // 带 Range：这样响应里会回 Content-Range，里面就带总长度
            c.setRequestProperty("Range", "bytes=0-")
            val code = c.responseCode
            parseTotalLen(c)?.let { if (it > 0) total = it }
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                hops++
                if (loc.isNullOrBlank() || hops > 5) {
                    throw java.io.IOException("redirect $code without usable location")
                }
                urlStr = try {
                    java.net.URI(urlStr).resolve(loc).toString()
                } catch (e: Exception) {
                    loc
                }
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw java.io.IOException("HTTP $code")
            }
            if (total <= 0 && c.contentLengthLong > 0) total = c.contentLengthLong
            return c to total
        }
    }

    /** 响应头里的总长度：X-Linked-Size，或 Content-Range「bytes 0-45753/45754」的 45754。 */
    private fun parseTotalLen(c: HttpURLConnection): Long? = try {
        c.getHeaderField("X-Linked-Size")?.trim()?.toLongOrNull()
            ?: c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
    } catch (e: Exception) {
        null
    }

    /** 测试钩子（仅单测用）：置 true 可提前中止下载循环。 */
    @Volatile
    var cancelFlagForTest: Boolean = false

    private fun isCancelledHook(): Boolean = cancelFlagForTest

    /** 手动导入模型文件（SAF 多选）：文件名匹配任一候选即拷入模型目录。返回成功数。 */
    fun importModelFiles(context: Context, uris: List<Uri>): Int {
        val dir = modelDir(context).apply { mkdirs() }
        val known = modelFiles.flatMap { it.candidates }.toSet()
        var imported = 0
        for (u in uris) {
            try {
                val name = queryDisplayName(context, u)
                    ?: u.lastPathSegment?.substringAfterLast('/') ?: continue
                if (name !in known) continue
                val tmp = File(dir, "$name.tmp")
                context.contentResolver.openInputStream(u)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: continue
                if (tmp.length() > 1024) {
                    val target = File(dir, name)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    imported++
                    PlaybackLog.log("asr import OK $name (${tmp.length()}B)")
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                PlaybackLog.log("asr import THREW ${e.message}")
            }
        }
        PlaybackLog.log("asr import done: $imported files")
        return imported
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ---------- 台本 → 热词 ----------

    /** tokens.txt 符号表（cjkchar 逐字查表用），按「文件路径+大小」缓存。 */
    private val symbolCache = HashMap<String, Set<String>>()

    private fun symbolTable(context: Context): Set<String>? {
        val f = resolvedFile(context, "tokens") ?: return null
        val key = f.absolutePath + ":" + f.length()
        synchronized(symbolCache) { symbolCache[key]?.let { return it } }
        return try {
            val set = HashSet<String>(8192)
            f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (line in seq) {
                    val sym = line.substringBefore('\t').trim()
                    if (sym.isNotEmpty()) set.add(sym)
                }
            }
            if (set.isEmpty()) return null
            synchronized(symbolCache) { symbolCache[key] = set }
            PlaybackLog.log("asr symbol table: ${set.size} symbols")
            set
        } catch (e: Exception) {
            PlaybackLog.log("asr symbol table THREW: ${e.message}")
            null
        }
    }

    /**
     * 台本文本 → sherpa hotwords 文件（每行「词组 1.5」，去重、限长限量）。
     * modified_beam_search 下热词显著提升专有名词识别率。
     *
     * **必须按模型符号表逐字过滤**：日语模型 tokens.txt 只有 ASCII + 假名 + 日文汉字，
     * 台本里的简体中文（说/语/们…）、特殊符号都不在表内；sherpa-onnx 在
     * modified_beam_search 把热词转 token id 时找不到 symbol，会在 native 层直接
     * exit/abort 杀掉进程——Java 侧 try/catch 拦不住，用户看到的就是「一用台本就闪退」。
     * 所以只保留「每个字符都在符号表里」的词；过滤后为空就返回 null
     * （调用方回退 greedy_search，不启用热词），功能降级但不崩。
     */
    private fun writeHotwords(context: Context, scriptText: String): File? {
        return try {
            val sym = symbolTable(context) ?: run {
                PlaybackLog.log("asr hotwords skipped: no symbol table")
                return null
            }
            val phrases = LinkedHashSet<String>()
            for (raw in scriptText.lines()) {
                for (seg in raw.split(Regex("[。！？!?.,、，…\\s　]+"))) {
                    val p = seg.trim()
                    if (p.isEmpty() || p.length < 2 || p.length > 20) continue
                    if (p.all { it.isDigit() || it in "0-9.-" }) continue
                    // 模型符号表里没有的字符：整词丢弃（否则 native 层直接崩）
                    if (!p.all { sym.contains(it.toString()) }) continue
                    phrases.add(p)
                    if (phrases.size >= MAX_HOTWORDS) break
                }
                if (phrases.size >= MAX_HOTWORDS) break
            }
            if (phrases.isEmpty()) {
                PlaybackLog.log("asr hotwords: 0 phrases survived symbol-table filter")
                return null
            }
            val f = File(modelDir(context), HOTWORDS_FILE)
            f.writeText(phrases.joinToString("\n") { "$it 1.5" })
            PlaybackLog.log("asr hotwords: ${phrases.size} phrases (symbol-filtered)")
            f
        } catch (e: Exception) {
            PlaybackLog.log("asr hotwords THREW: ${e.message}")
            null
        }
    }

    // ---------- 主入口：识别并生成歌词 ----------

    /**
     * 识别并生成歌词（实时）。
     * @param scriptText 台本文本（可空）→ 热词提升识别率
     * @param onProgress (已处理秒, 总秒)
     * @param onWindowDone 每识别完一个窗口回调一次（识别线程；linesSoFar 为累计行）
     * @param onDone (ok, errMsg, savedWhere, lines)
     */
    fun transcribe(
        context: Context,
        audioUri: Uri,
        treeUris: List<Uri>,
        scriptText: String?,
        onProgress: (doneSec: Int, totalSec: Int) -> Unit,
        onWindowDone: (linesSoFar: List<LrcLine>) -> Unit,
        isCancelled: () -> Boolean,
        onDone: (ok: Boolean, errMsg: String?, savedWhere: String?, lines: List<LrcLine>?) -> Unit
    ) {
        Thread {
            if (!isModelReady(context)) {
                onDone(false, "识别模型未就绪", null, null)
                return@Thread
            }
            try {
                PlaybackLog.log("asr start uri=$audioUri script=${scriptText?.length ?: 0}chars")
                // lrc 目标在识别开始前建好：每窗写入，边识别边落盘（取消也保留已识别部分）。
                // SAF 写失败时自动切换应用内兜底，绝不静默丢歌词
                var target = createLrcTarget(context, audioUri, treeUris)
                if (!writeLrc(context, target, "")) {
                    PlaybackLog.log("asr SAF target unusable -> fallback internal")
                    target = internalLrcTarget(context, audioUri)
                    writeLrc(context, target, "")
                }
                // 流水线：解码线程边解码边把 16k 采样块推入队列，
                // 识别线程凑满 30s 窗即识别并实时上抛（首行歌词约 6-10 秒内出现）
                val queue = java.util.concurrent.ArrayBlockingQueue<Any>(128)
                val decoder = Thread {
                    decodeToQueue(context, audioUri, queue, onProgress, isCancelled)
                }
                decoder.start()
                val lines = recognizeStreaming(
                    queue, modelDir(context),
                    scriptText?.let { writeHotwords(context, it) },
                    { linesSoFar ->
                        // 实时：每窗完成即写盘 + 上抛（UI 预览 / 逐窗翻译联动）
                        if (!writeLrc(context, target, buildLrc(linesSoFar))) {
                            // 写失败（如 SAF 中途失效）：切应用内兜底
                            PlaybackLog.log("asr writeLrc fail -> fallback internal")
                            target = internalLrcTarget(context, audioUri)
                            writeLrc(context, target, buildLrc(linesSoFar))
                        }
                        onWindowDone(linesSoFar)
                    },
                    isCancelled
                ) { errMsg ->
                    onDone(false, errMsg, null, null)
                }
                if (lines == null) return@Thread
                // 收尾：最终内容确认写入（失败切兜底重写）
                if (!writeLrc(context, target, buildLrc(lines))) {
                    target = internalLrcTarget(context, audioUri)
                    writeLrc(context, target, buildLrc(lines))
                }
                val where = describeTarget(context, audioUri, target)
                PlaybackLog.log("asr done: ${lines.size} lines -> $where")
                onDone(true, null, where, lines)
            } catch (e: Exception) {
                PlaybackLog.log("asr THREW: ${e.javaClass.simpleName}: ${e.message}")
                onDone(false, "${e.javaClass.simpleName}: ${e.message}", null, null)
            }
        }.start()
    }

    // ---------- 音频解码：任意音频 → 16kHz 单声道采样块（流水线推入队列） ----------

    /** 队列终止标记：解码结束。 */
    private class AsrEof

    /** 队列错误标记：解码失败。 */
    private class AsrError(val msg: String)

    private val asrEofMarker = AsrEof()

    /** 重采样后的 shorts 按 1 秒（16000 采样）聚块，转 float 推入队列。 */
    private class PcmChunker(
        private val chunkSamples: Int,
        private val sink: (FloatArray) -> Unit
    ) {
        private var buf = ShortArray(chunkSamples)
        private var len = 0

        fun push(s: Short) {
            buf[len++] = s
            if (len == chunkSamples) flushPartial()
        }

        private fun flushPartial() {
            val out = FloatArray(len)
            for (i in 0 until len) out[i] = buf[i] / 32768f
            sink(out)
            len = 0
        }

        fun flush() {
            if (len > 0) flushPartial()
        }
    }

    /** 解码线程：结束时必投递终止标记（EOF 或 AsrError），消费方据此收尾。 */
    private fun decodeToQueue(
        context: Context,
        uri: Uri,
        queue: java.util.concurrent.ArrayBlockingQueue<Any>,
        onProgress: (doneSec: Int, totalSec: Int) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var chunker = PcmChunker(16000) { chunk -> queue.put(chunk) }
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) {
                queue.put(AsrError("文件里没有音频轨"))
                return
            }
            extractor.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                fmt.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val totalSec = durationUs / 1_000_000.0
            val resampler = LinearResampler(srcRate, 16000)
            val mono = ShortArray(65536)
            val info = MediaCodec.BufferInfo()
            var eos = false
            var lastReport = -1

            fun report(us: Long) {
                if (durationUs > 0) {
                    val sec = (us / 1_000_000).toInt()
                    if (sec != lastReport) {
                        lastReport = sec
                        onProgress(sec, totalSec.toInt())
                    }
                }
            }

            if (mime == "audio/raw") {
                val bb = ByteBuffer.allocateDirect(128 * 1024)
                while (!eos) {
                    if (isCancelled()) return
                    bb.clear()
                    val sz = extractor.readSampleData(bb, 0)
                    if (sz < 0) break
                    val frames = downmix(bb, sz, channels, mono)
                    resampler.feed(mono, frames) { s -> chunker.push(s) }
                    report(extractor.sampleTime)
                    extractor.advance()
                }
            } else {
                val c = MediaCodec.createDecoderByType(mime).also { codec = it }
                c.configure(fmt, null, null, 0)
                c.start()
                while (!eos) {
                    if (isCancelled()) return
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = c.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(ib, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    var outIdx = c.dequeueOutputBuffer(info, 10_000)
                    while (outIdx >= 0 && !eos) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val frames = downmix(ob, channels, mono)
                            resampler.feed(mono, frames) { s -> chunker.push(s) }
                        }
                        val eosFlag = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        c.releaseOutputBuffer(outIdx, false)
                        report(info.presentationTimeUs)
                        if (eosFlag) {
                            // EOS 缓冲已释放：立即退出内层循环，否则旧索引会被再次
                            // 处理（"index 0 is not owned by client"，2.0 实测必崩）
                            eos = true
                            break
                        }
                        outIdx = c.dequeueOutputBuffer(info, 0)
                    }
                }
            }
            resampler.flush { s -> chunker.push(s) }
            chunker.flush()
            PlaybackLog.log("asr decode done")
        } catch (e: Exception) {
            PlaybackLog.log("asr decode THREW: ${e.javaClass.simpleName}: ${e.message}")
            queue.put(AsrError("音频解码失败：${e.message}"))
            return
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
        queue.put(asrEofMarker)
    }

    /** 16bit PCM（ByteBuffer 已定位）→ 单声道 shorts（多声道取平均）。 */
    private fun downmix(bb: ByteBuffer, channels: Int, mono: ShortArray): Int {
        val sb = bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val total = sb.remaining()
        val frames = total / channels
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += sb.get(f * channels + c).toInt()
            mono[f] = (acc / channels).toShort()
        }
        return frames
    }

    private fun downmix(bb: ByteBuffer, size: Int, channels: Int, mono: ShortArray): Int {
        bb.position(0)
        bb.limit(size)
        return downmix(bb, channels, mono)
    }

    /** 线性插值重采样：流式、恒定内存。 */
    private class LinearResampler(private val srcRate: Int, private val outRate: Int) {
        private val step = srcRate.toDouble() / outRate
        private var nextPos = 0.0
        private var base = 0
        private var buf = ShortArray(1 shl 15)
        private var len = 0

        fun feed(input: ShortArray, n: Int, emit: (Short) -> Unit) {
            if (n <= 0) return
            if (len + n > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            }
            System.arraycopy(input, 0, buf, len, n)
            len += n
            while (true) {
                val i0 = floor(nextPos).toInt() - base
                if (i0 + 1 >= len) break
                val frac = nextPos - floor(nextPos)
                val s0 = buf[i0].toInt()
                val s1 = buf[i0 + 1].toInt()
                emit((s0 + ((s1 - s0) * frac).toInt()).toShort())
                nextPos += step
            }
            val keep = (floor(nextPos).toInt() - base).coerceIn(0, len)
            if (keep > 0) {
                System.arraycopy(buf, keep, buf, 0, len - keep)
                len -= keep
                base += keep
            }
        }

        fun flush(emit: (Short) -> Unit) {
            while (true) {
                val i0 = floor(nextPos).toInt() - base
                if (i0 !in 0 until len) break
                emit(buf[i0])
                nextPos += step
            }
        }
    }

    // ---------- 识别：消费解码队列 → 分窗（实时上抛）→ 行 ----------

    private fun recognizeStreaming(
        queue: java.util.concurrent.ArrayBlockingQueue<Any>,
        modelDir: File,
        hotwordsFile: File?,
        onWindowDone: (linesSoFar: List<LrcLine>) -> Unit,
        isCancelled: () -> Boolean,
        err: (String) -> Unit
    ): List<LrcLine>? {
        val tokens = mutableListOf<Pair<Double, String>>()
        try {
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                            decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                            joiner = (File(modelDir, "joiner-epoch-99-avg-1.int8.onnx")
                                .takeIf { it.exists() }
                                ?: File(modelDir, "joiner-epoch-99-avg-1.onnx")).absolutePath
                        ),
                        tokens = File(modelDir, TOKENS_FILE).absolutePath,
                        numThreads = 2,
                        modelType = "transducer",
                        modelingUnit = "cjkchar"
                    ),
                    decodingMethod = if (hotwordsFile != null) "modified_beam_search" else "greedy_search",
                    hotwordsFile = hotwordsFile?.absolutePath ?: "",
                    hotwordsScore = 1.5f
                )
            )
            try {
                val windowSec = 30.0
                val overlapSec = 1.5
                val windowSamples = (16000 * (windowSec + overlapSec)).toInt()
                val pending = ArrayDeque<FloatArray>()
                var pendingSamples = 0
                var eof = false
                var emittedUntil = -1.0
                var windowStart = 0.0

                fun takeWindow(target: FloatArray): Int {
                    var filled = 0
                    while (filled < target.size && pending.isNotEmpty()) {
                        val head = pending.first()
                        val take = minOf(target.size - filled, head.size)
                        System.arraycopy(head, 0, target, filled, take)
                        filled += take
                        if (take >= head.size) {
                            pending.removeFirst()
                        } else {
                            pending[0] = head.copyOfRange(take, head.size)
                        }
                        pendingSamples -= take
                    }
                    return filled
                }

                while (true) {
                    if (isCancelled()) {
                        err("已取消")
                        return null
                    }
                    // 凑窗：不满窗且未结束时持续拉队列（250ms 轮询以便响应取消）
                    if (pendingSamples < windowSamples && !eof) {
                        val item = try {
                            queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } catch (e: InterruptedException) {
                            null
                        }
                        when (item) {
                            null -> {}
                            is FloatArray -> {
                                pending.add(item)
                                pendingSamples += item.size
                            }
                            is AsrError -> {
                                err(item.msg)
                                return null
                            }
                            else -> eof = true
                        }
                        if (pendingSamples < windowSamples && !eof) continue
                    }
                    if (pendingSamples <= 0) {
                        if (eof) break
                        continue
                    }
                    // 识别一个窗
                    val window = FloatArray(minOf(windowSamples, pendingSamples))
                    takeWindow(window)
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(window, sampleRate = 16000)
                    recognizer.decode(stream)
                    val r = recognizer.getResult(stream)
                    for (i in r.tokens.indices) {
                        val t = windowStart + r.timestamps[i]
                        if (t >= emittedUntil) {
                            tokens.add(t to r.tokens[i])
                            emittedUntil = t + 0.05
                        }
                    }
                    stream.release()
                    windowStart += windowSec
                    // 实时：本窗结果立即切行上抛（UI 预览 / 逐窗翻译联动）
                    if (r.tokens.isNotEmpty()) {
                        onWindowDone(tokensToLines(tokens))
                    }
                    // 回退 1.5s 重叠区，下一窗从这里开始（重叠 token 由 emittedUntil 去重）
                    val rewind = (16000 * overlapSec).toInt()
                    if (window.size > rewind) {
                        pending.addFirst(window.copyOfRange(window.size - rewind, window.size))
                        pendingSamples += rewind
                    }
                }
            } finally {
                recognizer.release()
            }
        } catch (e: Exception) {
            PlaybackLog.log("asr recognize THREW: ${e.javaClass.simpleName}: ${e.message}")
            err("识别失败：${e.message}")
            return null
        }
        if (tokens.isEmpty()) {
            err("没有识别到任何语音内容（纯音乐或模型不匹配？）")
            return null
        }
        PlaybackLog.log("asr recognize done: ${tokens.size} tokens")
        return tokensToLines(tokens)
    }

    private val sentenceEnds = "。！？!?.,、，…"

    private fun tokensToLines(tokens: List<Pair<Double, String>>): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        var cur = StringBuilder()
        var start = -1.0
        var last = -1.0

        fun flush() {
            val s = start
            val e = last
            val txt = cur.toString()
            cur.setLength(0)
            start = -1.0
            last = -1.0
            if (s < 0 || txt.isBlank()) return
            if (txt.all { sentenceEnds.contains(it) }) return
            lines.add(LrcLine(s, e, txt))
        }

        for ((t, tok) in tokens) {
            if (tok.isBlank()) continue
            if (last >= 0 && t - last > 0.6) flush()
            if (start < 0) start = t
            cur.append(tok)
            last = t
            if (tok.any { sentenceEnds.contains(it) }) {
                flush()
            } else if (cur.length >= 16) {
                flush()
            }
        }
        flush()
        return lines
    }

    // ---------- LRC 增量落盘 ----------

    private data class LrcTarget(val safUri: Uri?, val file: File?, val desc: String)

    private fun internalLrcTarget(context: Context, audioUri: Uri): LrcTarget {
        val stem = (audioUri.lastPathSegment ?: "lyrics")
            .substringAfterLast('/').substringBeforeLast('.')
        val dir = File(context.filesDir, "asr_lrc").apply { mkdirs() }
        return LrcTarget(null, File(dir, "$stem.lrc"), "应用内 asr_lrc/$stem.lrc")
    }

    /** 识别开始前创建 lrc 目标：SAF 同目录优先（自动入歌词库），兜底应用私有目录。 */
    private fun createLrcTarget(context: Context, audioUri: Uri, treeUris: List<Uri>): LrcTarget {
        val docId = try {
            DocumentsContract.getDocumentId(audioUri)
        } catch (e: Exception) {
            PlaybackLog.log("asr lrc target: getDocumentId failed ${e.message}")
            null
        }
        val stem = (docId ?: audioUri.lastPathSegment ?: "lyrics")
            .substringAfterLast('/').substringBeforeLast('.')
        if (docId != null) {
            for (tree in treeUris) {
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(tree)
                    if (docId != treeDocId && !docId.startsWith("$treeDocId/")) continue
                    val parentDocId = if (docId.contains('/')) {
                        docId.substringBeforeLast('/')
                    } else treeDocId
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
                    val lrcUri = DocumentsContract.createDocument(
                        context.contentResolver, parentUri, "text/plain", "$stem.lrc"
                    )
                    if (lrcUri != null) {
                        // 创建后立刻试写一次：确认可写，不可写走兜底
                        context.contentResolver.openOutputStream(lrcUri)?.use {
                            it.write(" ".toByteArray())
                        }
                        PlaybackLog.log("asr lrc target OK (SAF) $stem.lrc")
                        return LrcTarget(lrcUri, null, "音频同目录 $stem.lrc")
                    }
                    PlaybackLog.log("asr lrc target: createDocument null")
                } catch (e: Exception) {
                    PlaybackLog.log("asr lrc target THREW: ${e.message}")
                }
            }
        } else {
            PlaybackLog.log("asr lrc target: non-document uri -> internal")
        }
        val fb = internalLrcTarget(context, audioUri)
        PlaybackLog.log("asr lrc target fallback -> ${fb.desc}")
        return fb
    }

    private fun writeLrc(context: Context, target: LrcTarget, content: String): Boolean {
        return try {
            if (target.safUri != null) {
                context.contentResolver.openOutputStream(target.safUri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                } != null
            } else {
                target.file?.writeText(content)
                true
            }
        } catch (e: Exception) {
            PlaybackLog.log("asr writeLrc THREW: ${e.message}")
            false
        }
    }

    private fun describeTarget(context: Context, audioUri: Uri, target: LrcTarget): String = target.desc

    private fun buildLrc(lines: List<LrcLine>): String {
        val sb = StringBuilder()
        for (l in lines) {
            sb.append(fmtLrcTime(l.startSec)).append(l.text).append('\n')
        }
        return sb.toString()
    }

    private fun fmtLrcTime(t: Double): String {
        val totalSec = floor(t).toInt().coerceAtLeast(0)
        val mm = totalSec / 60
        val ss = totalSec % 60
        val cs = ((t - totalSec) * 100).toInt().coerceIn(0, 99)
        return String.format(Locale.US, "[%02d:%02d.%02d]", mm, ss, cs)
    }

    /** 兼容旧调用：保存整份歌词到音频同目录 / 应用私有目录。 */
    fun saveLrcNextToAudio(
        context: Context,
        audioUri: Uri,
        treeUris: List<Uri>,
        content: String
    ): String? {
        val target = createLrcTarget(context, audioUri, treeUris)
        return if (writeLrc(context, target, content)) target.desc else null
    }

    /**
     * 把应用内兜底的识别歌词（filesDir/asr_lrc/*.lrc）补写到音频同目录。
     *
     * 2.0 及更早：选文件夹时只申请了读权限，识别出的 .lrc 只能存应用内（所有机型都一样）。
     * 2.1 修好了选文件夹的写权限申请；用户重选一次文件夹后，这些已识别好的歌词会自动
     * 补写回音乐文件夹（不必重新识别），成功后删掉应用内副本。
     * @return 成功补写的文件数
     */
    fun flushInternalLrc(context: Context, songs: List<Song>, treeUris: List<Uri>): Int {
        val files = File(context.filesDir, "asr_lrc")
            .listFiles { f -> f.isFile && f.name.endsWith(".lrc", ignoreCase = true) }
            ?: return 0
        if (files.isEmpty()) return 0
        var ok = 0
        for (f in files) {
            val stem = f.name.substringBeforeLast('.')
            val song = songs.firstOrNull { it.fileStem.equals(stem, ignoreCase = true) }
                ?: continue
            try {
                val target = createLrcTarget(context, song.uri, treeUris)
                if (target.safUri == null) continue   // 还是没有写权限：留到下次
                if (writeLrc(context, target, f.readText())) {
                    f.delete()
                    ok++
                    PlaybackLog.log("asr lrc flushed -> ${target.desc}")
                }
            } catch (e: Exception) {
                PlaybackLog.log("asr lrc flush THREW: ${e.message}")
            }
        }
        if (ok > 0) PlaybackLog.log("asr lrc flush done: $ok file(s)")
        return ok
    }
}

/** 一行歌词（秒）。 */
data class LrcLine(val startSec: Double, val endSec: Double, val text: String)
