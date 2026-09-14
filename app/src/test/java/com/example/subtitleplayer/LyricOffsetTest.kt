package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** 2.2 歌词校准纯逻辑：行平移 / offset 标签读写 / lrc 文本解码。 */
class LyricOffsetTest {

    // ---- applyToLines ----

    @Test
    fun `行平移正常移动时间戳`() {
        val lines = listOf(SubtitleLine(1000, 5000, "a"), SubtitleLine(2000, 6000, "b"))
        val moved = LyricOffset.applyToLines(lines, 300)
        assertEquals(1300, moved[0].startMs)
        assertEquals(5300, moved[0].endMs)
        assertEquals(2300, moved[1].startMs)
        assertEquals("a", moved[0].text)
    }

    @Test
    fun `行平移支持负偏移`() {
        val moved = LyricOffset.applyToLines(listOf(SubtitleLine(1000, 5000, "a")), -300)
        assertEquals(700, moved[0].startMs)
        assertEquals(4700, moved[0].endMs)
    }

    @Test
    fun `lrc行的无限endMs不参与平移防溢出`() {
        val moved = LyricOffset.applyToLines(listOf(SubtitleLine(1000, Int.MAX_VALUE, "a")), 300)
        assertEquals(1300, moved[0].startMs)
        assertEquals(Int.MAX_VALUE, moved[0].endMs)
    }

    @Test
    fun `偏移为0时原样返回`() {
        val lines = listOf(SubtitleLine(1000, 5000, "a"))
        assertTrue(LyricOffset.applyToLines(lines, 0) === lines)
    }

    // ---- detectOffsetTag ----

    @Test
    fun `识别正数offset标签`() {
        assertEquals(300, LyricOffset.detectOffsetTag("[ti:x]\n[offset:300]\n[00:01.00]a"))
    }

    @Test
    fun `识别负数offset标签`() {
        assertEquals(-300, LyricOffset.detectOffsetTag("[offset:-300]"))
    }

    @Test
    fun `无offset标签返回0`() {
        assertEquals(0, LyricOffset.detectOffsetTag("[ti:x]\n[00:01.00]歌词"))
    }

    @Test
    fun `大小写与空格兼容`() {
        assertEquals(150, LyricOffset.detectOffsetTag("[ OFFSET: 150 ]"))
    }

    // ---- buildLrcWithOffset ----

    @Test
    fun `写入新offset标签`() {
        val out = LyricOffset.buildLrcWithOffset("[ti:x]\n[00:01.00]歌词", 300)
        assertEquals("[offset:300]\n[ti:x]\n[00:01.00]歌词", out)
    }

    @Test
    fun `替换旧offset标签避免双重偏移`() {
        val out = LyricOffset.buildLrcWithOffset("[offset:100]\n[00:01.00]歌词", 500)
        assertEquals(500, LyricOffset.detectOffsetTag(out))
        assertFalse(out.contains("[offset:100]"))
        assertEquals(1, Regex("""(?i)\[offset:""").findAll(out).count())
    }

    @Test
    fun `行内旧标签被清除且不破坏行文本`() {
        val out = LyricOffset.buildLrcWithOffset("[00:01.00][offset:100]歌词", 0)
        assertEquals("[00:01.00]歌词", out)
    }

    @Test
    fun `偏移为0时移除全部标签`() {
        val out = LyricOffset.buildLrcWithOffset("[offset:100]\n[00:01.00]歌词", 0)
        assertFalse(out.contains("[offset:"))
        assertTrue(out.contains("[00:01.00]歌词"))
    }

    @Test
    fun `标签重复多份也能全部替换`() {
        val out = LyricOffset.buildLrcWithOffset("[offset:1]\n[offset:2]\n[00:01.00]a", 300)
        assertEquals("[offset:300]\n[00:01.00]a", out)
    }

    // ---- decodeLrcText ----

    @Test
    fun `UTF8无BOM正常解码`() {
        val bytes = "[00:01.00]月亮".toByteArray(Charsets.UTF_8)
        assertEquals("[00:01.00]月亮", LyricOffset.decodeLrcText(bytes))
    }

    @Test
    fun `UTF8BOM被剥离`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "[00:01.00]月亮".toByteArray(Charsets.UTF_8)
        assertEquals("[00:01.00]月亮", LyricOffset.decodeLrcText(bytes))
    }

    @Test
    fun `GBK内容回退GBK解码`() {
        // 「月」的 GBK 编码不是合法 UTF-8，应回退 GBK 成功解码
        val bytes = "[00:01.00]月亮".toByteArray(Charset.forName("GBK"))
        assertEquals("[00:01.00]月亮", LyricOffset.decodeLrcText(bytes))
    }
}
