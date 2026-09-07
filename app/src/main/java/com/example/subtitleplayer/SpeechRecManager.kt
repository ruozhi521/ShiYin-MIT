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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
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
                                val conn = URL(base + name).openConnection() as HttpURLConnection
                                conn.connectTimeout = 20_000
                                conn.readTimeout = 45_000
                                conn.instanceFollowRedirects = true
                                conn.setRequestProperty("User-Agent", "ShiYin/2.0")
                                val code = conn.responseCode
                                if (code !in 200..299) {
                                    lastErr = "$name HTTP $code"
                                    conn.disconnect()
                                    continue
                                }
                                val total = conn.contentLengthLong
                                val tmp = File(dir, "$name.tmp")
                                var acc = 0L
                                var sane = true
                                conn.inputStream.use { input ->
                                    tmp.outputStream().use { out ->
                                        val buf = ByteArray(64 * 1024)
                                        var first = true
                                        while (true) {
                                            // 收满 Content-Length 立即结束：hf-mirror 等代理
                                            // 不会主动断流，等 EOF 会卡到读超时（2.0 实测）
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
                                if (!sane || acc < 1024) {
                                    tmp.delete()
                                    lastErr = "$name 内容异常（可能 404 页面）"
                                    continue
                                }
                                val target = File(dir, name)
                                if (!tmp.renameTo(target)) {
                                    tmp.copyTo(target, overwrite = true)
                                    tmp.delete()
                                }
                                downloaded = target
                                onProgress(idx, 100)
                                PlaybackLog.log("asr model OK ${mf.role} <- $base$name")
                                break@attempt
                            } catch (e: Exception) {
                                lastErr = "${e.javaClass.simpleName}: ${e.message}"
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

    /**
     * 台本文本 → sherpa hotwords 文件（每行「词组 1.5」，去重、限长限量）。
     * modified_beam_search 下热词显著提升专有名词识别率。
     */
    private fun writeHotwords(context: Context, scriptText: String): File? {
        return try {
            val phrases = LinkedHashSet<String>()
            for (raw in scriptText.lines()) {
                for (seg in raw.split(Regex("[。！？!?.,、，…\\s　]+"))) {
                    val p = seg.trim()
                    if (p.isEmpty() || p.length < 2 || p.length > 20) continue
                    if (p.all { it.isDigit() || it in "0-9.-" }) continue
                    phrases.add(p)
                    if (phrases.size >= 300) break
                }
                if (phrases.size >= 300) break
            }
            if (phrases.isEmpty()) return null
            val f = File(modelDir(context), HOTWORDS_FILE)
            f.writeText(phrases.joinToString("\n") { "$it 1.5" })
            PlaybackLog.log("asr hotwords: ${phrases.size} phrases")
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
            val pcm = File(context.cacheDir, "asr_pcm_16k.raw")
            try {
                PlaybackLog.log("asr decode start uri=$audioUri script=${scriptText?.length ?: 0}chars")
                val totalSec = decodeToPcm16k(
                    context, audioUri, pcm, onProgress, isCancelled, onDone
                ) ?: return@Thread
                if (isCancelled()) {
                    onDone(false, "已取消", null, null)
                    return@Thread
                }
                // lrc 目标在识别开始前建好：每窗写入，边识别边落盘（取消也保留已识别部分）。
                // SAF 写失败时自动切换应用内兜底，绝不静默丢歌词
                var target = createLrcTarget(context, audioUri, treeUris)
                if (!writeLrc(context, target, "")) {
                    PlaybackLog.log("asr SAF target unusable -> fallback internal")
                    target = internalLrcTarget(context, audioUri)
                    writeLrc(context, target, "")
                }
                val lines = recognize(
                    modelDir(context), pcm, totalSec,
                    scriptText?.let { writeHotwords(context, it) },
                    onProgress,
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
            } finally {
                pcm.delete()
            }
        }.start()
    }

    // ---------- 音频解码：任意音频 → 16kHz 单声道 s16le 原始 PCM 文件 ----------

    private fun decodeToPcm16k(
        context: Context,
        uri: Uri,
        out: File,
        onProgress: (doneSec: Int, totalSec: Int) -> Unit,
        isCancelled: () -> Boolean,
        onDone: (ok: Boolean, errMsg: String?, savedWhere: String?, lines: List<LrcLine>?) -> Unit
    ): Double? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
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
                onDone(false, "文件里没有音频轨", null, null)
                return null
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
            val sink = DataOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024))
            val mono = ShortArray(65536)
            val info = MediaCodec.BufferInfo()
            var eos = false
            var lastReport = -1

            fun emit(s: Short) {
                sink.write(s.toInt() and 0xFF)
                sink.write((s.toInt() shr 8) and 0xFF)
            }
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
                    if (isCancelled()) {
                        sink.close()
                        onDone(false, "已取消", null, null)
                        return null
                    }
                    bb.clear()
                    val sz = extractor.readSampleData(bb, 0)
                    if (sz < 0) break
                    val frames = downmix(bb, sz, channels, mono)
                    resampler.feed(mono, frames, ::emit)
                    report(extractor.sampleTime)
                    extractor.advance()
                }
            } else {
                val c = MediaCodec.createDecoderByType(mime).also { codec = it }
                c.configure(fmt, null, null, 0)
                c.start()
                while (!eos) {
                    if (isCancelled()) {
                        sink.close()
                        onDone(false, "已取消", null, null)
                        return null
                    }
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
                            resampler.feed(mono, frames, ::emit)
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
            resampler.flush(::emit)
            sink.flush()
            sink.close()
            val secs = out.length() / 2.0 / 16000.0
            PlaybackLog.log("asr decode done: ${"%.1f".format(secs)}s pcm=${out.length()}B")
            return secs
        } catch (e: Exception) {
            PlaybackLog.log("asr decode THREW: ${e.javaClass.simpleName}: ${e.message}")
            onDone(false, "音频解码失败：${e.message}", null, null)
            return null
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

    // ---------- 识别：PCM → 分窗（实时上抛）→ 行 ----------

    private fun recognize(
        modelDir: File,
        pcm: File,
        totalSec: Double,
        hotwordsFile: File?,
        onProgress: (doneSec: Int, totalSec: Int) -> Unit,
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
                val input = DataInputStream(BufferedInputStream(FileInputStream(pcm), 64 * 1024))
                var emittedUntil = -1.0
                var windowStart = 0.0
                try {
                    while (true) {
                        if (isCancelled()) {
                            err("已取消")
                            return null
                        }
                        val samples = FloatArray(windowSamples)
                        var filled = 0
                        while (filled < windowSamples) {
                            val wantBytes = (windowSamples - filled) * 2
                            val chunk = ByteArray(minOf(8192, wantBytes))
                            var got = 0
                            while (got < chunk.size) {
                                val n = input.read(chunk, got, chunk.size - got)
                                if (n < 0) break
                                got += n
                            }
                            if (got <= 0) break
                            val sb = ByteBuffer.wrap(chunk, 0, got)
                                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = sb.remaining()
                            for (i in 0 until n) {
                                samples[filled + i] = sb.get(i) / 32768f
                            }
                            filled += n
                        }
                        if (filled == 0) break
                        val stream = recognizer.createStream()
                        stream.acceptWaveform(samples.copyOf(filled), sampleRate = 16000)
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
                        onProgress(
                            windowStart.toInt().coerceAtMost(totalSec.toInt()),
                            totalSec.toInt()
                        )
                        // 实时：本窗结果立即切行上抛（UI 预览 / 逐窗翻译联动）
                        if (r.tokens.isNotEmpty()) {
                            onWindowDone(tokensToLines(tokens))
                        }
                    }
                } finally {
                    try {
                        input.close()
                    } catch (_: Exception) {
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
}

/** 一行歌词（秒）。 */
data class LrcLine(val startSec: Double, val endSec: Double, val text: String)
