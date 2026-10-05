package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 自动序号残留识别测试（2.14）。
 *
 * 回归目标：粉丝反馈「每次都要重新识别台本，而且是直接用原有的台本来识别」+
 * 「有多个重复名的情况下不会自动读取歌词」。根因是 SAF 的 createDocument
 * 同名不覆盖、自动改名为 `歌名(1).lrc`，这些残留又被台本检测读回去。
 *
 * 注意本测试保护的是一条**保守**语义：只有同目录确实存在原名时，编号文件才算残留。
 * 若哪天有人把它改成「见到括号就丢」，下面 `目录里没有原名时不算残留` 会红。
 */
class LrcResidueRulesTest {

    // ---- autoNumberBase：拆出基础名 ----

    @Test
    fun `识别标准自动序号名`() {
        assertEquals("歌名.lrc", LrcResidueRules.autoNumberBase("歌名(1).lrc"))
        assertEquals("歌名.lrc", LrcResidueRules.autoNumberBase("歌名(12).lrc"))
        assertEquals("台本.txt", LrcResidueRules.autoNumberBase("台本(3).txt"))
    }

    @Test
    fun `带多级扩展名的自动序号名`() {
        // ASR 产物形如 歌名.mp3.lrc，重复后是 歌名.mp3(1).lrc
        assertEquals("歌名.mp3.lrc", LrcResidueRules.autoNumberBase("歌名.mp3(1).lrc"))
    }

    @Test
    fun `非自动序号名返回 null`() {
        assertNull(LrcResidueRules.autoNumberBase("歌名.lrc"))
        assertNull(LrcResidueRules.autoNumberBase("歌名(abc).lrc"))
        assertNull(LrcResidueRules.autoNumberBase("歌名(1)"))
        assertNull(LrcResidueRules.autoNumberBase("(1).lrc"))
        assertNull(LrcResidueRules.autoNumberBase(""))
        assertNull(LrcResidueRules.autoNumberBase(null))
    }

    @Test
    fun `名字里本来就有括号但不是序号 不算自动序号`() {
        // 音声作品常用 トラック(前編) 这类命名，必须原样保留
        assertNull(LrcResidueRules.autoNumberBase("トラック(前編).lrc"))
        assertNull(LrcResidueRules.autoNumberBase("曲目(A).lrc"))
    }

    // ---- isResidue：真正判定 ----

    @Test
    fun `原名存在时 编号文件判为残留`() {
        val siblings = setOf("歌名.lrc", "歌名(1).lrc", "歌名(2).lrc", "歌名.mp3")
        assertTrue(LrcResidueRules.isResidue("歌名(1).lrc", siblings))
        assertTrue(LrcResidueRules.isResidue("歌名(2).lrc", siblings))
    }

    @Test
    fun `原名不存在时 编号文件不是残留`() {
        // 关键保护：用户真把文件命名成 歌名(1).lrc 而目录里没有 歌名.lrc，
        // 那它就是唯一的一份歌词，必须照常生效——宁可留垃圾也不能让歌词消失。
        val siblings = setOf("歌名(1).lrc", "歌名.mp3")
        assertFalse(LrcResidueRules.isResidue("歌名(1).lrc", siblings))
    }

    @Test
    fun `原名与编号文件大小写不同也算原名存在`() {
        val siblings = setOf("Song.LRC", "song(1).lrc")
        assertTrue(LrcResidueRules.isResidue("song(1).lrc", siblings))
    }

    @Test
    fun `普通文件永远不是残留`() {
        val siblings = setOf("歌名.lrc", "歌名.mp3")
        assertFalse(LrcResidueRules.isResidue("歌名.lrc", siblings))
        assertFalse(LrcResidueRules.isResidue("歌名.mp3", siblings))
        assertFalse(LrcResidueRules.isResidue(null, siblings))
    }

    @Test
    fun `空目录里的编号文件不是残留`() {
        assertFalse(LrcResidueRules.isResidue("歌名(1).lrc", emptySet()))
    }

    @Test
    fun `同目录大量残留时 每一个都判为残留`() {
        // 粉丝截图场景：反复点生成歌词攒了 4 份
        val siblings = setOf(
            "台本.txt", "台本(1).txt", "台本(2).txt", "台本(3).txt"
        )
        for (n in 1..3) {
            assertTrue("台本($n).txt 应判为残留", LrcResidueRules.isResidue("台本($n).txt", siblings))
        }
        // 原名本身不是残留
        assertFalse(LrcResidueRules.isResidue("台本.txt", siblings))
    }
}
