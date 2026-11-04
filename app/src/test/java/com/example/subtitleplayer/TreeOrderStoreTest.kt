package com.example.subtitleplayer

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 树形目录拖拽顺序的存储测试（3.0.6）。
 *
 * 弱志 2026-11-04 反馈「树型文件夹的歌单排序好像也失效了」——
 * 先钉住存储层的往返，确认问题在存储还是在 UI 接线。
 */
@RunWith(RobolectricTestRunner::class)
class TreeOrderStoreTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        // Robolectric 的 filesDir 会复用，逐用例清场免污染
        File(ctx.filesDir, "tree_order.json").delete()
        File(ctx.filesDir, "tree_order.json.tmp").delete()
    }

    private fun node(name: String, path: String) =
        TreeNode(name, path, 0, Playlist(name, emptyList()))

    @Test
    fun `没拖过时原样返回`() {
        val nodes = listOf(node("B", "B"), node("A", "A"))
        assertEquals(nodes, TreeOrderStore.apply(ctx, "", nodes))
    }

    @Test
    fun `根层顺序按保存值重排`() {
        TreeOrderStore.saveLevel(ctx, "", listOf("B", "A", "C"))
        val nodes = listOf(node("A", "A"), node("B", "B"), node("C", "C"))
        assertEquals(
            listOf("B", "A", "C"),
            TreeOrderStore.apply(ctx, "", nodes).map { it.path }
        )
    }

    @Test
    fun `不同父路径的顺序互不影响`() {
        TreeOrderStore.saveLevel(ctx, "A", listOf("A/D2", "A/D1"))
        TreeOrderStore.saveLevel(ctx, "B", listOf("B/D1", "B/D2"))

        val aKids = listOf(node("D1", "A/D1"), node("D2", "A/D2"))
        val bKids = listOf(node("D1", "B/D1"), node("D2", "B/D2"))

        assertEquals(
            listOf("A/D2", "A/D1"),
            TreeOrderStore.apply(ctx, "A", aKids).map { it.path }
        )
        assertEquals(
            listOf("B/D1", "B/D2"),
            TreeOrderStore.apply(ctx, "B", bKids).map { it.path }
        )
    }

    @Test
    fun `新增的节点按原序追加到末尾不会丢`() {
        TreeOrderStore.saveLevel(ctx, "", listOf("C", "A"))
        val nodes = listOf(node("A", "A"), node("B", "B"), node("C", "C"))
        // C、A 按记录在前；B 是后加的，追加到末尾
        assertEquals(
            listOf("C", "A", "B"),
            TreeOrderStore.apply(ctx, "", nodes).map { it.path }
        )
    }

    @Test
    fun `记录里有但已不存在的节点直接跳过`() {
        TreeOrderStore.saveLevel(ctx, "", listOf("Z", "B", "A"))
        val nodes = listOf(node("A", "A"), node("B", "B"))
        assertEquals(
            listOf("B", "A"),
            TreeOrderStore.apply(ctx, "", nodes).map { it.path }
        )
    }

    @Test
    fun `保存空列表等于清掉该层记录`() {
        TreeOrderStore.saveLevel(ctx, "", listOf("B", "A"))
        TreeOrderStore.saveLevel(ctx, "", emptyList())
        val nodes = listOf(node("A", "A"), node("B", "B"))
        assertEquals(nodes, TreeOrderStore.apply(ctx, "", nodes))
    }
}
