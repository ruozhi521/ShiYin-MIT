package com.example.subtitleplayer

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 歌词 AI 翻译：调用 OpenAI 兼容接口（DeepSeek / 通义 / GLM / Kimi 等均可）。
 * 分批逐行翻译，返回成功翻译的行号 -> 译文映射；模型缺失/拒绝的行不在结果中（行级降级）。
 * 出错时返回可读的中文错误信息，便于用户在界面上直接看到原因。
 */
object LyricTranslator {

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String
    )

    data class TransResult(
        val translations: Map<Int, String>,
        val error: String?
    )

    private const val BATCH_SIZE = 15
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 60000

    /** 翻译 [lines]（行号, 原文），返回译文与首个错误信息（无错为 null）。 */
    fun translate(lines: List<Pair<Int, String>>, config: Config): TransResult {
        if (lines.isEmpty()) return TransResult(emptyMap(), null)
        val result = mutableMapOf<Int, String>()
        var firstError: String? = null
        for (batch in lines.chunked(BATCH_SIZE)) {
            val r = translateBatch(batch, config)
            if (r.error != null && firstError == null) firstError = r.error
            result.putAll(r.translations)
        }
        return TransResult(result, firstError)
    }

    private fun translateBatch(
        batch: List<Pair<Int, String>>,
        config: Config
    ): TransResult {
        try {
            val userContent = batch.joinToString("\n") { (idx, text) -> "$idx|$text" }
            val body = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.3)
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    "你是专业的歌词翻译员。请把用户提供的歌词逐行翻译成中文，保留行号对应。直接输出 JSON 数组，格式：" +
                                        "[{\"line\":行号,\"trans\":\"译文\"}]。不要输出任何其他内容，不要拒绝翻译，不要添加解释。"
                                )
                        )
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", "翻译以下歌词（每行格式为 行号|原文）：\n$userContent")
                        )
                )

            val conn = buildUrl(config.baseUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val respText = conn.inputStream.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    return TransResult(
                        parseResponse(respText, batch.map { it.first }.toSet()),
                        null
                    )
                }
                conn.errorStream?.use { it.readBytes() }
                return TransResult(emptyMap(), httpError(code))
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            return TransResult(emptyMap(), "连接或响应超时：请检查网络和接口地址")
        } catch (e: UnknownHostException) {
            return TransResult(emptyMap(), "无法解析服务器地址：${e.message}")
        } catch (e: ConnectException) {
            return TransResult(emptyMap(), "无法连接服务器：${e.message}")
        } catch (e: IOException) {
            return TransResult(emptyMap(), "网络请求失败：${e.message}")
        } catch (e: Exception) {
            return TransResult(emptyMap(), "请求异常：${e.message}")
        }
    }

    /** 兼容两种填法：`https://api.deepseek.com/v1` 或已带 `/chat/completions` 的完整地址。 */
    private fun buildUrl(base: String): URL {
        val b = base.trimEnd('/')
        return if (b.endsWith("/chat/completions")) URL(b) else URL("$b/chat/completions")
    }

    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "API Key 无效或无权限（HTTP $code）"
        404 -> "接口地址不正确（HTTP 404），请检查 Base URL"
        429 -> "请求过于频繁（HTTP 429），请稍后重试"
        in 500..599 -> "服务器错误（HTTP $code），请稍后重试"
        else -> "请求失败（HTTP $code）"
    }

    private fun parseResponse(respText: String, expected: Set<Int>): Map<Int, String> {
        return try {
            val json = extractJsonArray(respText) ?: return emptyMap()
            val arr = JSONArray(json)
            val result = mutableMapOf<Int, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val line = obj.optInt("line", -1)
                val trans = obj.optString("trans", "").trim()
                if (line in expected && trans.isNotEmpty()) {
                    result[line] = trans
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 从模型输出中提取 JSON 数组（容忍 ```json 包裹或前后杂文本）。 */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
