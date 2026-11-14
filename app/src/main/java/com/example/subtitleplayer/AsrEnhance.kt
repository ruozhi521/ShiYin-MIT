package com.example.subtitleplayer

import android.content.Context
import java.io.File

/**
 * 识别用的音频增强：VAD（语音活动检测）与降噪（3.1）。
 *
 * 解决的问题：3.1 之前音频是**固定 30 秒硬切窗**直接喂给识别器 ——
 * 前奏、间奏、纯伴奏段全都被当作「语音」送进去。粉丝反馈的
 * 「复杂场景识别不准」「出现莫名其妙的内容」，很大一部分不是模型不够强，
 * 而是**输入端就被污染了**：模型在一段纯音乐上被要求「听写」，它只能硬猜。
 *
 * 两件事分开做，因为收益与代价完全不同：
 *
 * - **VAD**（[vadModelFile]，约 2.3 MB）：判断一段音频里有没有人声。
 *   纯音乐窗直接跳过识别 —— 省时间、也不给模型硬猜的机会。
 *   这是**低风险高收益**的一项：跳过的窗本来也识别不出东西。
 * - **降噪**（[denoiseModelFile]，约 0.5 MB）：把人声从背景噪音里拎出来。
 *   对录音质量差的音源有用；但对**已经混好的音乐**可能连带削掉人声细节，
 *   所以默认关，交给用户试。
 *
 * ⚠️ 这两个模型是可选的：没下载也能正常识别（只是少了这层处理）。
 * 所以所有入口都要容忍「模型不存在」，绝不能因此让识别失败。
 */
object AsrEnhance {

    /** 模型下载源（单个文件，直链）。hf-mirror 国内可直连。 */
    private val VAD_URLS = listOf(
        "https://hf-mirror.com/deepghs/silero-vad-onnx/resolve/main/silero_vad.onnx",
        "https://huggingface.co/deepghs/silero-vad-onnx/resolve/main/silero_vad.onnx"
    )

    private val DENOISE_URLS = listOf(
        "https://hf-mirror.com/bitsydarel/gtcrn-onnx/resolve/main/gtcrn_simple.onnx",
        "https://huggingface.co/bitsydarel/gtcrn-onnx/resolve/main/gtcrn_simple.onnx"
    )

    private const val VAD_FILE = "silero_vad.onnx"
    private const val DENOISE_FILE = "gtcrn_simple.onnx"

    /** 文件合理性下限（实测：VAD 2,327,524 B、GTCRN 535,638 B，都留足余量）。 */
    private const val VAD_MIN_BYTES = 1_000_000L
    private const val DENOISE_MIN_BYTES = 200_000L

    /** 增强模型放在模型根目录下的独立子目录，不跟 ASR 模型混。 */
    fun enhanceDir(context: Context): File =
        File(context.filesDir, "asr_enhance").apply { mkdirs() }

    fun vadModelFile(context: Context): File? {
        val f = File(enhanceDir(context), VAD_FILE)
        return if (f.isFile && f.length() >= VAD_MIN_BYTES) f else null
    }

    fun denoiseModelFile(context: Context): File? {
        val f = File(enhanceDir(context), DENOISE_FILE)
        return if (f.isFile && f.length() >= DENOISE_MIN_BYTES) f else null
    }

    fun hasVad(context: Context): Boolean = vadModelFile(context) != null

    fun hasDenoise(context: Context): Boolean = denoiseModelFile(context) != null

    /**
     * 下载增强模型（VAD 必下，降噪按需）。
     *
     * 刻意**不复用** ASR 那套按角色/多镜像的下载器：这里只有两个单文件、
     * 来源固定，套那套反而绕。失败就是失败，用户再点一次即可。
     */
    fun download(
        context: Context,
        wantVad: Boolean,
        wantDenoise: Boolean,
        onProgress: (msg: String) -> Unit,
        onDone: (ok: Boolean, err: String?) -> Unit
    ) {
        Thread {
            val dir = enhanceDir(context)
            try {
                if (wantVad && !hasVad(context)) {
                    onProgress("VAD")
                    if (!fetch(VAD_URLS, File(dir, VAD_FILE), VAD_MIN_BYTES)) {
                        onDone(false, "VAD 模型下载失败"); return@Thread
                    }
                }
                if (wantDenoise && !hasDenoise(context)) {
                    onProgress("降噪")
                    if (!fetch(DENOISE_URLS, File(dir, DENOISE_FILE), DENOISE_MIN_BYTES)) {
                        onDone(false, "降噪模型下载失败"); return@Thread
                    }
                }
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }.start()
    }

    /** 逐个源尝试下载到 [target]；成功后校验大小。 */
    private fun fetch(urls: List<String>, target: File, minBytes: Long): Boolean {
        for (u in urls) {
            try {
                val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "ShiYin/3.1")
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val tmp = File(target.parentFile, target.name + ".tmp")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                conn.disconnect()
                if (tmp.length() < minBytes) {
                    tmp.delete()
                    continue
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                PlaybackLog.log("asr enhance OK ${target.name} ${target.length()}B")
                return true
            } catch (e: Exception) {
                PlaybackLog.log("asr enhance FAIL $u: ${e.message}")
            }
        }
        return false
    }

    /**
     * 一次识别期间复用的增强会话（3.1）。
     *
     * ⚠️ **必须复用，不能每窗新建**：sherpa 的 VAD / 降噪模型每次构造都要加载
     * onnx 并初始化，30 分钟音频有 60 个窗口 —— 每窗重建会让识别从「1/3 实时」
     * 退化成比实时还慢。这里把实例持有到整段音频结束，用完 [close]。
     *
     * 两者的模型文件都不存在时，对应方法返回 null，调用方按「没做增强」继续 ——
     * 增强是可选优化，绝不能因为它缺失或出错就让识别失败。
     */
    class Session(
        private val vad: com.k2fsa.sherpa.onnx.Vad?,
        private val denoiser: com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser?,
        private val sampleRate: Int
    ) : AutoCloseable {

        /** 这段音频里有没有人声。`null` = 判断不了（没模型 / 出错），调用方应按「有」处理。 */
        fun hasSpeech(samples: FloatArray): Boolean? {
            val v = vad ?: return null
            return try {
                v.acceptWaveform(samples)
                val detected = v.isSpeechDetected()
                // VAD 是累积式的：判断完必须清空，否则下一个窗会带着上一窗的结论
                v.reset()
                detected
            } catch (e: Throwable) {
                PlaybackLog.log("asr vad THREW: ${e.message}")
                null
            }
        }

        /** 降噪后的样本；`null` = 处理不了，调用方用原始音频。 */
        fun denoise(samples: FloatArray): FloatArray? {
            val d = denoiser ?: return null
            return try {
                d.run(samples, sampleRate)?.samples
            } catch (e: Throwable) {
                PlaybackLog.log("asr denoise THREW: ${e.message}")
                null
            }
        }

        override fun close() {
            try {
                vad?.release()
            } catch (_: Throwable) {
            }
            try {
                denoiser?.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 按用户开关创建增强会话。
     *
     * @param wantVad 启用 VAD（跳过纯音乐窗）
     * @param wantDenoise 启用降噪（默认关，因为它可能削掉人声细节）
     */
    fun newSession(
        context: Context,
        wantVad: Boolean,
        wantDenoise: Boolean,
        sampleRate: Int = 16000
    ): Session {
        val vad = if (wantVad) buildVad(context, sampleRate) else null
        val denoiser = if (wantDenoise) buildDenoiser(context) else null
        if (vad != null) PlaybackLog.log("asr enhance: VAD on")
        if (denoiser != null) PlaybackLog.log("asr enhance: denoise on")
        return Session(vad, denoiser, sampleRate)
    }

    private fun buildVad(context: Context, sampleRate: Int): com.k2fsa.sherpa.onnx.Vad? {
        val model = vadModelFile(context) ?: return null
        return try {
            // ⚠️ 一律用**位置参数**：这些类在 Java 侧可见的签名里参数名不可见，
            // 写命名参数会直接编译失败，而且报错信息很难懂。
            val silero = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model.absolutePath, // model
                0.5f, // threshold：越高越严格
                0.25f, // minSilenceDuration
                0.25f, // minSpeechDuration
                512, // windowSize
                20f // maxSpeechDuration
            )
            val cfg = com.k2fsa.sherpa.onnx.VadModelConfig(
                silero, // sileroVadModelConfig
                com.k2fsa.sherpa.onnx.TenVadModelConfig(), // tenVadModelConfig（不用，但不能给 null）
                sampleRate, // sampleRate
                1, // numThreads
                "cpu", // provider
                false // debug
            )
            com.k2fsa.sherpa.onnx.Vad(context.assets, cfg)
        } catch (e: Throwable) {
            PlaybackLog.log("asr vad build THREW: ${e.message}")
            null
        }
    }

    private fun buildDenoiser(context: Context): com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser? {
        val model = denoiseModelFile(context) ?: return null
        return try {
            val gtcrn = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig(
                model.absolutePath
            )
            val modelCfg = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig(
                gtcrn, // gtcrn
                com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig(), // dpdfnet（不用，但不能给 null）
                1, // numThreads
                false, // debug
                "cpu" // provider
            )
            val cfg = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig(modelCfg)
            com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser(context.assets, cfg)
        } catch (e: Throwable) {
            PlaybackLog.log("asr denoiser build THREW: ${e.message}")
            null
        }
    }
}
