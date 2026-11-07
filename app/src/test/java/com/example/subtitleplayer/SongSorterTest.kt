package com.example.subtitleplayer

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 歌曲排序测试（3.0.6）。
 *
 * 来源：B 站粉丝要求「正序倒序 / 添加到媒体库时间 / 修改时间」。
 * 锁住三条容易写错的规则：
 * 1. 时间缺失（0）**不管正序倒序都排最后** —— 混在中间会让人觉得"排序坏了"
 * 2. 中文按拼音（Collator），不是 Unicode 码位
 * 3. 倒序是整体反转，但缺失值的"排最后"语义要保住
 */
@RunWith(RobolectricTestRunner::class)
class SongSorterTest {

    private fun song(name: String, modified: Long = 0L): Song = Song(
        title = name,
        uri = Uri.parse("content://p/doc/" + name),
        folder = "F",
        fileStem = name,
        lastModified = modified
    )

    private val a = song("A", 100L)
    private val b = song("B", 300L)
    private val c = song("C", 200L)
    /** 修改时间未知。 */
    private val unknown = song("Z")

    @Test
    fun `不排序时原样返回`() {
        val list = listOf(c, a, b)
        assertEquals(list, SongSorter.sort(list, SongSorter.NONE, desc = false, added = emptyMap()))
    }

    @Test
    fun `按标题正序中文按拼音`() {
        // 周 zhou(U+5468) / 安 an(U+5B89)：Unicode 码位会排成"周"在前，拼音才是"安"在前
        val zhou = song("周杰伦", 1L)
        val an = song("安和桥", 2L)
        val out = SongSorter.sort(
            listOf(zhou, an), SongSorter.NAME, desc = false, added = emptyMap()
        )
        assertEquals(listOf("安和桥", "周杰伦"), out.map { it.title })
    }

    @Test
    fun `按标题倒序`() {
        val out = SongSorter.sort(
            listOf(a, b, c), SongSorter.NAME, desc = true, added = emptyMap()
        )
        assertEquals(listOf("C", "B", "A"), out.map { it.title })
    }

    @Test
    fun `按修改时间正序时最旧的在前`() {
        val out = SongSorter.sort(
            listOf(b, a, c), SongSorter.MODIFIED, desc = false, added = emptyMap()
        )
        assertEquals(listOf("A", "C", "B"), out.map { it.title })
    }

    @Test
    fun `按修改时间倒序时最新的在前`() {
        val out = SongSorter.sort(
            listOf(a, c, b), SongSorter.MODIFIED, desc = true, added = emptyMap()
        )
        assertEquals(listOf("B", "C", "A"), out.map { it.title })
    }

    @Test
    fun `修改时间缺失的歌永远排在最后`() {
        val asc = SongSorter.sort(
            listOf(unknown, b, a), SongSorter.MODIFIED, desc = false, added = emptyMap()
        )
        assertEquals("Z", asc.last().title)

        val desc = SongSorter.sort(
            listOf(unknown, b, a), SongSorter.MODIFIED, desc = true, added = emptyMap()
        )
        // 倒序时也要在最后：粉丝找新歌，未知时间的堆在底部不挡路
        assertEquals("Z", desc.last().title)
    }

    @Test
    fun `按收录时间倒序时后进库的在前`() {
        val added = mapOf(
            a.uri.toString() to 1000L,
            b.uri.toString() to 3000L,
            c.uri.toString() to 2000L
        )
        val out = SongSorter.sort(
            listOf(a, b, c), SongSorter.ADDED, desc = true, added = added
        )
        assertEquals(listOf("B", "C", "A"), out.map { it.title })
    }

    @Test
    fun `收录时间缺失的歌排在最后`() {
        val added = mapOf(b.uri.toString() to 3000L)
        val out = SongSorter.sort(
            listOf(a, b), SongSorter.ADDED, desc = true, added = added
        )
        assertEquals("A", out.last().title)
    }

    @Test
    fun `单首或空列表不崩`() {
        assertEquals(emptyList<Song>(), SongSorter.sort(emptyList(), SongSorter.MODIFIED, true, emptyMap()))
        assertEquals(
            listOf(a),
            SongSorter.sort(listOf(a), SongSorter.MODIFIED, true, emptyMap())
        )
    }
}
