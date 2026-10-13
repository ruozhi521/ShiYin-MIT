package com.example.subtitleplayer

/** 多服务商翻译统一入口。每个实现只负责自己的协议，不污染 Activity。 */
interface TranslationEngine {
    val id: String
    val displayName: String

    /**
     * 翻译 [lines]。
     *
     * @param onProgress 已完成的**行数**进度回调（`done, total`），供 UI 显示。
     *   云端引擎一次请求返回整批，通常只在批次间回调；本地离线引擎逐行翻译，
     *   会**逐行**回调 —— 它最慢（真机上可能数十分钟），没有进度反馈会被当成卡死。
     */
    fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): EngineTranslationResult
}

enum class TranslationProvider(val id: String, val displayName: String) {
    OPENAI("openai", "AI（OpenAI 兼容）"),
    BAIDU("baidu", "百度翻译"),
    YOUDAO("youdao", "有道翻译"),

    /**
     * 本地离线翻译（3.0）—— **完全不出网**，模型跑在设备上。
     * 首次使用需先在设置页下载模型（约 216 MB）；与云端方案的区别是隐私与离线可用性。
     */
    LOCAL("local", "本地离线（无需联网）")
}

/** 所有服务商共用的本地配置模型；未使用的字段为空。 */
data class TranslationConfig(
    val provider: TranslationProvider,
    val baseUrl: String = "",
    val apiKey: String = "",
    val secretKey: String = "",
    val appId: String = "",
    val model: String = "",
    /** 本地离线翻译的模型目录（[TranslationProvider.LOCAL] 使用），其余 provider 留空。 */
    val mtModelDir: String = "",
    /** 本地离线翻译的 beam 宽度：1=贪心最快，>1 质量通常更好但耗时约 ×N。 */
    val mtBeams: Int = 1,
    /**
     * 本地离线翻译的取消钩子，返回 true 则尽快停止。
     *
     * 为什么需要：本地引擎逐行推理，一小时音频的歌词可能上千行、耗时很久，
     * 用户中途想停（切歌/退出）必须能停 —— 云端引擎每次请求很短，没这个问题。
     * 默认不取消，故对现有调用方无影响。
     */
    val mtCancel: () -> Boolean = { false }
)

data class EngineTranslationResult(
    val translations: Map<Int, String>,
    val error: String?
)

object TranslationEngines {
    private val all: List<TranslationEngine> =
        listOf(
            OpenAiCompatibleEngine, BaiduTranslationEngine, YoudaoTranslationEngine,
            LocalMtEngine
        )

    fun forProvider(provider: TranslationProvider): TranslationEngine =
        all.firstOrNull { it.id == provider.id } ?: OpenAiCompatibleEngine

    /** 所有可选服务商（设置页列表用），顺序即展示顺序。 */
    fun allProviders(): List<TranslationEngine> = all
}

/** 现有 LyricTranslator 的适配器，保持 DeepSeek/通义/GLM/Kimi 兼容。 */
object OpenAiCompatibleEngine : TranslationEngine {
    override val id = TranslationProvider.OPENAI.id
    override val displayName = TranslationProvider.OPENAI.displayName

    override fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit
    ): EngineTranslationResult {
        val cfg = LyricTranslator.configFrom(config.baseUrl, config.apiKey, config.model)
            ?: return EngineTranslationResult(emptyMap(), "请先配置 AI 翻译 API Key")
        val result = LyricTranslator.translate(lines, cfg)
        onProgress(lines.size, lines.size)
        return EngineTranslationResult(result.translations, result.error)
    }
}

/** 百度通用文本翻译 API，使用标准库完成 MD5 签名，不新增网络依赖。 */
object BaiduTranslationEngine : TranslationEngine {
    override val id = TranslationProvider.BAIDU.id
    override val displayName = TranslationProvider.BAIDU.displayName

    private const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"

    override fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit
    ): EngineTranslationResult {
        if (lines.isEmpty()) return EngineTranslationResult(emptyMap(), null)
        if (config.appId.isBlank() || config.secretKey.isBlank()) {
            return EngineTranslationResult(emptyMap(), "请配置百度翻译 APP ID 和密钥")
        }
        val query = lines.joinToString("\n") { it.second }
        val salt = System.currentTimeMillis().toString()
        val sign = md5(config.appId + query + salt + config.secretKey)
        val params = linkedMapOf(
            "q" to query,
            "from" to "auto",
            "to" to "zh",
            "appid" to config.appId,
            "salt" to salt,
            "sign" to sign
        )
        return try {
            val root = TranslationHttp.postForm(ENDPOINT, params)
            val errorCode = root.optString("error_code")
            if (errorCode.isNotBlank()) {
                EngineTranslationResult(emptyMap(), "百度翻译失败：${root.optString("error_msg", errorCode)}")
            } else {
                val data = root.optJSONArray("trans_result")
                if (data == null || data.length() != lines.size) {
                    return EngineTranslationResult(
                        emptyMap(),
                        "百度翻译返回 ${data?.length() ?: 0} 段，预期 ${lines.size} 段；为避免错行已取消本批写入"
                    )
                }
                val result = linkedMapOf<Int, String>()
                for (i in 0 until lines.size) {
                    val translated = data.optJSONObject(i)?.optString("dst").orEmpty().trim()
                    if (translated.isNotEmpty()) result[lines[i].first] = translated
                }
                onProgress(lines.size, lines.size)
                EngineTranslationResult(result, if (result.isEmpty()) "百度翻译未返回译文" else null)
            }
        } catch (e: Exception) {
            EngineTranslationResult(emptyMap(), "百度翻译请求失败：${e.message}")
        }
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 有道智云文本翻译 API，按官方 SHA-256 签名规则请求。 */
object YoudaoTranslationEngine : TranslationEngine {
    override val id = TranslationProvider.YOUDAO.id
    override val displayName = TranslationProvider.YOUDAO.displayName

    private const val ENDPOINT = "https://openapi.youdao.com/api"

    override fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit
    ): EngineTranslationResult {
        if (lines.isEmpty()) return EngineTranslationResult(emptyMap(), null)
        if (config.appId.isBlank() || config.secretKey.isBlank()) {
            return EngineTranslationResult(emptyMap(), "请配置有道应用 ID 和密钥")
        }
        val query = lines.joinToString("\n") { it.second }
        val salt = java.util.UUID.randomUUID().toString()
        val curtime = (System.currentTimeMillis() / 1000L).toString()
        val signStr = config.appId + truncate(query) + salt + curtime + config.secretKey
        val params = linkedMapOf(
            "q" to query,
            "from" to "auto",
            "to" to "zh-CHS",
            "appKey" to config.appId,
            "salt" to salt,
            "sign" to sha256(signStr),
            "signType" to "v3",
            "curtime" to curtime
        )
        return try {
            val root = TranslationHttp.postForm(ENDPOINT, params)
            val code = root.optString("errorCode")
            if (code.isNotBlank() && code != "0") {
                EngineTranslationResult(emptyMap(), "有道翻译失败（错误码 $code）")
            } else {
                val text = root.optString("translation").trim()
                val translated = text.split("\n")
                if (translated.size != lines.size) {
                    return EngineTranslationResult(
                        emptyMap(),
                        "有道翻译返回 ${translated.size} 行，预期 ${lines.size} 行；为避免错行已取消本批写入"
                    )
                }
                val result = linkedMapOf<Int, String>()
                for (i in lines.indices) {
                    if (translated[i].isNotBlank()) result[lines[i].first] = translated[i].trim()
                }
                onProgress(lines.size, lines.size)
                EngineTranslationResult(result, if (result.isEmpty()) "有道翻译未返回译文" else null)
            }
        } catch (e: Exception) {
            EngineTranslationResult(emptyMap(), "有道翻译请求失败：${e.message}")
        }
    }

    private fun truncate(text: String): String =
        if (text.length <= 20) text else text.take(10) + text.length + text.takeLast(10)

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 本地离线翻译引擎（3.0）—— **完全不出网**，模型在设备上跑。
 *
 * 与云端引擎的三个关键差异：
 * 1. **逐行推理**：云端一次请求翻译一批；本地每行都要跑一遍完整 decoder，
 *    故**逐行回调进度**（真机上总耗时可能很久，没有进度会被当成卡死）。
 * 2. **可取消**：长音频的歌词上千行，中途切歌/退出必须能停（[TranslationConfig.mtCancel]）。
 * 3. **可能失败在"模型没下载"**：这是唯一需要前置资源的引擎，
 *    未就绪时给出可操作的提示（去设置页下载），而不是泛泛的"请求失败"。
 *
 * 线程：本方法**阻塞**且耗时很长，调用方必须在后台线程执行（现有三处调用点都已如此）。
 */
object LocalMtEngine : TranslationEngine {
    override val id = TranslationProvider.LOCAL.id
    override val displayName = TranslationProvider.LOCAL.displayName

    override fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit
    ): EngineTranslationResult {
        if (lines.isEmpty()) return EngineTranslationResult(emptyMap(), null)

        val dirText = config.mtModelDir
        if (dirText.isBlank()) {
            return EngineTranslationResult(emptyMap(), "本地离线翻译未配置模型目录")
        }
        val dir = java.io.File(dirText)
        if (!MarianMtEngine.isModelReady(dir)) {
            // 未下载时给明确指引（设置页有「下载模型」入口），别让用户以为是网络问题
            return EngineTranslationResult(emptyMap(), "本地模型尚未下载，请到「设置 → 翻译设置」下载")
        }

        if (!MarianMtEngine.load(dir)) {
            return EngineTranslationResult(emptyMap(), "本地模型加载失败（文件可能损坏，可删除后重新下载）")
        }

        val out = LinkedHashMap<Int, String>()
        var firstError: String? = null
        val beams = config.mtBeams.coerceAtLeast(1)
        val total = lines.size
        var done = 0

        for ((index, text) in lines) {
            if (config.mtCancel()) {
                // 已翻好的部分照常返回：调用方会把它们写进缓存，不必白跑
                PlaybackLog.log("mt local cancelled at $done/$total")
                if (out.isEmpty()) firstError = "已取消"
                break
            }
            val raw = text.trim()
            var zh = ""
            if (raw.isNotEmpty()) {
                zh = try {
                    MarianMtEngine.translate(raw, beams)
                } catch (t: Throwable) {
                    if (firstError == null) firstError = t.javaClass.simpleName + ": " + t.message
                    ""
                }
            }
            if (zh.isNotEmpty()) out[index] = zh
            done++
            onProgress(done, total)
        }

        PlaybackLog.log("mt local done: ${out.size}/$total err=$firstError")
        return EngineTranslationResult(out, firstError)
    }
}

/** 统一的表单请求工具，供传统翻译服务商复用。 */
private object TranslationHttp {
    fun postForm(url: String, params: Map<String, String>): org.json.JSONObject {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        conn.connectTimeout = 10000
        conn.readTimeout = 60000
        conn.doOutput = true
        try {
            val body = params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return org.json.JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
