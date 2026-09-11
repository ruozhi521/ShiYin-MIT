package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** cleanTag 乱码检测测试（GBK/UTF-8 被 MediaMetadataRetriever 误读成 Latin-1 的典型乱码）。 */
class CleanTagTest {

    @Test
    fun `UTF-8 中文被读成 Latin-1 的高位乱码应识别为乱码`() {
        // "周杰伦" 的 UTF-8 字节被逐个当 Latin-1 高位字符
        val garbled = "\u5468\u6770\u4F26".map { String(byteArrayOf(it.code.toByte()), Charsets.ISO_8859_1) }
            .joinToString("").let { s ->
                // 构造：每个合法 CJK 字符的 UTF-8 字节 → Latin-1 高位（保证 >2/3 高位且无 CJK）
                val bytes = "\u5468\u6770\u4F26".toByteArray(Charsets.UTF_8)
                String(bytes, Charsets.ISO_8859_1)
            }
        assertNull("高位乱码应判为乱码", cleanTag(garbled))
    }

    @Test
    fun `正常中文标签不误伤`() {
        assertEquals("周杰伦", cleanTag("周杰伦"))
        assertEquals("晴天", cleanTag("晴天"))
    }

    @Test
    fun `混合中文字符串不误伤`() {
        // 正常 CJK + 少量高位（如特殊符号）仍保留
        assertEquals("晴天 ½", cleanTag("晴天 ½"))
    }

    @Test
    fun `空串和空白返回 null`() {
        assertNull(cleanTag(""))
        assertNull(cleanTag("   "))
        assertNull(cleanTag(null))
    }

    @Test
    fun `含替换字符的解码失败判为乱码`() {
        // 全高位无 CJK 的替换字符串 → 乱码
        assertNull(cleanTag("\uFFFD\uFFFD\uFFFD"))
        // 有 CJK 的串保留（cleanTag 只判高位占比，不误伤正常中文串）
        assertEquals("\uFFFD异常", cleanTag("\uFFFD异常"))
    }
}
