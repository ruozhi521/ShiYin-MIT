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
        // 2.13 起树形目录按中文**拼音**排序（Collator(Locale.CHINA)）：林 lin < 周 zhou。
        // 注意：旧实现用 Kotlin 默认 String 比较（Unicode 码位），
        // 「周」U+5468 < 「林」U+6797，顺序恰好相反——本断言随该变更更新（2.13）。
        assertEquals("林俊杰", dirA.children[0].children[0].name)
        assertEquals("周杰伦", dirA.children[0].children[1].name)
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

    // ---------- 3.0.6：树形跟随排序下拉 ----------
    //
    // 弱志反馈「树形歌单的默认排序、最近播放、名称排序是失效的」——
    // 根因是 build() 结尾**无条件**按名称重排（2.13 的旧设计），
    // 无论下拉选什么都被覆盖。以下用例钉住四种模式的差异。

    private fun fourRoots() = listOf(
        playlist("C", listOf("c.mp3")),
        playlist("A", listOf("a.mp3")),
        playlist("D", listOf("d.mp3")),
        playlist("B", listOf("b.mp3"))
    )

    @Test
    fun `默认顺序下同级仍按名称排`() {
        val roots = LibraryTree.build(fourRoots(), sortMode = LibraryTree.SORT_DEFAULT)
        assertEquals(listOf("A", "B", "C", "D"), roots.map { it.name })
    }

    @Test
    fun `按名称排序与默认顺序一致`() {
        val roots = LibraryTree.build(fourRoots(), sortMode = LibraryTree.SORT_NAME)
        assertEquals(listOf("A", "B", "C", "D"), roots.map { it.name })
    }

    @Test
    fun `按最近播放时最近听过的排最前`() {
        val recent = mapOf("C" to 300L, "A" to 100L)
        val roots = LibraryTree.build(
            fourRoots(),
            sortMode = LibraryTree.SORT_RECENT,
            recent = recent
        )
        // C(300) > A(100) > 无记录的 B、D（时间戳 0，稳定保序 → B 在 D 前）
        assertEquals(listOf("C", "A", "B", "D"), roots.map { it.name })
    }

    @Test
    fun `按最近播放时目录取子树里最新的那条`() {
        // A 自己没听过，但 A/B 里的歌听过 → A 不该被压到无记录组里
        val roots = LibraryTree.build(
            listOf(
                playlist("A/B", listOf("b.mp3")),
                playlist("Z", listOf("z.mp3"))
            ),
            sortMode = LibraryTree.SORT_RECENT,
            recent = mapOf("A/B" to 500L)
        )
        assertEquals(listOf("A", "Z"), roots.map { it.name })
        assertEquals(500L, roots[0].bestRecent(mapOf("A/B" to 500L)))
    }

    @Test
    fun `随机排序同一 seed 结果稳定`() {
        val a = LibraryTree.build(fourRoots(), sortMode = LibraryTree.SORT_RANDOM, seed = 42L)
        val b = LibraryTree.build(fourRoots(), sortMode = LibraryTree.SORT_RANDOM, seed = 42L)
        assertEquals(a.map { it.path }, b.map { it.path })
        // 且确实不是名称顺序（否则等于没生效）；128 次里至少有一次不同才是真随机
        val nameOrder = listOf("A", "B", "C", "D")
        val differs = (1L..128L).any { s ->
            LibraryTree.build(fourRoots(), sortMode = LibraryTree.SORT_RANDOM, seed = s)
                .map { it.name } != nameOrder
        }
        assertEquals(true, differs)
    }

    @Test
    fun `排序只作用于同级不影响层级结构`() {
        val roots = LibraryTree.build(
            listOf(
                playlist("X/c", listOf("1.mp3")),
                playlist("X/a", listOf("2.mp3")),
                playlist("Y", listOf("3.mp3"))
            ),
            sortMode = LibraryTree.SORT_NAME
        )
        // 顶层是纯目录 X 在前；X 的子节点按名称排
        assertEquals(listOf("X", "Y"), roots.map { it.name })
        assertEquals(listOf("a", "c"), roots[0].children.map { it.name })
    }
}
