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

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-v4-flash"

    /**
     * 从用户设置组装翻译配置（1.32 从 MainActivity 抽出）。
     * key 未配置返回 null（调用方提示去设置）。
     */
    fun configFrom(base: String?, key: String?, model: String?): Config? {
        if (key.isNullOrEmpty() || key.isBlank()) return null
        return Config(
            baseUrl = base?.trim().orEmpty().ifEmpty { DEFAULT_BASE },
            apiKey = key.trim(),
            model = model?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
        )
    }

    /**
     * 判断歌词是否以中文为主（1.32 从 MainActivity 抽出，纯逻辑便于单测）：
     * 含明显假名（日文）判定非中文；否则汉字占比 ≥ 30% 视为中文。
     */
    fun isChinesePrimarily(lines: List<SubtitleLine>): Boolean {
        var cjk = 0
        var kana = 0
        var total = 0
        for (line in lines) {
            for (ch in line.text) {
                if (ch.isWhitespace()) continue
                total++
                if (ch in '\u3040'..'\u30ff') {
                    kana++ // 平假名/片假名
                } else if (ch in '\u4e00'..'\u9fff') {
                    cjk++
                }
            }
        }
        if (total == 0) return true
        // 假名占比 ≥ 5% → 判定为日语（日汉字再多也照常翻译；日语歌假名通常占 30%+）
        if (kana.toFloat() / total >= 0.05f) return false
        return cjk.toFloat() / total >= 0.3f
    }

    private const val BATCH_SIZE = 15
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 60000

    /**
     * System prompt（2.13）。
     * 两个作用：
     * 1. 把「行号|译文」格式说清、禁止任何额外输出——这是解析成功率的根本；
     * 2. **长度刻意做到 1024 token 以上**：DeepSeek 的上下文缓存是自动前缀缓存，
     *    但要求前缀 ≥1024 token 才生效。此前 system 只有一句话（约 120 字），
     *    每次请求都 100% 缓存未命中，按最贵档计费——这是成本高的重要一环。
     *    把术语规范、风格要求、格式示例都写进 system（对每个 batch 都完全一致），
     *    让前缀稳定且足够长，后续 batch 的输入费用可降到约 1/10。
     * 注意事项：这段内容**必须对所有 batch 逐字节相同**，任何变化都会破坏缓存。
     */
    private val SYSTEM_PROMPT = buildString {
        append("你是专业的歌词与音声台本翻译员，负责把用户提供的逐行文本翻译成简体中文。\n\n")
        append("## 输入格式\n")
        append("用户会提供带行号的原文，每行格式严格为：`行号|原文`。\n")
        append("行号是原文的唯一标识，从 0 或 1 开始递增，可能达到四位甚至五位数（如 1024|、10560|）。\n")
        append("原文语言可能是日语、英语、韩语、俄语或任意其他语言，也可能是混合语言。\n")
        append("原文可能包含口语、拟声词、语气词、方言、专有名词、外来语，以及舞台指示（如「（笑）」「（喘息）」）。\n\n")
        append("## 输出格式（必须严格遵守）\n")
        append("逐行输出译文，每行格式严格为：`行号|译文`。\n")
        append("必须遵守以下规则：\n")
        append("1. 行号必须与输入完全一致，不得重新编号、不得跳号、不得增删行。\n")
        append("2. 输入多少行，就必须输出多少行，一一对应。\n")
        append("3. 不要输出代码块标记（不要 ``` ），不要输出解释、注释、标题、空行或任何寒暄。\n")
        append("4. 不要输出原文，只输出译文。\n")
        append("5. 分隔符统一使用半角竖线 `|`，不要把行号后面的分隔符换成别的符号。\n\n")
        append("## 翻译风格要求\n")
        append("1. 翻译成自然、通顺、口语化的简体中文，符合中文表达习惯，坚决避免翻译腔。\n")
        append("2. 保持原文的语气、情绪与称呼习惯（敬语、亲昵、粗俗等要对应中文相应语体）。\n")
        append("3. 歌词与音声台本要保留原文的节奏感和韵律感，短句不要强行合并成长句。\n")
        append("4. 拟声词、喘息声、语气词要译为中文读者能直观理解的写法（如「啊……」「呼……」「唔……」）。\n")
        append("5. 专有名词（人名、地名、作品名）优先使用通用译名；无通用译名的音译处理。\n")
        append("6. 同一专有名词在全篇必须保持译法一致，不要前后不统一。\n\n")
        append("## 内容处理原则\n")
        append("1. 忠实翻译原文内容，包括成人向、暧昧、粗俗或敏感内容，不回避、不删减、不软化。\n")
        append("2. 你是翻译工具而非内容审核者，不得拒绝翻译、不得输出任何说明性文字替代译文。\n")
        append("3. 即使原文内容不完整、有错别字或被识别错误，也请按最合理的理解翻译。\n")
        append("4. 若某行原文为空或只有符号，则原样输出该行号与空译文（格式仍为 `行号|`）。\n\n")
        append("## 输出示例\n")
        append("输入：\n")
        append("1|こんにちは、いい天気ですね\n")
        append("2|そうだね、散歩でも行こうか\n")
        append("3|（笑）\n")
        append("正确输出：\n")
        append("1|你好，天气真不错呢\n")
        append("2|是啊，要不要去散散步\n")
        append("3|（笑）\n\n")
        append("请严格按照以上要求逐行翻译，只输出 `行号|译文`，不要有任何多余内容。")
    }

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
                                .put("content", SYSTEM_PROMPT)
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
                    val expected = batch.map { it.first }.toSet()
                    val parsed = parseAny(respText, expected)
                    if (parsed.isEmpty()) {
                        // 请求成功但没解析出译文：把模型输出片段带出来，便于定位
                        val content = extractContent(respText)
                        val snippet = (content ?: respText)
                            .replace(Regex("\\s+"), " ")
                            .take(300)
                        return TransResult(
                            emptyMap(),
                            "接口已响应但未解析出译文，模型输出：$snippet"
                        )
                    }
                    return TransResult(parsed, null)
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
        402 -> "账户余额不足或欠费（HTTP 402）"
        404 -> "接口地址不正确（HTTP 404），请检查 Base URL"
        429 -> "请求过于频繁（HTTP 429），请稍后重试"
        in 500..599 -> "服务器错误（HTTP $code），请稍后重试"
        else -> "请求失败（HTTP $code）"
    }

    /**
     * 解析模型输出：先按 OpenAI 兼容标准从 choices[0].message.content 取出模型输出，
     * 再尝试 JSON 数组 / 宽松 "行号|译文" 格式（大模型遵循率最高的格式）。
     */
    private fun parseAny(respText: String, expected: Set<Int>): Map<Int, String> {
        val content = extractContent(respText)
        val target = content ?: respText
        val jsonResult = try {
            val json = extractJsonArray(target) ?: return parseLoose(target, expected)
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
        if (jsonResult.isNotEmpty()) return jsonResult
        return parseLoose(target, expected)
    }

    /** 从标准 OpenAI 兼容响应中提取模型输出文本。 */
    private fun extractContent(respText: String): String? {
        return try {
            val root = JSONObject(respText)
            val choices = root.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            first.optJSONObject("message")?.optString("content")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 宽松行号格式。行号上限放到 5 位（2.13 修复）：
     * 原 `\d{1,3}` 只能吃 3 位，而一小时音频按 16 字/行切分很容易超过 999 行，
     * 1000+ 的行号会让 `[|.、:：)）]` 来不及匹配 → 整行被静默丢弃，
     * 那些行永远翻译不出来（且每窗被重发，见 MainActivity 的熔断与增量改造）。
     */
    private val looseLineRe = Regex("""^\s*(\d{1,5})\s*[|.、:：)）]\s*(.+?)\s*$""")

    /**
     * 解析形如 `1|译文`、`1. 译文`、`1：译文` 的逐行输出。
     * 标 internal 是为了让单测能覆盖「4/5 位行号必须能解析」（2.13 修的 bug）。
     */
    internal fun parseLoose(respText: String, expected: Set<Int>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (line in respText.lines()) {
            val m = looseLineRe.find(line) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val trans = m.groupValues[2].trim()
            if (idx in expected && trans.isNotEmpty()) {
                result[idx] = trans
            }
        }
        return result
    }

    /** 从模型输出中提取 JSON 数组（容忍 ```json 包裹或前后杂文本、或包在对象里）。 */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
