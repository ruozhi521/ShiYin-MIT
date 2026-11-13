package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词导出（3.0.8）单测。
 *
 * 粉丝（B 站「月夜伴花落」）：「翻译出来的文本可以导出来吗，现在的好像
 * 导出来的只有日文 lrc 歌词文件」——以前译文只活在缓存里，从不落盘。
 */
class LyricExportRulesTest {

    private fun line(startMs: Int, text: String) = SubtitleLine(startMs, startMs + 2000, text)

    private val lines = listOf(
        line(0, "夜に駆ける"),
        line(3_500, "沈むように溶けてゆくように"),
        line(61_250, "二人だけの空が広がる夜")
    )
    private val trans = mapOf(
        0 to "奔向黑夜",
        1 to "像要沉沦般 像要融化般",
        2 to "只属于两人的天空在夜里铺开"
    )

    // ---------- 时间戳格式 ----------

    @Test
    fun `时间戳保留厘秒`() {
        assertEquals("[00:00.00]", LyricExportRules.fmtTime(0))
        assertEquals("[00:03.50]", LyricExportRules.fmtTime(3_500))
        assertEquals("[01:01.25]", LyricExportRules.fmtTime(61_250))
    }

    @Test
    fun `负数时间戳当作零处理不崩`() {
        assertEquals("[00:00.00]", LyricExportRules.fmtTime(-500))
    }

    // ---------- 三种模式 ----------

    @Test
    fun `双语模式原文与译文共用同一时间戳`() {
        val out = LyricExportRules.build(lines, trans, LyricExportRules.Mode.BILINGUAL)
        // 第一行：一行原文、一行译文，时间戳相同
        assertTrue(out.contains("[00:00.00]夜に駆ける"))
        assertTrue(out.contains("[00:00.00]奔向黑夜"))
        // 不能给译文加偏移（否则两行错位）
        assertFalse(out.contains("[00:00.") && out.contains("[00:00.20]"))
    }

    @Test
    fun `只导译文时不出现原文`() {
        val out = LyricExportRules.build(lines, trans, LyricExportRules.Mode.TRANSLATION_ONLY)
        assertFalse(out.contains("夜に駆ける"))
        assertTrue(out.contains("[00:00.00]奔向黑夜"))
    }

    @Test
    fun `只导原文时不出现译文`() {
        val out = LyricExportRules.build(lines, trans, LyricExportRules.Mode.ORIGINAL)
        assertTrue(out.contains("[00:00.00]夜に駆ける"))
        assertFalse(out.contains("奔向黑夜"))
    }

    @Test
    fun `没有时间信息的行被跳过`() {
        val withBad = listOf(line(-1, "识别失败的一行"), line(1_000, "正常一行"))
        val out = LyricExportRules.build(withBad, emptyMap(), LyricExportRules.Mode.ORIGINAL)
        assertFalse(out.contains("识别失败的一行"))
        assertTrue(out.contains("[00:01.00]正常一行"))
    }

    @Test
    fun `译文缺失时双语模式只写原文`() {
        val partial = mapOf(0 to "奔向黑夜")   // 只翻了一行
        val out = LyricExportRules.build(lines, partial, LyricExportRules.Mode.BILINGUAL)
        assertTrue(out.contains("[00:00.00]夜に駆ける"))
        assertTrue(out.contains("[00:00.00]奔向黑夜"))
        // 第二行没译文，不该出现空的时间戳行
        assertFalse(out.contains("[00:03.50]\n"))
    }

    // ---------- 有没有内容可导 ----------

    @Test
    fun `没有译文时只导译文视为无内容`() {
        assertFalse(LyricExportRules.hasContent(lines, emptyMap(), LyricExportRules.Mode.TRANSLATION_ONLY))
    }

    @Test
    fun `有原文时导原文视为有内容`() {
        assertTrue(LyricExportRules.hasContent(lines, emptyMap(), LyricExportRules.Mode.ORIGINAL))
    }

    @Test
    fun `有译文时双语视为有内容`() {
        assertTrue(LyricExportRules.hasContent(lines, trans, LyricExportRules.Mode.BILINGUAL))
    }

    // ---------- 文件名后缀 ----------

    @Test
    fun `不同模式文件名不同以免覆盖原歌词`() {
        assertEquals(".双语", LyricExportRules.fileNameSuffix(LyricExportRules.Mode.BILINGUAL))
        assertEquals(".译文", LyricExportRules.fileNameSuffix(LyricExportRules.Mode.TRANSLATION_ONLY))
        assertEquals("", LyricExportRules.fileNameSuffix(LyricExportRules.Mode.ORIGINAL))
    }
}
