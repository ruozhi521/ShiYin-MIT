package com.example.subtitleplayer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 歌词 AI 翻译：调用 OpenAI 兼容接口（DeepSeek / 通义 / GLM / Kimi 等均可）。
 * 分批逐行翻译，返回成功翻译的行号 -> 译文映射；模型缺失/拒绝的行不在结果中（行级降级）。
 */
object LyricTranslator {

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String
    )

    private const val BATCH_SIZE = 15
    private const val CONNECT_TIMEOUT = 20000
    private const val READ_TIMEOUT = 90000

    /** 翻译 [lines]（行号, 原文），返回成功行的 行号 -> 译文。 */
    fun translate(lines: List<Pair<Int, String>>, config: Config): Map<Int, String> {
        if (lines.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, String>()
        for (batch in lines.chunked(BATCH_SIZE)) {
            result.putAll(translateBatch(batch, config))
        }
        return result
    }

    private fun translateBatch(
        batch: List<Pair<Int, String>>,
        config: Config
    ): Map<Int, String> {
        return try {
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

            val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val respText = if (code in 200..299) {
                    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } else {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                }
                if (code !in 200..299) return emptyMap()
                parseResponse(respText, batch.map { it.first }.toSet())
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            emptyMap()
        }
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
