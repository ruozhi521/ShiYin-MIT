package com.example.subtitleplayer

/** 多服务商翻译统一入口。每个实现只负责自己的协议，不污染 Activity。 */
interface TranslationEngine {
    val id: String
    val displayName: String

    fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig
    ): EngineTranslationResult
}

enum class TranslationProvider(val id: String, val displayName: String) {
    OPENAI("openai", "AI（OpenAI 兼容）"),
    BAIDU("baidu", "百度翻译"),
    YOUDAO("youdao", "有道翻译")
}

/** 所有服务商共用的本地配置模型；未使用的字段为空。 */
data class TranslationConfig(
    val provider: TranslationProvider,
    val baseUrl: String = "",
    val apiKey: String = "",
    val secretKey: String = "",
    val appId: String = "",
    val model: String = ""
)

data class EngineTranslationResult(
    val translations: Map<Int, String>,
    val error: String?
)

object TranslationEngines {
    private val all: List<TranslationEngine> =
        listOf(OpenAiCompatibleEngine, BaiduTranslationEngine, YoudaoTranslationEngine)

    fun forProvider(provider: TranslationProvider): TranslationEngine =
        all.firstOrNull { it.id == provider.id } ?: OpenAiCompatibleEngine
}

/** 现有 LyricTranslator 的适配器，保持 DeepSeek/通义/GLM/Kimi 兼容。 */
object OpenAiCompatibleEngine : TranslationEngine {
    override val id = TranslationProvider.OPENAI.id
    override val displayName = TranslationProvider.OPENAI.displayName

    override fun translate(
        lines: List<Pair<Int, String>>,
        config: TranslationConfig
    ): EngineTranslationResult {
        val cfg = LyricTranslator.configFrom(config.baseUrl, config.apiKey, config.model)
            ?: return EngineTranslationResult(emptyMap(), "请先配置 AI 翻译 API Key")
        val result = LyricTranslator.translate(lines, cfg)
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
        config: TranslationConfig
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
        config: TranslationConfig
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
