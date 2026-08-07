package com.example.subtitleplayer

/**
 * 解析 .vtt 字幕与 .lrc 歌词，自动识别格式，返回按开始时间排序的行列表。
 */
object SubtitleParser {

    enum class Kind { VTT, LRC }

    private val vttTimeRe = Regex(
        """^(?:(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?)\s*-->\s*(?:(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?).*$"""
    )
    private val lrcTimeRe = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val lrcOffsetRe = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val htmlTagRe = Regex("<[^>]*>")

    /**
     * 自动判断格式：包含 WEBVTT 头或 VTT 时间行 -> VTT，否则按 LRC 解析；
     * 若完全无时间戳（如纯文本 .txt 歌词），按行拆分并以固定间隔展示。
     */
    fun parse(text: String): List<SubtitleLine> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
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

    /** 无时间戳的纯文本歌词（.txt）：每行一个条目，固定间隔展示，可滚动查看全文。 */
    private fun parsePlainText(text: String): List<SubtitleLine> {
        val out = mutableListOf<SubtitleLine>()
        var idx = 0
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val start = idx * 4000
            out.add(SubtitleLine(start, start + 4000, t))
            idx++
        }
        return out
    }

    private fun detectKind(text: String): Kind {
        if (text.trimStart().startsWith("WEBVTT")) return Kind.VTT
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("NOTE")) {
                if (vttTimeRe.containsMatchIn(t)) return Kind.VTT
                break
            }
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

    private fun parseLrc(text: String): List<SubtitleLine> {
        val out = mutableListOf<SubtitleLine>()
        var offset = 0
        for (line in text.lines()) {
            val om = lrcOffsetRe.find(line.trim())
            if (om != null) {
                offset = om.groupValues[1].toIntOrNull() ?: 0
                break
            }
        }
        for (line in text.lines()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val matches = lrcTimeRe.findAll(s).toList()
            if (matches.isEmpty()) continue
            val times = mutableListOf<Int>()
            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val msRaw = m.groupValues[3]
                val ms = if (msRaw.isEmpty()) 0 else msRaw.padEnd(3, '0').take(3).toInt()
                times.add((min * 60 + sec) * 1000 + ms)
            }
            val txt = lrcTimeRe.replace(s, "").trim()
            if (txt.isEmpty()) continue
            for (t in times) {
                out.add(SubtitleLine(t + offset, Int.MAX_VALUE, txt))
            }
        }
        return out
    }

    // ---------- helpers ----------

    private fun toMs(h: String, m: String, s: String, ms: String): Int {
        val hh = h.toIntOrNull() ?: 0
        val mm = m.toIntOrNull() ?: 0
        val ss = s.toIntOrNull() ?: 0
        val msec = if (ms.isEmpty()) 0 else ms.padEnd(3, '0').take(3).toInt()
        return (hh * 3600 + mm * 60 + ss) * 1000 + msec
    }
}
