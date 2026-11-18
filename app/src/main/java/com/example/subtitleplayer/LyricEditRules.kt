package com.example.subtitleplayer

/**
 * 手动改歌词原文（3.1.1）。
 *
 * ## 为什么需要
 *
 * 识别出来的歌词总会有错字、断句错、把「笑」听成「わ」这类问题。
 * 3.1.1 之前用户唯一的选择是整首重新识别 —— 而重新识别**不保证更准**，
 * 可能这一行修好了、旁边两行又坏了。第一次就把能用的部分固定下来，
 * 才是「越用越好」的做法。
 *
 * ## 为什么按行号定位，而不是按时间戳
 *
 * 时间戳看起来更「语义化」，但在 lrc 里并不唯一也不稳定：
 * 1. 同一行可以挂多个时间戳（`[00:12.34][00:15.00]副歌`），解析后会展开成多条，
 *    按时间戳找得到、却不知道该改文件里的哪一行；
 * 2. 文件可能带 `[offset:±ms]`，界面上显示的时间已经平移过，反推容易差出 offset；
 * 3. 坏文件里同一时间戳可能重复出现。
 *
 * 行号没有这些问题 —— 只要**解析与编辑用同一份解析结果**（见 [SubtitleParser.lrcEntries]），
 * 界面上第 N 条对应的就是文件里第 [Entry.lineIndex] 行。
 *
 * 纯字符串逻辑，不依赖 Android，可直接单测。
 */
internal object LyricEditRules {

    /** 替换结果。 */
    sealed class Result {
        /** 成功；[text] 为改写后的完整文件文本。 */
        data class Ok(val text: String) : Result()

        /** [lineIndex] 越界，或该行没有时间标签（不是歌词行）。 */
        object NotALyricLine : Result()
    }

    /**
     * 把文件第 [lineIndex] 行的正文替换为 [newText]，所有时间标签原样保留。
     *
     * @param lineIndex 0 起的行号，与 [SubtitleParser.lrcEntries] 给出的 `lineIndex` 同口径
     *   （按 `\n` 切分；`\r\n` 的 `\r` 不影响计数）
     * @param newText 新正文；其中的换行会被压成空格（lrc 一行一条，带换行会毁掉时间轴）
     */
    fun replaceLineAt(lrc: String, lineIndex: Int, newText: String): Result {
        val safeText = newText.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()
        if (safeText.isEmpty()) return Result.NotALyricLine
        val lines = lrc.split('\n')
        if (lineIndex !in lines.indices) return Result.NotALyricLine
        val raw = lines[lineIndex]
        val cr = if (raw.endsWith('\r')) "\r" else ""
        val line = if (cr.isEmpty()) raw else raw.dropLast(1)
        // 全角括号（`［00:12.34］`）要先归一化才认得出时间标签。
        // 归一化是**等长替换**（`［`/`］` → `[`/`]` 各一个字符），所以下标可直接
        // 作用在原始行上 —— 不必把归一化后的文本写回文件，用户的手写风格不受影响。
        val norm = SubtitleParser.normalizeBrackets(line)
        val tags = SubtitleParser.timeTags(SubtitleParser.stripInlineTags(norm))
        if (tags.isEmpty()) return Result.NotALyricLine
        // 保留标签原文而不是重新格式化：`[00:12.340]`、全角冒号 `[00：12.34]` 都维持原样
        val sb = StringBuilder(line.length)
        for (t in tags) sb.append(line, t.start, t.end)
        sb.append(safeText)
        val replaced = sb.toString() + cr
        return Result.Ok(buildString(lrc.length + safeText.length) {
            for ((i, l) in lines.withIndex()) {
                if (i > 0) append('\n')
                append(if (i == lineIndex) replaced else l)
            }
        })
    }

    /**
     * 在解析结果里找「界面上那一条」对应的文件行。
     *
     * 同时比对开始时间**与正文**：只看时间的话，同一时间戳的重复行会认错；
     * 只比正文的话，副歌那种同一句出现多次的会认错。两者都相等才算命中。
     *
     * @return 命中条目的下标；找不到返回 -1
     */
    fun findEntry(
        entries: List<SubtitleParser.LrcEntry>,
        startMs: Int,
        text: String
    ): Int {
        val t = text.trim()
        return entries.indexOfFirst { it.startMs == startMs && it.text.trim() == t }
    }
}
