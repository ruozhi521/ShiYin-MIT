package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.32 从 MainActivity 抽出的翻译纯逻辑：中文判定 + 配置组装。 */
class LyricTranslatorTest {

    private fun lines(vararg texts: String): List<SubtitleLine> =
        texts.map { SubtitleLine(0, 0, it) }

    // ---- isChinesePrimarily ----

    @Test
    fun `中文歌词判定为中文`() {
        assertTrue(LyricTranslator.isChinesePrimarily(lines("月亮代表我的心", "你问我爱你有多深")))
    }

    @Test
    fun `日文假名歌词判定为非中文`() {
        assertFalse(LyricTranslator.isChinesePrimarily(lines("よるのひかり", "ありがとう")))
    }

    @Test
    fun `英文歌词判定为非中文`() {
        assertFalse(LyricTranslator.isChinesePrimarily(lines("Yesterday once more", "When I was young")))
    }

    @Test
    fun `空文本视为中文不自动翻译`() {
        assertTrue(LyricTranslator.isChinesePrimarily(lines("", "   ")))
    }

    @Test
    fun `少量汉字混英文仍判非中文`() {
        // 汉字占比低于 30%：1 个 CJK 字符 + 4 个 ASCII
        assertFalse(LyricTranslator.isChinesePrimarily(lines("Love 音")))
    }

    // ---- configFrom ----

    @Test
    fun `key 为空返回 null`() {
        assertNull(LyricTranslator.configFrom(null, null, null))
        assertNull(LyricTranslator.configFrom("https://x", "  ", null))
        assertNull(LyricTranslator.configFrom("https://x", "", null))
    }

    @Test
    fun `base 与 model 缺省时填默认值`() {
        val c = LyricTranslator.configFrom(null, "sk-test", null)!!
        assertEquals(LyricTranslator.DEFAULT_BASE, c.baseUrl)
        assertEquals("sk-test", c.apiKey)
        assertEquals(LyricTranslator.DEFAULT_MODEL, c.model)
    }

    @Test
    fun `自定义 base 与 model 去空白后生效`() {
        val c = LyricTranslator.configFrom(" https://api.x.com/v1 ", " sk-1 ", " glm-5 ")!!
        assertEquals("https://api.x.com/v1", c.baseUrl)
        assertEquals("sk-1", c.apiKey)
        assertEquals("glm-5", c.model)
    }
}
