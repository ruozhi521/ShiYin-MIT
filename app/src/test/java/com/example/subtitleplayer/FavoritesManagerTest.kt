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
 * 收藏存储测试（2.15）。
 *
 * 背景：粉丝反馈「新版本覆盖安装会把以前版本收藏的歌单清掉」。旧实现用 SharedPreferences
 * 单点存储，读失败静默返回空表——用户再收藏一次就把空表固化写回，收藏真丢。
 * 这里锁死新存储层的三条底线：
 * 1. **读不出来不能当成"没有收藏"**（否则会被固化写空）
 * 2. 老用户的 prefs 数据要能迁过来，且 prefs 保留作备份
 * 3. JSON 损坏时能退回 .bak
 *
 * Robolectric：需要 Context.filesDir 与 SharedPreferences。
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesManagerTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        File(ctx.filesDir, "favorites.json").delete()
        File(ctx.filesDir, "favorites.json.tmp").delete()
        File(ctx.filesDir, "favorites.json.bak").delete()
        prefs().edit().clear().commit()
        // 内存缓存必须清掉，否则用例之间会互相污染
        FavoritesManager.invalidateCache()
    }

    private fun prefs() = ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private fun jsonFile() = File(ctx.filesDir, "favorites.json")

    // ---- 基本读写 ----

    @Test
    fun `初始状态为空`() {
        assertTrue(FavoritesManager.list(ctx).isEmpty())
        assertEquals(0, FavoritesManager.asSet(ctx).size)
    }

    @Test
    fun `切换收藏后可读回`() {
        assertTrue(FavoritesManager.toggle(ctx, "u1"))
        assertTrue(FavoritesManager.isFavorite(ctx, "u1"))
        assertFalse(FavoritesManager.isFavorite(ctx, "u2"))
    }

    @Test
    fun `再次切换即取消收藏`() {
        FavoritesManager.toggle(ctx, "u1")
        assertFalse(FavoritesManager.toggle(ctx, "u1"))
        assertFalse(FavoritesManager.isFavorite(ctx, "u1"))
    }

    @Test
    fun `最新收藏排在最前`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.toggle(ctx, "u2")
        assertEquals(listOf("u2", "u1"), FavoritesManager.list(ctx))
    }

    @Test
    fun `remove 移除指定项`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.toggle(ctx, "u2")
        FavoritesManager.remove(ctx, "u1")
        assertEquals(listOf("u2"), FavoritesManager.list(ctx))
    }

    @Test
    fun `setAll 覆盖整表并去重保序`() {
        FavoritesManager.setAll(ctx, listOf("a", "b", "a", "c"))
        assertEquals(listOf("a", "b", "c"), FavoritesManager.list(ctx))
    }

    @Test
    fun `setAll 传空表即清空收藏（合法状态）`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.setAll(ctx, emptyList())
        assertTrue(FavoritesManager.list(ctx).isEmpty())
    }

    @Test
    fun `asSet 与 list 内容一致`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.toggle(ctx, "u2")
        assertEquals(FavoritesManager.list(ctx).toSet(), FavoritesManager.asSet(ctx))
    }

    // ---- 持久化（重新加载后仍在）----

    @Test
    fun `数据落在 JSON 文件里`() {
        FavoritesManager.toggle(ctx, "u1")
        assertTrue(jsonFile().exists())
    }

    @Test
    fun `清掉内存缓存后仍能从磁盘读回`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.toggle(ctx, "u2")
        FavoritesManager.invalidateCache()
        assertEquals(listOf("u2", "u1"), FavoritesManager.list(ctx))
    }

    @Test
    fun `双写 prefs 保证旧路径也读得到`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.invalidateCache()
        // 直接把 JSON 删掉，模拟「只有 prefs 可用」的旧环境
        jsonFile().delete()
        assertEquals(listOf("u1"), FavoritesManager.list(ctx))
    }

    // ---- 关键：读不出来 ≠ 没有收藏 ----

    @Test
    fun `JSON 损坏时退回 bak 而不是清空`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.toggle(ctx, "u2")   // 这一步会把上一份挪成 .bak
        // 主文件写坏
        jsonFile().writeText("{ 这不是合法 JSON")
        FavoritesManager.invalidateCache()
        val restored = FavoritesManager.list(ctx)
        assertTrue("应能从 .bak 或 prefs 恢复出内容，而不是空表", restored.isNotEmpty())
    }

    @Test
    fun `JSON 与 prefs 全损坏时才返回空`() {
        jsonFile().writeText("坏")
        prefs().edit().putString("uris", "也坏").commit()
        FavoritesManager.invalidateCache()
        assertTrue(FavoritesManager.list(ctx).isEmpty())
    }

    @Test
    fun `prefs 为空数组时迁移不产生收藏`() {
        prefs().edit().putString("uris", "[]").commit()
        FavoritesManager.invalidateCache()
        assertTrue(FavoritesManager.list(ctx).isEmpty())
    }

    // ---- 老用户迁移 ----

    @Test
    fun `老用户 prefs 数据会迁移到 JSON`() {
        prefs().edit().putString("uris", """["old1","old2"]""").commit()
        FavoritesManager.invalidateCache()
        assertEquals(listOf("old1", "old2"), FavoritesManager.list(ctx))
        assertTrue("迁移后应写出 JSON 文件", jsonFile().exists())
    }

    @Test
    fun `迁移后不清空 prefs（留作备份）`() {
        prefs().edit().putString("uris", """["old1"]""").commit()
        FavoritesManager.invalidateCache()
        FavoritesManager.list(ctx)
        assertFalse(
            "prefs 应保留作备份，否则 JSON 写坏就没退路了",
            prefs().getString("uris", "")!!.isEmpty()
        )
    }

    @Test
    fun `迁移时跳过空字符串`() {
        prefs().edit().putString("uris", """["a","","b"]""").commit()
        FavoritesManager.invalidateCache()
        assertEquals(listOf("a", "b"), FavoritesManager.list(ctx))
    }

    @Test
    fun `已有 JSON 时不再读 prefs`() {
        FavoritesManager.toggle(ctx, "new")
        // 往 prefs 塞旧数据：JSON 存在时不该被它覆盖
        prefs().edit().putString("uris", """["old"]""").commit()
        FavoritesManager.invalidateCache()
        assertEquals(listOf("new"), FavoritesManager.list(ctx))
    }

    @Test
    fun `JSON 是合法空数组时不回退 prefs`() {
        // 「用户把收藏全删了」是合法状态，不能被 prefs 里的旧数据"复活"
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.setAll(ctx, emptyList())
        prefs().edit().putString("uris", """["u1"]""").commit()
        FavoritesManager.invalidateCache()
        assertTrue(FavoritesManager.list(ctx).isEmpty())
    }

    // ---- 批量（批量删除用）----

    @Test
    fun `setAll 后 isFavorite 同步更新`() {
        FavoritesManager.toggle(ctx, "u1")
        FavoritesManager.toggle(ctx, "u2")
        FavoritesManager.setAll(ctx, listOf("u2"))
        assertFalse(FavoritesManager.isFavorite(ctx, "u1"))
        assertTrue(FavoritesManager.isFavorite(ctx, "u2"))
    }
}
