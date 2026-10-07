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
 * 隐藏名单存储测试（2.14）。
 *
 * Robolectric：需要 Context.filesDir 才有地方落 JSON。
 * 每个用例前清场，避免用例之间互相污染（Robolectric 的 filesDir 会复用）。
 */
@RunWith(RobolectricTestRunner::class)
class HiddenStoreTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        File(ctx.filesDir, "hidden.json").delete()
        File(ctx.filesDir, "hidden.json.tmp").delete()
    }

    // ---- 基本读写 ----

    @Test
    fun `初始状态为空`() {
        assertTrue(HiddenStore.isEmpty(ctx))
        assertEquals(0, HiddenStore.hiddenSongs(ctx).size)
        assertEquals(0, HiddenStore.hiddenFolders(ctx).size)
    }

    @Test
    fun `隐藏歌曲后可读回`() {
        HiddenStore.hideSong(ctx, "u1")
        HiddenStore.hideSong(ctx, "u2")
        assertEquals(setOf("u1", "u2"), HiddenStore.hiddenSongs(ctx))
        assertFalse(HiddenStore.isEmpty(ctx))
    }

    @Test
    fun `隐藏文件夹后可读回`() {
        HiddenStore.hideFolder(ctx, "音声/RJ01")
        assertEquals(setOf("音声/RJ01"), HiddenStore.hiddenFolders(ctx))
    }

    @Test
    fun `重复隐藏同一首不产生重复项`() {
        HiddenStore.hideSong(ctx, "u1")
        HiddenStore.hideSong(ctx, "u1")
        assertEquals(1, HiddenStore.hiddenSongs(ctx).size)
    }

    @Test
    fun `重复隐藏同一文件夹不产生重复项`() {
        HiddenStore.hideFolder(ctx, "A/B")
        HiddenStore.hideFolder(ctx, "A/B")
        assertEquals(1, HiddenStore.hiddenFolders(ctx).size)
    }

    @Test
    fun `空值被忽略`() {
        HiddenStore.hideSong(ctx, "")
        HiddenStore.hideFolder(ctx, "")
        HiddenStore.hideFolder(ctx, "   ")
        assertTrue(HiddenStore.isEmpty(ctx))
    }

    @Test
    fun `文件夹路径两端斜杠被裁掉`() {
        HiddenStore.hideFolder(ctx, "/音声/RJ01/")
        assertEquals(setOf("音声/RJ01"), HiddenStore.hiddenFolders(ctx))
    }

    // ---- 恢复 ----

    @Test
    fun `恢复单曲只去掉那一首`() {
        HiddenStore.hideSong(ctx, "u1")
        HiddenStore.hideSong(ctx, "u2")
        HiddenStore.restoreSong(ctx, "u1")
        assertEquals(setOf("u2"), HiddenStore.hiddenSongs(ctx))
    }

    @Test
    fun `恢复文件夹只去掉那一个`() {
        HiddenStore.hideFolder(ctx, "A")
        HiddenStore.hideFolder(ctx, "B")
        HiddenStore.restoreFolder(ctx, "A")
        assertEquals(setOf("B"), HiddenStore.hiddenFolders(ctx))
    }

    @Test
    fun `恢复没隐藏过的东西不抛异常`() {
        HiddenStore.restoreSong(ctx, "不存在")
        HiddenStore.restoreFolder(ctx, "不存在")
        assertTrue(HiddenStore.isEmpty(ctx))
    }

    @Test
    fun `全部恢复清空两个名单`() {
        HiddenStore.hideSong(ctx, "u1")
        HiddenStore.hideFolder(ctx, "A")
        HiddenStore.restoreAll(ctx)
        assertTrue(HiddenStore.isEmpty(ctx))
        assertEquals(0, HiddenStore.hiddenSongs(ctx).size)
        assertEquals(0, HiddenStore.hiddenFolders(ctx).size)
    }

    // ---- 两类名单互不干扰 ----

    @Test
    fun `隐藏歌曲不影响文件夹名单 反之亦然`() {
        HiddenStore.hideSong(ctx, "u1")
        HiddenStore.hideFolder(ctx, "A")
        assertEquals(setOf("u1"), HiddenStore.hiddenSongs(ctx))
        assertEquals(setOf("A"), HiddenStore.hiddenFolders(ctx))
        HiddenStore.restoreSong(ctx, "u1")
        assertEquals(setOf("A"), HiddenStore.hiddenFolders(ctx))
    }

    // ---- 持久化与容错 ----

    @Test
    fun `数据跨实例读取保持`() {
        HiddenStore.hideSong(ctx, "u1")
        HiddenStore.hideFolder(ctx, "A/B")
        // 重新 load（模拟重启）
        assertEquals(setOf("u1"), HiddenStore.hiddenSongs(ctx))
        assertEquals(setOf("A/B"), HiddenStore.hiddenFolders(ctx))
    }

    @Test
    fun `存储文件损坏时返回空名单不抛异常`() {
        File(ctx.filesDir, "hidden.json").writeText("{ 这不是合法 JSON")
        assertTrue(HiddenStore.isEmpty(ctx))
    }

    @Test
    fun `文件不存在时返回空名单`() {
        assertTrue(HiddenStore.isEmpty(ctx))
    }

    @Test
    fun `名单里的空字符串被跳过`() {
        File(ctx.filesDir, "hidden.json")
            .writeText("""{"songs":["u1","","u2"],"folders":["","A"]}""")
        assertEquals(setOf("u1", "u2"), HiddenStore.hiddenSongs(ctx))
        assertEquals(setOf("A"), HiddenStore.hiddenFolders(ctx))
    }

    @Test
    fun `缺少 folders 字段时仍能读出歌曲`() {
        File(ctx.filesDir, "hidden.json").writeText("""{"songs":["u1"]}""")
        assertEquals(setOf("u1"), HiddenStore.hiddenSongs(ctx))
        assertEquals(0, HiddenStore.hiddenFolders(ctx).size)
    }

    // ---- 批量（2.15 批量删除）----

    @Test
    fun `批量隐藏一次写入全部生效`() {
        HiddenStore.hideSongs(ctx, listOf("u1", "u2", "u3"))
        assertEquals(setOf("u1", "u2", "u3"), HiddenStore.hiddenSongs(ctx))
    }

    @Test
    fun `批量隐藏与已有名单合并而非覆盖`() {
        HiddenStore.hideSong(ctx, "old")
        HiddenStore.hideSongs(ctx, listOf("a", "b"))
        assertEquals(setOf("old", "a", "b"), HiddenStore.hiddenSongs(ctx))
    }

    @Test
    fun `批量隐藏会去重`() {
        HiddenStore.hideSongs(ctx, listOf("u1", "u1", "u2"))
        assertEquals(2, HiddenStore.hiddenSongs(ctx).size)
    }

    @Test
    fun `批量隐藏跳过空字符串`() {
        HiddenStore.hideSongs(ctx, listOf("", "u1", ""))
        assertEquals(setOf("u1"), HiddenStore.hiddenSongs(ctx))
    }

    @Test
    fun `批量隐藏传空集合无副作用`() {
        HiddenStore.hideSongs(ctx, emptyList())
        assertTrue(HiddenStore.isEmpty(ctx))
    }

    @Test
    fun `批量隐藏不影响文件夹名单`() {
        HiddenStore.hideFolder(ctx, "A")
        HiddenStore.hideSongs(ctx, listOf("u1", "u2"))
        assertEquals(setOf("A"), HiddenStore.hiddenFolders(ctx))
        assertEquals(setOf("u1", "u2"), HiddenStore.hiddenSongs(ctx))
    }

    @Test
    fun `批量隐藏后可逐条恢复`() {
        HiddenStore.hideSongs(ctx, listOf("u1", "u2", "u3"))
        HiddenStore.restoreSong(ctx, "u2")
        assertEquals(setOf("u1", "u3"), HiddenStore.hiddenSongs(ctx))
    }
}
