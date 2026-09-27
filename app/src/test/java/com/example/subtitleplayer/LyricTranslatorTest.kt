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

    // ---- parseLoose：行号位数（2.13 修复「4 位行号被丢弃」）----

    @Test
    fun `三位以内行号正常解析`() {
        val r = LyricTranslator.parseLoose(
            "1|一\n99|九十九\n999|九百九十九",
            setOf(1, 99, 999)
        )
        assertEquals(mapOf(1 to "一", 99 to "九十九", 999 to "九百九十九"), r)
    }

    @Test
    fun `四位行号必须能解析 一小时台本必然超过 999 行`() {
        // 这是 2.13 修的 bug：原正则 \d{1,3} 只吃 3 位，1000+ 的行整行被丢弃
        val r = LyricTranslator.parseLoose(
            "1000|一千\n1024|一零二四\n1234|一二三四",
            setOf(1000, 1024, 1234)
        )
        assertEquals(
            "4 位行号必须全部解析出来（否则这些行永远翻译不出来）",
            mapOf(1000 to "一千", 1024 to "一零二四", 1234 to "一二三四"),
            r
        )
    }

    @Test
    fun `五位行号也能解析`() {
        val r = LyricTranslator.parseLoose("10560|一万零五百六十", setOf(10560))
        assertEquals(mapOf(10560 to "一万零五百六十"), r)
    }

    @Test
    fun `不在期望集合里的行号被忽略`() {
        val r = LyricTranslator.parseLoose("1|一\n2|二", setOf(1))
        assertEquals(mapOf(1 to "一"), r)
    }

    @Test
    fun `多种分隔符都支持`() {
        val r = LyricTranslator.parseLoose(
            "1|竖线\n2. 点号\n3、顿号\n4：全角冒号\n5:半角冒号",
            setOf(1, 2, 3, 4, 5)
        )
        assertEquals(
            mapOf(1 to "竖线", 2 to "点号", 3 to "顿号", 4 to "全角冒号", 5 to "半角冒号"),
            r
        )
    }

    @Test
    fun `空译文与乱格式行不写入结果`() {
        val r = LyricTranslator.parseLoose("1|\n没有行号的行\n2|有", setOf(1, 2))
        assertEquals(mapOf(2 to "有"), r)
    }
}
