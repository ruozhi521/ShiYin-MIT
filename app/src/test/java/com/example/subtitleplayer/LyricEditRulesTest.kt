package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LyricEditRules 单测：按文件行号替换正文，纯字符串逻辑。 */
class LyricEditRulesTest {

    private fun ok(r: LyricEditRules.Result): String {
        assertTrue("期望成功，实际 $r", r is LyricEditRules.Result.Ok)
        return (r as LyricEditRules.Result.Ok).text
    }

    @Test
    fun `替换指定行正文 其余行原样保留`() {
        val lrc = "[00:01.00]第一行\n[00:05.00]第二行\n[00:09.00]第三行"
        val out = ok(LyricEditRules.replaceLineAt(lrc, 1, "改过的第二行"))
        assertEquals("[00:01.00]第一行\n[00:05.00]改过的第二行\n[00:09.00]第三行", out)
    }

    @Test
    fun `保留时间标签的原始写法`() {
        // 全角冒号、三位毫秒都不该被重排成别的格式
        val out = ok(LyricEditRules.replaceLineAt("[00：12.340]旧正文", 0, "新正文"))
        assertEquals("[00：12.340]新正文", out)
    }

    @Test
    fun `同一行多个时间戳时标签全部保留`() {
        val out = ok(LyricEditRules.replaceLineAt("[00:12.34][00:15.00]副歌", 0, "新副歌"))
        assertEquals("[00:12.34][00:15.00]新副歌", out)
    }

    @Test
    fun `行号越界返回 NotALyricLine`() {
        val lrc = "[00:01.00]第一行"
        assertTrue(LyricEditRules.replaceLineAt(lrc, 5, "x") is LyricEditRules.Result.NotALyricLine)
        assertTrue(LyricEditRules.replaceLineAt(lrc, -1, "x") is LyricEditRules.Result.NotALyricLine)
    }

    @Test
    fun `改到元数据行或空行不生效`() {
        val lrc = "[ti:歌名]\n\n[00:01.00]正文"
        assertTrue(LyricEditRules.replaceLineAt(lrc, 0, "x") is LyricEditRules.Result.NotALyricLine)
        assertTrue(LyricEditRules.replaceLineAt(lrc, 1, "x") is LyricEditRules.Result.NotALyricLine)
        // 第三行才是歌词行
        assertEquals("[ti:歌名]\n\n[00:01.00]x", ok(LyricEditRules.replaceLineAt(lrc, 2, "x")))
    }

    @Test
    fun `空正文视为无效 不改文件`() {
        val lrc = "[00:01.00]第一行"
        assertTrue(LyricEditRules.replaceLineAt(lrc, 0, "   ") is LyricEditRules.Result.NotALyricLine)
    }

    @Test
    fun `正文里的换行被压成空格`() {
        val out = ok(LyricEditRules.replaceLineAt("[00:01.00]旧", 0, "上句\n下句"))
        assertEquals("[00:01.00]上句 下句", out)
        assertEquals("整份文本不能多出换行（lrc 一行一条）", 0, out.count { it == '\n' })
    }

    @Test
    fun `offset 标签行与元数据行原样保留`() {
        val lrc = "[ti:歌名]\n[offset:500]\n[00:01.00]旧"
        val out = ok(LyricEditRules.replaceLineAt(lrc, 2, "新"))
        assertEquals("[ti:歌名]\n[offset:500]\n[00:01.00]新", out)
    }

    @Test
    fun `保留 CRLF 换行风格`() {
        val lrc = "[00:01.00]旧\r\n[00:05.00]第二行\r\n"
        val out = ok(LyricEditRules.replaceLineAt(lrc, 0, "新"))
        assertEquals("[00:01.00]新\r\n[00:05.00]第二行\r\n", out)
    }

    @Test
    fun `时分秒四点式标签可识别`() {
        val out = ok(LyricEditRules.replaceLineAt("[00:01:12.34]旧", 0, "新"))
        assertEquals("[00:01:12.34]新", out)
    }

    @Test
    fun `全角方括号的行也能改 且保留全角写法`() {
        val out = ok(LyricEditRules.replaceLineAt("［00:12.34］旧", 0, "新"))
        assertEquals("［00:12.34］新", out)
    }

    @Test
    fun `解析条目与文件行号一一对应`() {
        // 解析器给出的 lineIndex 必须能直接交给 replaceLineAt
        val lrc = "[ti:歌名]\n[00:01.00]第一行\n\n[00:05.00][00:09.00]副歌"
        val entries = SubtitleParser.lrcEntries(lrc)
        assertEquals(3, entries.size) // 副歌那条一行两时间戳 → 两条
        val idx = LyricEditRules.findEntry(entries, 5000, "副歌")
        assertTrue(idx >= 0)
        val lineIndex = entries[idx].lineIndex
        assertEquals(3, lineIndex)
        val out = ok(LyricEditRules.replaceLineAt(lrc, lineIndex, "新副歌"))
        assertEquals("[ti:歌名]\n[00:01.00]第一行\n\n[00:05.00][00:09.00]新副歌", out)
    }

    @Test
    fun `findEntry 同时比对时间与正文`() {
        val lrc = "[00:01.00]同样的话\n[00:05.00]同样的话\n[00:09.00]别的话"
        val entries = SubtitleParser.lrcEntries(lrc)
        assertEquals(1, LyricEditRules.findEntry(entries, 5000, "同样的话"))
        assertEquals(2, LyricEditRules.findEntry(entries, 9000, "别的话"))
        assertEquals(-1, LyricEditRules.findEntry(entries, 9999, "别的话"))
    }
}
