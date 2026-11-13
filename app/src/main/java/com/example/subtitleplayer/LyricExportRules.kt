package com.example.subtitleplayer

import java.util.Locale

/**
 * 歌词导出成 lrc 文本（3.0.8）。
 *
 * 粉丝（B 站「月夜伴花落」）：「翻译出来的文本可以导出来吗，现在的好像导出来的只有日文 lrc 歌词文件」。
 *
 * 以前只有识别歌词会落盘（[SpeechRecManager] 写 `歌名.lrc`），译文只活在
 * `LyricTranslationCache` 里（provider → uri → {行号: 译文}），从没出过 App。
 * 这里把「原文 + 译文」拼成 lrc，交给同一套落盘通道写出去。
 *
 * 三种模式对应粉丝可能想要的三种东西：
 * - [Mode.BILINGUAL]：同一时间戳下原文一行、译文一行（多数播放器会依次显示两行）
 * - [Mode.TRANSLATION_ONLY]：只要译文（他自己看得懂的那种，例如中文）
 * - [Mode.ORIGINAL]：只要原文（等于原有行为，留作对照）
 *
 * ⚠️ 时间戳只打一次、两行共享，而不是给译文加几十毫秒：
 * 加偏移会让译文比原文晚出现，滚动歌词时两行错位；共享时间戳则由播放器一起显示。
 */
object LyricExportRules {

    enum class Mode { BILINGUAL, TRANSLATION_ONLY, ORIGINAL }

    /** lrc 时间戳 `[mm:ss.cc]`（厘秒，两位小数）。 */
    fun fmtTime(ms: Int): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        val mm = total / 60
        val ss = total % 60
        val cs = (ms.coerceAtLeast(0) % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", mm, ss, cs)
    }

    /**
     * 生成 lrc 正文。
     *
     * @param lines 歌词行（startMs 用作时间戳；负数表示「这行没有时间信息」，
     *   识别失败时会这样，直接跳过 —— 写进 lrc 会变成 00:00 堆在一起）
     * @param trans 行号 → 译文
     * @param mode 导出模式
     */
    fun build(
        lines: List<SubtitleLine>,
        trans: Map<Int, String>,
        mode: Mode
    ): String {
        val sb = StringBuilder()
        // 头部标记：告诉别人（和以后的自己）这文件是怎么来的
        when (mode) {
            Mode.BILINGUAL -> sb.append("[re:拾音 双语歌词]\n")
            Mode.TRANSLATION_ONLY -> sb.append("[re:拾音 译文]\n")
            Mode.ORIGINAL -> Unit
        }
        lines.forEachIndexed { idx, line ->
            if (line.startMs < 0) return@forEachIndexed
            val time = fmtTime(line.startMs)
            val original = line.text.trim()
            val translation = trans[idx]?.trim().orEmpty()
            when (mode) {
                Mode.ORIGINAL -> if (original.isNotEmpty()) {
                    sb.append(time).append(original).append('\n')
                }
                Mode.TRANSLATION_ONLY -> if (translation.isNotEmpty()) {
                    sb.append(time).append(translation).append('\n')
                }
                Mode.BILINGUAL -> {
                    if (original.isNotEmpty()) sb.append(time).append(original).append('\n')
                    if (translation.isNotEmpty()) sb.append(time).append(translation).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /**
     * 该模式下导出的内容是否有实际文本（用来决定「没什么可导」的提示）。
     *
     * 译文模式下一个字都没有时，导出一个空文件是添乱。
     */
    fun hasContent(
        lines: List<SubtitleLine>,
        trans: Map<Int, String>,
        mode: Mode
    ): Boolean = when (mode) {
        Mode.ORIGINAL -> lines.any { it.startMs >= 0 && it.text.isNotBlank() }
        Mode.TRANSLATION_ONLY -> trans.values.any { it.isNotBlank() }
        // 双语：只要有任何一行（原文或译文）写得出来就算有内容
        Mode.BILINGUAL -> lines.withIndex().any { (idx, line) ->
            line.startMs >= 0 &&
                (line.text.isNotBlank() || !trans[idx].isNullOrBlank())
        }
    }

    /** 导出文件名后缀（`歌名双语.lrc` / `歌名译文.lrc` / `歌名.lrc`）。 */
    fun fileNameSuffix(mode: Mode): String = when (mode) {
        Mode.BILINGUAL -> ".双语"
        Mode.TRANSLATION_ONLY -> ".译文"
        Mode.ORIGINAL -> ""
    }
}
