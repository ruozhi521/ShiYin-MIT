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
}
