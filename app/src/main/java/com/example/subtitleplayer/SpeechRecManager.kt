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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.floor

/**
 * 歌词识别（1.33.1 实验，日语特化）：
 * sherpa-onnx 离线 zipformer（ReazonSpeech 35k 小时，int8 ≈162MB）→
 * 音频（SAF uri）解码为 16k 单声道 PCM → 分窗识别 → 词级时间戳 →
 * 按标点/间隙/长度切行 → 生成 .lrc 保存到音频同目录（SAF）或应用私有目录。
 *
 * 设计要点：
 * - 音频先落盘为 s16le 原始 PCM 再分窗读取：30 分钟音频仅 58MB 磁盘，
 *   避免整段音频驻留内存（低端机 OOM 风险）
 * - 分窗 30s + 1.5s 重叠：重叠区按 token 时间戳去重，边界不切丢整句
 * - 生成的 .lrc 与拾音现有歌词匹配体系（LibraryScanner）天然兼容：
 *   重新扫描后 findLyric 按同目录同名 stem 命中，播放即显歌词
 */
object SpeechRecManager {

    private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    private const val DECODER = "decoder-epoch-99-avg-1.onnx"
    private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    private const val TOKENS = "tokens.txt"

    /** 4 个必需文件（总下载约 162MB）。 */
    val requiredFiles = listOf(ENCODER, DECODER, JOINER, TOKENS)

    /** 模型镜像源（按序尝试；均为 HF 单文件直链，避免 680MB tar 包）。
     *  hf-mirror.com 放首位：国内无需 VPN 可直连；huggingface 原站作回退。 */
    private val modelSources = listOf(
        "https://hf-mirror.com/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://hf-mirror.com/k2-fsa/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/"
    )

    fun modelDir(context: Context): File = File(context.filesDir, "asr_model")

    fun isModelReady(context: Context): Boolean =
        requiredFiles.all { File(modelDir(context), it).let { f -> f.exists() && f.length() > 1024 } }

    /** 下载模型（后台线程）。onProgress(第几个文件, 当前文件百分比)。 */
    fun downloadModel(
        context: Context,
        onProgress: (fileIndex: Int, filePercent: Int) -> Unit,
        onDone: (ok: Boolean, errMsg: String?) -> Unit
    ) {
        Thread {
            val dir = modelDir(context).apply { mkdirs() }
            for ((idx, name) in requiredFiles.withIndex()) {
                val target = File(dir, name)
                if (target.exists() && target.length() > 1024) {
                    onProgress(idx, 100)
                    continue
                }
                var downloaded = false
                var lastErr: String? = null
                for (base in modelSources) {
                    try {
                        val conn = URL(base + name).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 60_000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "ShiYin/1.33.1")
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            lastErr = "HTTP $code"
                            conn.disconnect()
                            continue
                        }
                        val total = conn.contentLengthLong
                        val tmp = File(dir, "$name.tmp")
                        var acc = 0L
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    acc += n
                                    if (total > 0) {
                                        onProgress(idx, ((acc * 100) / total).toInt().coerceIn(0, 100))
                                    }
                                }
                            }
                        }
                        if (tmp.length() < 1024) {
                            // 404 页面等错误内容
                            tmp.delete()
                            lastErr = "内容异常 (${tmp.length()}B)"
                            continue
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        lastErr = e.message
                    }
                }
                if (!downloaded) {
                    PlaybackLog.log("asr model download FAIL $name: $lastErr")
                    onDone(false, "$name（$lastErr）")
                    return@Thread
                }
                PlaybackLog.log("asr model download OK $name")
            }
            PlaybackLog.log("asr model download ALL DONE")
            onDone(true, null)
        }.start()
    }

    /**
     * 识别并生成歌词。
     * onDone(ok, errMsg, savedWhere, lines)：ok=false 时 errMsg 可读、lines=null；
     * savedWhere 为保存位置描述，lines 为识别出的歌词行（供后续直接翻译）。
     */
    fun transcribe(
        context: Context,
        audioUri: Uri,
        treeUris: List<Uri>,
        onProgress: (doneSec: Int, totalSec: Int) -> Unit,
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
                PlaybackLog.log("asr decode start uri=$audioUri")
                val totalSec = decodeToPcm16k(
                    context, audioUri, pcm, onProgress, isCancelled, onDone
                ) ?: return@Thread  // 已通过 onDone 上报
                if (isCancelled()) {
                    onDone(false, "已取消", null, null)
                    return@Thread
                }
                val lines = recognize(modelDir(context), pcm, totalSec, onProgress, isCancelled) { errMsg ->
                    pcm.delete()
                    onDone(false, errMsg, null, null)
                }
                if (lines == null) return@Thread  // 已上报
                if (isCancelled()) {
                    pcm.delete()
                    onDone(false, "已取消", null, null)
                    return@Thread
                }
                val content = buildLrc(lines)
                val where = saveLrcNextToAudio(context, audioUri, treeUris, content)
                pcm.delete()
                if (where == null) {
                    onDone(false, "保存歌词文件失败", null, null)
                } else {
                    PlaybackLog.log("asr done: ${lines.size} lines -> $where")
                    onDone(true, null, where, lines)
                }
            } catch (e: Exception) {
                PlaybackLog.log("asr THREW: ${e.javaClass.simpleName}: ${e.message}")
                pcm.delete()
                onDone(false, "${e.javaClass.simpleName}: ${e.message}", null, null)
            }
        }.start()
    }

    // ---------- 音频解码：任意音频 → 16kHz 单声道 s16le 原始 PCM 文件 ----------

    /**
     * 返回音频总秒数；出错/取消时已回调 onDone 并返回 null。
     * PCM 16bit 单声道 16kHz：30 分钟 ≈ 58MB 磁盘，内存占用恒定。
     */
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
                onDone(false, "文件里没有音频轨", null)
                return null
            }
            extractor.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                fmt.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val totalSec = (durationUs / 1_000_000.0)
            val resampler = LinearResampler(srcRate, 16000)
            val sink = DataOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024))
            // 128KB 输入块 → 单声道最多 65536 帧
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
                // WAV 等：提取器直接给 PCM
                val bb = ByteBuffer.allocateDirect(128 * 1024)
                while (!eos) {
                    if (isCancelled()) {
                        sink.close()
                        onDone(false, "已取消", null)
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
                        onDone(false, "已取消", null)
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
                    while (outIdx >= 0) {
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
                        if (eosFlag) eos = true
                        if (!eos) outIdx = c.dequeueOutputBuffer(info, 0)
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
            onDone(false, "音频解码失败：${e.message}", null)
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
            val keep = floor(nextPos).toInt() - base
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

    // ---------- 识别：PCM 文件 → 分窗 → token+时间戳 → 行 ----------

    /** 出错时回调 err（并返回 null）；正常返回行列表。 */
    private fun recognize(
        modelDir: File,
        pcm: File,
        totalSec: Double,
        onProgress: (doneSec: Int, totalSec: Int) -> Unit,
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
                            encoder = File(modelDir, ENCODER).absolutePath,
                            decoder = File(modelDir, DECODER).absolutePath,
                            joiner = File(modelDir, JOINER).absolutePath
                        ),
                        tokens = File(modelDir, TOKENS).absolutePath,
                        numThreads = 2,
                        modelType = "transducer"
                    )
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

    // ---------- LRC 生成与保存 ----------

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

    /**
     * 保存 .lrc：优先写音频同目录（借已授权的扫描根树），保证下次扫描能自动
     * 收进歌词库；无树权限时落到应用私有目录 asr_lrc/。返回位置描述，失败 null。
     */
    fun saveLrcNextToAudio(
        context: Context,
        audioUri: Uri,
        treeUris: List<Uri>,
        content: String
    ): String? {
        val docId = try {
            DocumentsContract.getDocumentId(audioUri)
        } catch (e: Exception) {
            null
        }
        if (docId != null) {
            val stem = docId.substringAfterLast('/').substringBeforeLast('.')
            for (tree in treeUris) {
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(tree)
                    if (docId != treeDocId && !docId.startsWith("$treeDocId/")) continue
                    val parentDocId = if (docId.contains('/')) docId.substringBeforeLast('/') else treeDocId
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
                    val lrcUri = DocumentsContract.createDocument(
                        context.contentResolver, parentUri, "text/plain", "$stem.lrc"
                    ) ?: continue
                    context.contentResolver.openOutputStream(lrcUri)?.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    } ?: continue
                    return "音频同目录 $stem.lrc"
                } catch (e: Exception) {
                    // 换下一棵树 / 兜底
                }
            }
        }
        return try {
            val stem = (audioUri.lastPathSegment ?: "lyrics")
                .substringAfterLast('/').substringBeforeLast('.')
            val dir = File(context.filesDir, "asr_lrc").apply { mkdirs() }
            val f = File(dir, "$stem.lrc")
            f.writeText(content)
            "应用内 asr_lrc/$stem.lrc"
        } catch (e: Exception) {
            null
        }
    }
}

/** 一行歌词（秒）。 */
data class LrcLine(val startSec: Double, val endSec: Double, val text: String)
