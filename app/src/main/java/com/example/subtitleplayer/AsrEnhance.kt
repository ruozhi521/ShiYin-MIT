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

    // ---------- 原生崩溃哨兵（3.1.1） ----------

    private const val PREFS = "asr_enhance"
    private const val KEY_DENOISE_PENDING = "denoise_pending"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 上一次开启降噪后，进程是否**在成功跑出一次结果之前**就没了（3.1.1）。
     *
     * 为什么需要这个：降噪失败发生在 sherpa 的**原生代码**里，崩法包括
     * `CHECK` 失败、`abort`、段错误 —— 进程当场消失，Java 层的 try/catch
     * 和 [CrashCatcher]（装的是 Thread.UncaughtExceptionHandler）**一个都拦不到**。
     * 用户看到的就是「开启降噪 → 点歌词识别 → 直接闪退」，连崩溃日志都没有。
     *
     * 所以用一个「先写标记、成功才清」的哨兵：只要下次启动时标记还在，
     * 就说明上次是死在这一步 —— 这次直接跳过降噪，保证不再因为同一个原因崩第二次。
     */
    fun denoiseCrashedLastTime(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DENOISE_PENDING, false)

    /** 清除哨兵（重新下载模型 / 用户在设置里重新开启时调用）。 */
    fun clearDenoiseCrashFlag(context: Context) {
        prefs(context).edit().remove(KEY_DENOISE_PENDING).apply()
    }

    private fun markDenoiseStarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_DENOISE_PENDING, true).commit()
    }

    private fun markDenoiseOk(context: Context) {
        prefs(context).edit().remove(KEY_DENOISE_PENDING).apply()
    }

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
        private val sampleRate: Int,
        /** 降噪首次成功跑出结果时回调一次（用于清崩溃哨兵）。 */
        private val onDenoiseOk: () -> Unit = {}
    ) : AutoCloseable {

        /** 降噪是否已经成功跑过一次（成功后才清哨兵，避免「建好了但跑就崩」也清掉）。 */
        private var denoiseProved = false

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
                // Kotlin 侧把 run() 声明成非空返回，但它在 JNI 边界上仍可能拿到空指针，
                // 所以显式按可空处理（(X?) 转型只为消掉「elvis 恒取左值」的编译警告，
                // 不改变运行时行为）。
                val out = d.run(samples, sampleRate) as com.k2fsa.sherpa.onnx.DenoisedAudio?
                    ?: return null
                // 3.1.1：第一次真的跑出结果，才认定「这台机器上降噪可用」并清掉哨兵。
                // 时机刻意放在 run() 成功返回之后而不是构造之后 ——
                // sherpa 有「模型建得起来、一 run 才 CHECK 失败」的情形。
                if (!denoiseProved) {
                    denoiseProved = true
                    try {
                        onDenoiseOk()
                    } catch (_: Throwable) {
                    }
                }
                // 降噪模型有自己的输出采样率。与识别器不符时**宁可不降噪**——
                // 把 48k 样本按 16k 喂给识别器，得到的是语速快三倍的乱码，
                // 比不降噪差得多，而且用户完全看不出原因。
                val outRate = out.sampleRate
                if (outRate > 0 && outRate != sampleRate) {
                    PlaybackLog.log("asr denoise rate mismatch out=$outRate want=$sampleRate -> skip")
                    return null
                }
                out.samples
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
        // 3.1.1：上次就是在这一步把进程搞死的 → 这次别再试，直接按「不降噪」跑。
        // 用户的开关保持原样（不改他的设置），只是这一轮不用它。
        val denoiseAllowed = wantDenoise && !denoiseCrashedLastTime(context)
        if (wantDenoise && !denoiseAllowed) {
            PlaybackLog.log("asr enhance: denoise skipped (crashed last session)")
        }
        val denoiser = if (denoiseAllowed) buildDenoiser(context) else null
        if (vad != null) PlaybackLog.log("asr enhance: VAD on")
        // 3.1.1：**先写哨兵、成功再清**。这一行必须在 native 调用之前落盘 ——
        // 降噪崩在原生代码里时进程当场消失，没有机会再执行任何 Java 代码，
        // 只有提前写好的标记能留到下次启动。
        if (denoiser != null) {
            markDenoiseStarted(context)
            PlaybackLog.log("asr enhance: denoise on")
        }
        val app = context.applicationContext
        return Session(vad, denoiser, sampleRate) { markDenoiseOk(app) }
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
            // ⚠️ 3.1.1 关键修正：第一个参数必须传 **null**。
            //
            // 构造函数的字节码是：`assetManager == null ? newFromFile(config)
            // : newFromAsset(assetManager, config)`。也就是说，**只要传了 AssetManager，
            // 配置里的字符串就会被当成 assets/ 目录下的文件名**，而不是文件系统路径。
            // 我们给的是 `/data/user/0/.../silero_vad.onnx` 这种绝对路径，
            // 按 asset 名去查必然找不到，native 侧 `CHECK` 失败 → 直接杀掉进程。
            //
            // 这种死法最坑的地方是**没有任何 Java 崩溃栈**：进程没了，CrashCatcher
            // 装的是 Java 层 handler，根本收不到信号，用户看到的就是「点一下就闪退」。
            // sherpa 原生库里就写着这句提示：
            //   "Please set assetManager to null when you load model files from the SD card"
            com.k2fsa.sherpa.onnx.Vad(null, cfg)
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
            // ⚠️ 第一个参数必须是 null，理由同 [buildVad]：传了 AssetManager 就会走
            // newFromAsset，把绝对路径当 asset 名查，native 直接杀进程 ——
            // 这正是粉丝「开启降噪后点歌词识别直接闪退」的根因。
            com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser(null, cfg)
        } catch (e: Throwable) {
            PlaybackLog.log("asr denoiser build THREW: ${e.message}")
            null
        }
    }
}
