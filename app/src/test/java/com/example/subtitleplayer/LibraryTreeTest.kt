package com.example.subtitleplayer

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** LibraryTree 文件夹树构建测试（Robolectric：Song 需 Uri）。 */
@RunWith(RobolectricTestRunner::class)
class LibraryTreeTest {

    private fun song(name: String): Song = Song(
        title = name,
        uri = Uri.parse("content://provider/document/" + name),
        folder = name.substringBeforeLast('.'),
        artist = ""
    )

    private fun playlist(name: String, songNames: List<String>): Playlist =
        Playlist(name, songNames.map { song(it) })

    @Test
    fun `多级路径构建树形层级`() {
        val roots = LibraryTree.build(
            listOf(
                playlist("A/B/周杰伦", listOf("a.mp3")),
                playlist("A/B/林俊杰", listOf("b.mp3")),
                playlist("A/纯音乐", listOf("c.mp3")),
                playlist("根目录", listOf("d.mp3"))
            )
        )
        // 纯目录在前：A、根目录（叶子歌单）
        assertEquals(2, roots.size)
        val dirA = roots[0]
        assertEquals("A", dirA.name)
        assertEquals(2, dirA.children.size)
        assertEquals("B", dirA.children[0].name)
        assertEquals(2, dirA.children[0].children.size)
        assertEquals("周杰伦", dirA.children[0].children[0].name)
        // 子树歌曲数汇总
        assertEquals(3, dirA.totalSongs)
    }

    @Test
    fun `flatten 按展开状态拍平`() {
        val roots = LibraryTree.build(
            listOf(
                playlist("A/B/周杰伦", listOf("a.mp3")),
                playlist("A/纯音乐", listOf("c.mp3")),
                playlist("根目录", listOf("d.mp3"))
            )
        )
        // 全折叠：只显示顶层 A + 根目录
        val collapsed = LibraryTree.flatten(roots, emptySet())
        assertEquals(2, collapsed.size)

        // 展开 A：A、B、纯音乐、根目录
        val expanded = LibraryTree.flatten(roots, setOf("A"))
        assertEquals(4, expanded.size)

        // 展开 A 与 A/B：全展开
        val all = LibraryTree.flatten(roots, setOf("A", "A/B"))
        assertEquals(5, all.size)
    }

    @Test
    fun `同名歌单路径不合并`() {
        val roots = LibraryTree.build(
            listOf(
                playlist("A/周杰伦", listOf("a.mp3")),
                playlist("B/周杰伦", listOf("b.mp3"))
            )
        )
        assertEquals(2, roots.size)
        assertEquals("A", roots[0].name)
        assertEquals("B", roots[1].name)
    }

    @Test
    fun `目录自身有歌时叶子歌单挂载且可展开`() {
        val roots = LibraryTree.build(
            listOf(
                playlist("A", listOf("a.mp3")),          // A 本身有歌
                playlist("A/B", listOf("b.mp3"))         // A 也有子目录
            )
        )
        assertEquals(1, roots.size)
        val dirA = roots[0]
        assertEquals(true, dirA.isDir)      // 有子目录 → 目录行
        assertEquals(2, dirA.totalSongs)    // 自身 1 + 子 1
        assertEquals(true, dirA.playlist != null) // 自身有歌
    }
}
