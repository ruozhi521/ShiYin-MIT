package com.example.subtitleplayer

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 自定义歌单存储测试（2.14）。
 *
 * Robolectric：CustomPlaylistStore 要 Context.filesDir 才有地方落 JSON。
 * 每个用例前清掉数据文件，避免用例之间互相污染（Robolectric 的 filesDir 会复用）。
 */
@RunWith(RobolectricTestRunner::class)
class CustomPlaylistStoreTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        // 清场：把存储文件与临时文件都删掉
        File(ctx.filesDir, "custom_playlists.json").delete()
        File(ctx.filesDir, "custom_playlists.json.tmp").delete()
    }

    private fun uris(vararg n: Int): List<String> = n.map { "content://docs/document/song$it" }

    // ---- 新建 ----

    @Test
    fun `新建歌单后可列出`() {
        assertTrue(CustomPlaylistStore.create(ctx, "睡前"))
        assertTrue(CustomPlaylistStore.exists(ctx, "睡前"))
        assertEquals(listOf("睡前"), CustomPlaylistStore.names(ctx))
    }

    @Test
    fun `同名歌单不能重复创建`() {
        assertTrue(CustomPlaylistStore.create(ctx, "睡前"))
        assertFalse("重复创建应失败", CustomPlaylistStore.create(ctx, "睡前"))
        assertEquals(1, CustomPlaylistStore.names(ctx).size)
    }

    @Test
    fun `空名与超长名拒绝创建`() {
        assertFalse(CustomPlaylistStore.create(ctx, ""))
        assertFalse(CustomPlaylistStore.create(ctx, "   "))
        assertFalse(
            CustomPlaylistStore.create(ctx, "x".repeat(CustomPlaylistStore.MAX_NAME_LEN + 1))
        )
        assertTrue(CustomPlaylistStore.create(ctx, "x".repeat(CustomPlaylistStore.MAX_NAME_LEN)))
    }

    @Test
    fun `名称两端空白被裁掉`() {
        assertTrue(CustomPlaylistStore.create(ctx, "  睡前  "))
        assertTrue(CustomPlaylistStore.exists(ctx, "睡前"))
    }

    @Test
    fun `保持创建顺序`() {
        CustomPlaylistStore.create(ctx, "A")
        CustomPlaylistStore.create(ctx, "B")
        CustomPlaylistStore.create(ctx, "C")
        assertEquals(listOf("A", "B", "C"), CustomPlaylistStore.names(ctx))
    }

    // ---- 加歌 / 移除 ----

    @Test
    fun `加歌后按加入顺序返回`() {
        CustomPlaylistStore.create(ctx, "睡前")
        assertTrue(CustomPlaylistStore.add(ctx, "睡前", "u1"))
        assertTrue(CustomPlaylistStore.add(ctx, "睡前", "u2"))
        assertEquals(listOf("u1", "u2"), CustomPlaylistStore.uris(ctx, "睡前"))
    }

    @Test
    fun `同一首歌不会重复加入`() {
        CustomPlaylistStore.create(ctx, "睡前")
        assertTrue(CustomPlaylistStore.add(ctx, "睡前", "u1"))
        assertFalse("重复加应返回 false", CustomPlaylistStore.add(ctx, "睡前", "u1"))
        assertEquals(1, CustomPlaylistStore.uris(ctx, "睡前").size)
    }

    @Test
    fun `向不存在的歌单加歌失败`() {
        assertFalse(CustomPlaylistStore.add(ctx, "不存在", "u1"))
    }

    @Test
    fun `移除歌曲`() {
        CustomPlaylistStore.create(ctx, "睡前")
        CustomPlaylistStore.add(ctx, "睡前", "u1")
        CustomPlaylistStore.add(ctx, "睡前", "u2")
        CustomPlaylistStore.remove(ctx, "睡前", "u1")
        assertEquals(listOf("u2"), CustomPlaylistStore.uris(ctx, "睡前"))
    }

    @Test
    fun `移除不存在的歌不影响其他项`() {
        CustomPlaylistStore.create(ctx, "睡前")
        CustomPlaylistStore.add(ctx, "睡前", "u1")
        CustomPlaylistStore.remove(ctx, "睡前", "不存在")
        assertEquals(listOf("u1"), CustomPlaylistStore.uris(ctx, "睡前"))
    }

    // ---- 重命名 ----

    @Test
    fun `重命名保留歌曲与位置`() {
        CustomPlaylistStore.create(ctx, "A")
        CustomPlaylistStore.create(ctx, "B")
        CustomPlaylistStore.add(ctx, "A", "u1")
        assertTrue(CustomPlaylistStore.rename(ctx, "A", "A2"))
        // 位置不变：仍排在 B 前面
        assertEquals(listOf("A2", "B"), CustomPlaylistStore.names(ctx))
        assertEquals(listOf("u1"), CustomPlaylistStore.uris(ctx, "A2"))
    }

    @Test
    fun `重命名到已存在的名字失败`() {
        CustomPlaylistStore.create(ctx, "A")
        CustomPlaylistStore.create(ctx, "B")
        assertFalse(CustomPlaylistStore.rename(ctx, "A", "B"))
        assertTrue(CustomPlaylistStore.exists(ctx, "A"))
    }

    @Test
    fun `重命名不存在的歌单失败`() {
        assertFalse(CustomPlaylistStore.rename(ctx, "不存在", "新名"))
    }

    @Test
    fun `重命名成自己视为成功`() {
        CustomPlaylistStore.create(ctx, "A")
        assertTrue(CustomPlaylistStore.rename(ctx, "A", "A"))
        assertTrue(CustomPlaylistStore.exists(ctx, "A"))
    }

    // ---- 删除 ----

    @Test
    fun `删除歌单后不再存在 其他歌单不受影响`() {
        CustomPlaylistStore.create(ctx, "A")
        CustomPlaylistStore.create(ctx, "B")
        CustomPlaylistStore.delete(ctx, "A")
        assertFalse(CustomPlaylistStore.exists(ctx, "A"))
        assertTrue(CustomPlaylistStore.exists(ctx, "B"))
    }

    @Test
    fun `删除不存在的歌单不抛异常`() {
        CustomPlaylistStore.delete(ctx, "不存在")
    }

    // ---- 覆盖写（拖拽排序） ----

    @Test
    fun `整表覆盖写保存新顺序`() {
        CustomPlaylistStore.create(ctx, "睡前")
        CustomPlaylistStore.add(ctx, "睡前", "u1")
        CustomPlaylistStore.add(ctx, "睡前", "u2")
        CustomPlaylistStore.replaceSongs(ctx, "睡前", listOf("u2", "u1"))
        assertEquals(listOf("u2", "u1"), CustomPlaylistStore.uris(ctx, "睡前"))
    }

    @Test
    fun `整表覆盖写不会凭空造歌单`() {
        CustomPlaylistStore.replaceSongs(ctx, "不存在", listOf("u1"))
        assertFalse(CustomPlaylistStore.exists(ctx, "不存在"))
    }

    // ---- 持久化与容错 ----

    @Test
    fun `数据跨实例读取保持`() {
        CustomPlaylistStore.create(ctx, "睡前")
        CustomPlaylistStore.add(ctx, "睡前", "u1")
        // 重新 load（模拟重启）
        assertEquals(listOf("u1"), CustomPlaylistStore.load(ctx)["睡前"])
    }

    @Test
    fun `存储文件损坏时返回空表不抛异常`() {
        File(ctx.filesDir, "custom_playlists.json").writeText("{ 这不是合法 JSON")
        assertEquals(0, CustomPlaylistStore.names(ctx).size)
    }

    @Test
    fun `文件不存在时返回空表`() {
        assertEquals(0, CustomPlaylistStore.names(ctx).size)
    }

    @Test
    fun `异常数据里的空 uri 被跳过`() {
        File(ctx.filesDir, "custom_playlists.json")
            .writeText("""{"睡前":["u1","","u2"]}""")
        assertEquals(listOf("u1", "u2"), CustomPlaylistStore.uris(ctx, "睡前"))
    }
}
