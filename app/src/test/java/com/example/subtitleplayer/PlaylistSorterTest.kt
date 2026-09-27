package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 歌单排序测试（2.13）：按名称（拼音）/ 最近播放 / 稳定随机。
 * 保留 Robolectric：Playlist 内含 Song（字段为 android.net.Uri），
 * 在纯 JVM 下无 android.jar 环境加载该类型不安全；本文件虽不构造 Song 实例，
 * 但与 LibraryTreeTest 保持一致更稳妥（该组合已在云端 CI 验证通过）。
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistSorterTest {

    private fun pl(vararg names: String): List<Playlist> =
        names.map { Playlist(it, emptyList<Song>()) }

    private fun names(list: List<Playlist>): List<String> = list.map { it.name }

    @Test
    fun `按名称 中文按拼音而不是 Unicode 码位`() {
        // Unicode 码位顺序会把「周」排在「安」前；拼音顺序是 安 < 周
        val input = pl("周杰伦", "安室奈美惠", "beyond", "Aimer")
        val out = names(PlaylistSorter.byName(input))
        val iAn = out.indexOf("安室奈美惠")
        val iZhou = out.indexOf("周杰伦")
        assertTrue("拼音序应让「安」在「周」之前，实际=$out", iAn < iZhou)
    }

    @Test
    fun `按名称 字母与中文混排不崩且元素不丢`() {
        val input = pl("周杰伦", "beyond", "Aimer", "安室奈美惠")
        val out = names(PlaylistSorter.byName(input))
        assertEquals(input.size, out.size)
        assertEquals(input.map { it.name }.sorted(), out.sorted())
    }

    @Test
    fun `最近播放 时间戳大的在前`() {
        val input = pl("A", "B", "C")
        val recent = mapOf("A" to 100L, "B" to 300L, "C" to 200L)
        assertEquals(listOf("B", "C", "A"), names(PlaylistSorter.byRecent(input, recent)))
    }

    @Test
    fun `最近播放 没记录的歌单排在最后 不在中间插队`() {
        val input = pl("没听过", "听过A", "也没听过", "听过B")
        val recent = mapOf("听过A" to 100L, "听过B" to 200L)
        val out = names(PlaylistSorter.byRecent(input, recent))
        // 有记录的按时间倒序在前；无记录的排最后，且**保持输入原顺序**（稳定排序）
        // 期望值来自本机 JDK 实跑验证（Comparator.comparingLong.reversed + 稳定排序）
        assertEquals(listOf("听过B", "听过A", "没听过", "也没听过"), out)
        // 关键断言：无记录的绝不能插在有记录的中间
        assertTrue(
            "没播放记录的歌单应全部排在末尾",
            out.indexOf("没听过") >= 2 && out.indexOf("也没听过") >= 2
        )
    }

    @Test
    fun `最近播放 全无记录时保持原顺序 不重排`() {
        // 输入故意用非字典序，验证「保持原顺序」而不是被次级规则重排
        val input = pl("C", "A", "B")
        assertEquals(
            "全部无记录时应原样返回（不被名字次级规则重排）",
            listOf("C", "A", "B"),
            names(PlaylistSorter.byRecent(input, emptyMap()))
        )
    }

    @Test
    fun `稳定随机 同一 seed 结果一致 不闪跳`() {
        val input = pl("A", "B", "C", "D", "E", "F", "G", "H")
        val r1 = names(PlaylistSorter.shuffledStable(input, 42L))
        val r2 = names(PlaylistSorter.shuffledStable(input, 42L))
        assertEquals("同一 seed 必须得到同一顺序，否则列表会闪跳", r1, r2)
    }

    @Test
    fun `稳定随机 不同 seed 结果不同 且元素不丢`() {
        val input = pl("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
        val r1 = names(PlaylistSorter.shuffledStable(input, 1L))
        val r2 = names(PlaylistSorter.shuffledStable(input, 2L))
        assertTrue("不同 seed 应该给出不同顺序", r1 != r2)
        assertEquals(input.map { it.name }.sorted(), r1.sorted())
        assertEquals(input.map { it.name }.sorted(), r2.sorted())
    }

    @Test
    fun `稳定随机 单元素与空列表安全`() {
        assertEquals(listOf("A"), names(PlaylistSorter.shuffledStable(pl("A"), 7L)))
        assertEquals(emptyList<String>(), names(PlaylistSorter.shuffledStable(emptyList(), 7L)))
    }
}
