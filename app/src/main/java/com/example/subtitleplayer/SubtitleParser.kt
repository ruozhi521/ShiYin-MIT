package com.example.subtitleplayer

/**
 * 解析 .vtt 字幕与 .lrc 歌词，自动识别格式，返回按开始时间排序的行列表。
 */
object SubtitleParser {

    enum class Kind { VTT, LRC }

    private val vttTimeRe = Regex(
        """^(?:(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?)\s*-->\s*(?:(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?).*$"""
    )
    /**
     * LRC 时间标签（3.1.1 容错）。
     *
     * 3.1.1 之前只认 `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]` 三种写法。粉丝反馈的
     * 「识别 lrc 歌词时会把时间戳也显示出来，且无法读取 lrc 时间戳的时间」正是
     * 匹配失败的结果 —— 一行都认不出来 → [parseLrc] 返回空 → [parse] 退回
     * [parsePlainText]，时间戳被当成歌词正文，还按每行 4 秒的假时间排开，
     * 于是「时间戳显示出来了」和「时间全对不上」同时出现。
     *
     * 现在接受这些现实里存在的写法：
     * - 时:分:秒.小数（srt 导出的、部分日本音声附带的）：`[00:01:12.34]`
     * - 括号内有空格：`[ 00:12.34 ]`
     * - 全角括号：`［00:12.34］`（由 [normalizeBrackets] 先转半角）
     * - 小数用逗号 / 全角句号 / 全角逗号：`[00:12,34]`、`[00:12．34]`
     * - 分秒用全角冒号：`[00：12.34]`
     * - 毫秒 1~3 位：`[00:12.3]` / `[00:12.34]` / `[00:12.345]`
     *
     * ⚠️ 为什么拆成两条正则，而不是一条「小时可选」的：
     * `[00:12:34]` 在 LRC 里的传统含义是 **分:秒:厘秒**（12.34 秒），
     * 若允许把第一段当时，就会读成 12 分 34 秒 —— 差 60 倍，是**回归**。
     * 所以只有**四段数字**（`h:m:s.frac`）才按时:分:秒认；三段冒号式
     * 一律沿用旧语义（分:秒:厘秒），老文件行为不变。
     */
    private val lrcHmsTimeRe = Regex(
        """\[\s*(\d{1,3})\s*[:：]\s*(\d{1,2})\s*[:：]\s*(\d{1,2})\s*[.,，．·:：]\s*(\d{1,3})\s*]"""
    )

    /** 分:秒[.小数]（含旧式 `mm:ss:cc`），容错见 [lrcHmsTimeRe] 的注释。 */
    private val lrcTimeRe = Regex(
        """\[\s*(\d{1,3})\s*[:：]\s*(\d{1,2})(?:\s*[.,，．·:：]\s*(\d{1,3}))?\s*]"""
    )
    private val lrcOffsetRe = Regex("""\[\s*offset\s*[:：]\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val htmlTagRe = Regex("<[^>]*>")

    /**
     * 全角方括号 → 半角（3.1.1）。
     *
     * 只转换**包着时间戳**（只含数字与分隔符）的全角括号，不动歌词正文里的全角括号 ——
     * 「（笑）」这类括号是正文的一部分，全局替换会把歌词改坏。
     */
    private val fullWidthBracketRe = Regex("""［([\d\s:：.．,，·]*)］""")

    /**
     * 行内词级时间标签（增强型 LRC / A2 扩展）：`<00:12.34>`（3.1.1）。
     *
     * 这类标签夹在歌词中间表示「这个词在这一刻开始」。不处理它，整行文字里
     * 就会掺进 `<00:12.34>` 这种看得见的垃圾。当前不做逐字高亮，
     * 所以直接从正文里剥掉 —— 行首那个方括号时间戳仍然决定整行时间。
     */
    private val inlineTimeRe = Regex(
        """<(?:\d{1,3}\s*[:：]\s*)?\d{1,2}\s*[:：]\s*\d{1,2}(?:\s*[.,，．·:：]\s*\d{1,3})?>"""
    )

    /**
     * 兜底路径专用的「残留时间标签」清理（3.1.1）。
     *
     * 走到 [parsePlainText] 说明整份文件一个时间戳都没认出来（纯文本歌词）。
     * 但如果里面明明有 `[00:12.34]` 这种方括号时间戳，把它当成歌词正文显示
     * 就是粉丝看到的那一幕。这里只清「方括号 + 数字 + 冒号」的内容，
     * `[副歌]`、`[music]` 这类没有数字冒号的正文标签不受影响。
     */
    private val strayTimeTagRe = Regex("""\[\s*\d{1,3}\s*[:：][^\[\]]{0,20}]""")

    /**
     * 自动判断格式：包含 WEBVTT 头或 VTT 时间行 -> VTT，否则按 LRC 解析；
     * 若完全无时间戳（如纯文本 .txt 歌词），按行拆分并以固定间隔展示。
     */
    fun parse(text: String): List<SubtitleLine> {
        // 3.1.1：先把包着时间戳的全角方括号转成半角，再判格式。
        // 放在这里（而不是 parseLrc 内）是因为 [detectKind] 也要看同一份文本 ——
        // 只在解析器里转，格式判定仍会认不出全角写法，等于没修。
        val normalized = normalizeBrackets(text)
            .replace("\r\n", "\n").replace('\r', '\n')
        val kind = detectKind(normalized)
        val parsed = when (kind) {
            Kind.VTT -> parseVtt(normalized)
            Kind.LRC -> parseLrc(normalized)
        }
        if (parsed.isEmpty()) {
            return parsePlainText(normalized)
        }
        return parsed.sortedBy { it.startMs }
    }

    /** 把 `［00:12.34］` 这类**只含时间字符**的全角方括号转半角；正文括号（笑）不动。 */
    internal fun normalizeBrackets(text: String): String =
        if (text.indexOf('［') < 0) text
        else fullWidthBracketRe.replace(text) { m -> "[" + m.groupValues[1] + "]" }

    /**
     * 无时间戳的纯文本歌词（.txt）：每行一个条目，固定间隔展示，可滚动查看全文。
     *
     * 3.1.1：清掉残留的时间标签。走到这里说明整份文件一个时间戳都没认出来，
     * 但里面若还夹着 `[00:12.34]` 或 `<00:12.34>`，原样显示就是粉丝说的
     * 「时间戳也显示出来了」。清完变空的行直接丢弃（不占一个 4 秒的坑）。
     */
    private fun parsePlainText(text: String): List<SubtitleLine> {
        val out = mutableListOf<SubtitleLine>()
        var idx = 0
        for (line in text.lines()) {
            val t = inlineTimeRe.replace(strayTimeTagRe.replace(line, ""), "").trim()
            if (t.isEmpty()) continue
            val start = idx * 4000
            out.add(SubtitleLine(start, start + 4000, t))
            idx++
        }
        return out
    }

    private fun detectKind(text: String): Kind {
        if (text.trimStart().startsWith("WEBVTT")) return Kind.VTT
        // 扫描前若干行：跳过 srt 序号行（纯数字）、NOTE、空行，直到找到时间行
        var scanned = 0
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            scanned++
            if (scanned > 30) break
            if (t.startsWith("NOTE")) continue
            if (t.matches(Regex("""\d+"""))) continue // srt 序号
            if (vttTimeRe.containsMatchIn(t)) return Kind.VTT
            // 3.1.1：两条 LRC 正则都要问 —— 只问分:秒那条的话，
            // `[00:01:12.34]` 这种时:分:秒写法会让整份文件被判成「无时间戳」。
            if (lrcHmsTimeRe.containsMatchIn(t) || lrcTimeRe.containsMatchIn(t)) return Kind.LRC
        }
        return Kind.LRC
    }

    // ---------- VTT ----------

    private fun parseVtt(text: String): List<SubtitleLine> {
        val lines = text.lines()
        val out = mutableListOf<SubtitleLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            i++
            if (line.isEmpty()) continue
            if (line.startsWith("WEBVTT")) continue
            if (line.startsWith("NOTE")) {
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }
            val m = vttTimeRe.find(line)
            if (m == null) continue
            val start = toMs(
                m.groupValues[1], m.groupValues[2],
                m.groupValues[3], m.groupValues[4]
            )
            val end = toMs(
                m.groupValues[5], m.groupValues[6],
                m.groupValues[7], m.groupValues[8]
            )
            val buf = StringBuilder()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.isEmpty()) {
                    i++
                    break
                }
                if (vttTimeRe.containsMatchIn(t) || t.startsWith("NOTE")) break
                buf.append(t).append('\n')
                i++
            }
            var txt = buf.toString().trim()
            txt = htmlTagRe.replace(txt, "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            if (txt.isNotEmpty()) {
                out.add(SubtitleLine(start, end, txt))
            }
        }
        return out
    }

    // ---------- LRC ----------

    private fun parseLrc(text: String): List<SubtitleLine> =
        lrcEntries(text).map { SubtitleLine(it.startMs, Int.MAX_VALUE, it.text) }

    /**
     * 把 lrc 文本解析成「带文件位置」的条目（3.1.1）。
     *
     * [parseLrc] 与「改原文」都走这里 —— 编辑时按 [LrcEntry.lineIndex] 定位文件行，
     * 解析时按同一顺序展示。共用一份结果，才不会出现「界面上第 5 行」与
     * 「文件里第 5 行」不是同一条的情况（行号在多时间戳行展开后必然对不上）。
     *
     * 排序与 [parse] 一致（按开始时间升序，稳定排序），所以列表下标可直接当 pos 用。
     */
    internal fun lrcEntries(text: String): List<LrcEntry> {
        val out = mutableListOf<LrcEntry>()
        val offset = detectOffset(text)
        for ((lineIndex, raw) in text.lines().withIndex()) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            // 3.1.1：先剥掉行内词级标签 `<00:12.34>`，再找方括号时间戳。
            val body = inlineTimeRe.replace(s, "")
            val hits = timeTags(body)
            if (hits.isEmpty()) continue
            val txt = bodyText(body, hits)
            if (txt.isEmpty()) continue
            for (h in hits) {
                out.add(LrcEntry(lineIndex, h.ms, h.ms + offset, txt))
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * lrc 里的一行。
     * @param lineIndex 在**原始文本**中的行号（`\n` 分隔，从 0 起；[LyricEditRules] 据此改写）
     * @param tagMs 标签上的原始毫秒（不含 `[offset:]`）
     * @param startMs 叠加 `[offset:]` 后的毫秒（与 [SubtitleLine.startMs] 同口径）
     */
    internal class LrcEntry(
        val lineIndex: Int,
        val tagMs: Int,
        val startMs: Int,
        val text: String
    )

    /**
     * 一行里所有方括号时间标签（3.1.1 抽出，供 [LyricEditRules] 定位复用）。
     *
     * 两条正则各管一段：四段数字（时:分:秒.小数）归 [lrcHmsTimeRe]，其余归 [lrcTimeRe]。
     * **必须都跑** —— `[00:01:12.34]` 只有前者认得出，只跑后者那种文件仍会整段认不出来。
     *
     * @return 按出现位置升序；[LrcTag.ms] 是**文件内原始时间**（未叠加 [detectOffset]）
     */
    internal fun timeTags(body: String): List<LrcTag> {
        val hits = mutableListOf<LrcTag>()
        for (m in lrcHmsTimeRe.findAll(body)) {
            val ms = ((m.groupValues[1].toIntOrNull() ?: 0) * 60 +
                (m.groupValues[2].toIntOrNull() ?: 0)) * 60 * 1000 +
                (m.groupValues[3].toIntOrNull() ?: 0) * 1000 +
                fracToMs(m.groupValues[4])
            hits.add(LrcTag(m.range.first, m.range.last + 1, ms))
        }
        for (m in lrcTimeRe.findAll(body)) {
            val rs = m.range.first
            val re = m.range.last + 1
            // 已被时:分:秒那条吃掉的位置不再重复算（否则一个标签出两行）
            if (hits.any { rs < it.end && it.start < re }) continue
            val ms = ((m.groupValues[1].toIntOrNull() ?: 0) * 60 +
                (m.groupValues[2].toIntOrNull() ?: 0)) * 1000 +
                fracToMs(m.groupValues[3])
            hits.add(LrcTag(rs, re, ms))
        }
        hits.sortBy { it.start }
        return hits
    }

    /** 把时间标签逐个挖掉后的正文（保留标签之间的文字，兼容一行多个时间戳）。 */
    internal fun bodyText(body: String, tags: List<LrcTag>): String {
        if (tags.isEmpty()) return body.trim()
        val sb = StringBuilder()
        var cursor = 0
        for (t in tags) {
            if (t.start > cursor) sb.append(body, cursor, t.start)
            if (t.end > cursor) cursor = t.end
        }
        if (cursor < body.length) sb.append(body, cursor, body.length)
        return sb.toString().trim()
    }

    /** 文件里的 `[offset:±ms]`（第一个有效标签；没有则 0）。 */
    internal fun detectOffset(text: String): Int {
        for (line in text.lines()) {
            val om = lrcOffsetRe.find(line.trim())
            if (om != null) return om.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }

    /** 剥掉行内词级标签（`<00:12.34>`），编辑定位与解析共用同一套规则。 */
    internal fun stripInlineTags(line: String): String = inlineTimeRe.replace(line, "")

    /**
     * 一行 lrc 里的一个时间标签。
     * @param start/[end] 在**原始行文本**中的下标区间（左闭右开）
     * @param ms 解析出的毫秒（未叠加文件 offset）
     */
    internal class LrcTag(val start: Int, val end: Int, val ms: Int)

    /** 小数段 → 毫秒：`34` / `345` / `3` 都是「小数部分」，右补零到 3 位。 */
    private fun fracToMs(raw: String): Int =
        if (raw.isEmpty()) 0 else raw.padEnd(3, '0').take(3).toIntOrNull() ?: 0

    // ---------- helpers ----------

    private fun toMs(h: String, m: String, s: String, ms: String): Int {
        val hh = h.toIntOrNull() ?: 0
        val mm = m.toIntOrNull() ?: 0
        val ss = s.toIntOrNull() ?: 0
        val msec = if (ms.isEmpty()) 0 else ms.padEnd(3, '0').take(3).toInt()
        return (hh * 3600 + mm * 60 + ss) * 1000 + msec
    }
}
