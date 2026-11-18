package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SubtitleParser 纯字符串解析测试（不依赖 Android 框架）。 */
class SubtitleParserTest {

    @Test
    fun `vtt 带 WEBVTT 头按时间戳解析`() {
        val text = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            第一句

            00:00:04.000 --> 00:00:06.000
            第二句
        """.trimIndent()
        val lines = SubtitleParser.parse(text)
        assertEquals(2, lines.size)
        assertEquals(1000, lines[0].startMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(4000, lines[1].startMs)
    }

    @Test
    fun `lrc 标准时间戳解析并按时间排序`() {
        val text = """
            [00:03.00]第三句
            [00:01.50]第一句
            [00:02.00]第二句
        """.trimIndent()
        val lines = SubtitleParser.parse(text)
        assertEquals(3, lines.size)
        assertEquals("第一句", lines[0].text)
        assertEquals(1500, lines[0].startMs)
        assertEquals("第三句", lines[2].text)
        assertEquals(3000, lines[2].startMs)
    }

    @Test
    fun `纯文本无时间戳按行拆分`() {
        val text = "第一行\n第二行\n\n第三行"
        val lines = SubtitleParser.parse(text)
        assertEquals(3, lines.size)
        assertTrue(lines[0].startMs >= 0)
        assertEquals("第一行", lines[0].text)
    }

    @Test
    fun `lrc 毫秒小数时间戳`() {
        val text = "[01:23.456]测试"
        val lines = SubtitleParser.parse(text)
        assertEquals(1, lines.size)
        assertEquals(83456, lines[0].startMs)
    }

    @Test
    fun `vtt 去掉 html 标签`() {
        val text = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            <c>带标签</c> 的歌词
        """.trimIndent()
        val lines = SubtitleParser.parse(text)
        assertEquals(1, lines.size)
        assertEquals("带标签 的歌词", lines[0].text)
    }

    // ---------- 3.1.1：LRC 时间戳容错（B站反馈「时间戳被显示出来 + 时间全对不上」） ----------

    @Test
    fun `时分秒四点式时间戳按 时 分 秒 解析`() {
        // srt 转出来的 lrc 常见写法；旧正则认不出 → 退回纯文本 → 时间戳变成歌词
        val lines = SubtitleParser.parse("[00:01:12.34]第一句")
        assertEquals(1, lines.size)
        assertEquals(72340, lines[0].startMs)
        assertEquals("第一句", lines[0].text)
    }

    @Test
    fun `三点式冒号仍是旧语义 分冒号秒冒号厘秒`() {
        // 回归保护：若把 [00:12:34] 当成 时:分:秒，会变成 12 分 34 秒（差 60 倍）
        val lines = SubtitleParser.parse("[00:12:34]第一句")
        assertEquals(1, lines.size)
        assertEquals(12340, lines[0].startMs)
    }

    @Test
    fun `括号内空格与各种小数分隔符都能解析`() {
        val lines = SubtitleParser.parse(
            """
            [ 00:01.50 ]括号空格
            [00:02,50]逗号小数
            [00:03．50]全角句号
            [00:04.5]一位小数
            """.trimIndent()
        )
        assertEquals(4, lines.size)
        assertEquals(1500, lines[0].startMs)
        assertEquals(2500, lines[1].startMs)
        assertEquals(3500, lines[2].startMs)
        assertEquals(4500, lines[3].startMs)
    }

    @Test
    fun `全角方括号与全角冒号的时间戳`() {
        val lines = SubtitleParser.parse("［00:12.34］全角括号")
        assertEquals(1, lines.size)
        assertEquals(12340, lines[0].startMs)
        assertEquals("全角括号", lines[0].text)

        val lines2 = SubtitleParser.parse("[00：15.00]全角冒号")
        assertEquals(1, lines2.size)
        assertEquals(15000, lines2[0].startMs)
        assertEquals("全角冒号", lines2[0].text)
    }

    @Test
    fun `全角括号只转时间戳 不伤正文括号`() {
        val lines = SubtitleParser.parse("[00:01.00]（笑）［笑］")
        assertEquals(1, lines.size)
        assertEquals("（笑）［笑］", lines[0].text)
    }

    @Test
    fun `行内词级时间标签从正文剥离`() {
        val lines = SubtitleParser.parse("[00:12.34]正文<00:13.00>带标签<00:14.00>继续")
        assertEquals(1, lines.size)
        assertEquals(12340, lines[0].startMs)
        assertEquals("正文带标签继续", lines[0].text)
    }

    @Test
    fun `同一行多个时间戳生成多行同文本`() {
        val lines = SubtitleParser.parse("[00:12.34][00:15.00]副歌")
        assertEquals(2, lines.size)
        assertEquals(12340, lines[0].startMs)
        assertEquals(15000, lines[1].startMs)
        assertTrue(lines.all { it.text == "副歌" })
    }

    @Test
    fun `兜底纯文本路径不会把时间戳当歌词显示`() {
        // 整份文件都不带合法时间戳，但夹着方括号时间戳 —— 不能原样吐出来
        val lines = SubtitleParser.parse("［无时间的歌词］\n[00:12.34]看起来像时间戳")
        assertTrue("不应出现原始时间戳: ${lines.map { it.text }}", lines.none { it.text.contains("[00:12.34]") })
        assertTrue(lines.any { it.text.contains("看起来像时间戳") })
    }

    @Test
    fun `正文里的普通方括号标签保留`() {
        val lines = SubtitleParser.parse("[00:01.00][副歌]\n[00:05.00][music]")
        assertEquals(2, lines.size)
        assertEquals("[副歌]", lines[0].text)
        assertEquals("[music]", lines[1].text)
    }
}
