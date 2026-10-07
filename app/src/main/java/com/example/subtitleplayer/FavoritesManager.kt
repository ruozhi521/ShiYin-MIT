package com.example.subtitleplayer

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 收藏管理：收藏的歌曲 uri 列表（有序）。
 *
 * ## 2.15 换存储 + 加缓存（粉丝反馈驱动）
 *
 * 粉丝反馈「新版本覆盖安装会把以前版本收藏的歌单清掉」，而**自建歌单在完全相同的场景下没丢**。
 * 两者存的都是歌曲 uri，唯一差别是存储：
 * - 自建歌单 → 应用私有目录的**原子写 JSON**（[CustomPlaylistStore]）
 * - 收藏 → **SharedPreferences**
 *
 * 所以 uri 没有失配（否则自建歌单也会空），问题出在 SharedPreferences 这一侧。旧实现有两处硬伤：
 *
 * 1. **读不出来就静默返回空表**（`catch { emptyList() }`）。SharedPreferences 由系统异步落盘、
 *    没有原子保证也没有 `.bak` 可退回；一旦读空，用户下一次收藏就把空表**固化写回**，收藏真的没了。
 *    这与 2.2 修 [LyricTranslationCache] 的是同一类问题（写坏 → 读空 → 把空固化）。
 * 2. 每次 `isFavorite()` 都重新 parse 一遍整个 JSON，而列表页是
 *    `allSongs.filter { isFavorite(...) }` —— 曲库上千首就 parse 上千次。这就是粉丝说的
 *    「点左边收藏歌单有明显卡顿，右边创建的歌单没有」：自建歌单只建一次 uri→Song 映射。
 *
 * 现改为与 [CustomPlaylistStore] / [LyricTranslationCache] 同一套纪律：
 * 整文件覆盖写 + 加锁 + 先写 .tmp 再改名 + 保留 .bak 回退；并加内存缓存让 `isFavorite` 变 O(1)。
 *
 * ## 兼容（老用户数据不能丢）
 * - **读**：JSON 文件可用就用它；否则回退旧 prefs，并**顺手迁移**到 JSON
 * - **写**：**双写**（JSON + prefs），两边互为备份，任一方损坏另一方仍完整
 * - JSON 文件存在时以它为准；「空数组」是合法状态（用户本就没收藏），不回退 prefs
 */
object FavoritesManager {

    private const val PREFS = "favorites"
    private const val KEY_LIST = "uris"
    private const val FILE_NAME = "favorites.json"
    private val lock = Any()

    /**
     * 内存缓存（2.15）。修「点开收藏卡顿」的关键：
     * `isFavorite` 从「每次 parse 整个 JSON」变成「查一次 Set」。
     */
    @Volatile
    private var cache: LinkedHashSet<String>? = null

    /** 收藏列表（有序：最近收藏的在最前）。 */
    fun list(c: Context): List<String> = synchronized(lock) { cached(c).toList() }

    fun isFavorite(c: Context, uri: String): Boolean =
        synchronized(lock) { cached(c).contains(uri) }

    /** 切换收藏状态，返回切换后是否已收藏。 */
    fun toggle(c: Context, uri: String): Boolean = synchronized(lock) {
        val cur = cached(c).toMutableList()
        val fav = if (cur.contains(uri)) {
            cur.remove(uri)
            false
        } else {
            cur.add(0, uri)
            true
        }
        save(c, cur)
        fav
    }

    fun remove(c: Context, uri: String) = synchronized(lock) {
        val cur = cached(c)
        if (!cur.contains(uri)) return@synchronized
        save(c, cur.filter { it != uri })
    }

    /**
     * 批量写入（2.15 批量删除用）。传当前应保留的完整列表。
     * 去重但保持顺序。
     */
    fun setAll(c: Context, uris: List<String>) = synchronized(lock) {
        save(c, uris.filter { it.isNotEmpty() }.distinct())
    }

    /** 取一份 Set 视图，供列表页一次性过滤（避免在 filter 里反复查缓存）。 */
    fun asSet(c: Context): Set<String> = synchronized(lock) { cached(c).toSet() }

    /** 清掉内存缓存（测试用；正常流程不需要）。 */
    internal fun invalidateCache() {
        cache = null
    }

    /** 惰性加载：优先 JSON，其次迁移旧 prefs。 */
    private fun cached(c: Context): LinkedHashSet<String> {
        cache?.let { return it }
        val fromJson = readJson(c)
        val loaded = fromJson ?: migrateFromPrefs(c)
        val set = LinkedHashSet(loaded)
        cache = set
        // 诊断（2.15）：粉丝反馈过「覆盖安装后收藏被清空」。记下来源与条数，
        // 下次复现时一眼能看出是「真读空」还是「读到了空表」。
        PlaybackLog.log(
            "favorites loaded: ${set.size} from " +
                (if (fromJson != null) "json" else "prefs(migrate)")
        )
        return set
    }

    /**
     * 读 JSON。返回 null 表示「两个文件都不可用」（不存在或损坏）——
     * 注意与「合法空数组」区分：后者返回 emptyList，不该回退 prefs。
     */
    private fun readJson(c: Context): List<String>? {
        for (name in arrayOf(FILE_NAME, "$FILE_NAME.bak")) {
            val f = File(c.filesDir, name)
            if (!f.exists() || f.length() == 0L) continue
            try {
                val arr = JSONArray(f.readText())
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "")
                    if (u.isNotEmpty()) out.add(u)
                }
                return out
            } catch (e: Exception) {
                // 该文件损坏 → 试下一份（.bak 是上一份好文件）
            }
        }
        return null
    }

    private fun readPrefs(c: Context): List<String> = try {
        val arr = JSONArray(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, "[]") ?: "[]"
        )
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.optString(i, "")
            if (u.isNotEmpty()) out.add(u)
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 老用户升级：JSON 还不存在时从 SharedPreferences 迁过来。
     *
     * 注意这里**没有把 prefs 清空**——留着当备份，双写会继续维护它。
     * 万一 JSON 写坏（磁盘满等），回退 prefs 仍能读到最后一份完整收藏。
     */
    private fun migrateFromPrefs(c: Context): List<String> {
        val old = readPrefs(c)
        if (old.isNotEmpty()) writeJson(c, old)
        return old
    }

    private fun save(c: Context, uris: List<String>) {
        // 先更新内存：即使落盘失败，本次会话的界面也是对的
        cache = LinkedHashSet(uris)
        writeJson(c, uris)
        writePrefs(c, uris)
    }

    /** 原子写：先写 .tmp 再改名，任何时刻磁盘上都有一个完整可解析的文件。 */
    private fun writeJson(c: Context, uris: List<String>) {
        try {
            val arr = JSONArray()
            uris.forEach { arr.put(it) }
            val json = arr.toString()
            val dir = c.filesDir
            val f = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            val bak = File(dir, "$FILE_NAME.bak")
            // 1) 现有好文件挪成 .bak（rename 原子；失败则复制兜底）
            if (f.exists()) {
                bak.delete()
                if (!f.renameTo(bak)) {
                    try {
                        bak.writeText(f.readText())
                    } catch (_: Exception) {
                        // 备份失败不影响主写入
                    }
                }
            }
            // 2) 写 .tmp 再改名
            tmp.writeText(json)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(json)
                tmp.delete()
            }
            // 诊断（2.15）：写入条数。若日志里出现「loaded: N」→「saved: M」的骤降，
            // 就是「读空后被固化」的现场。
            PlaybackLog.log("favorites saved: ${uris.size} (json)")
        } catch (e: Exception) {
            // 落盘失败不影响本次会话（内存缓存已是新值）
            PlaybackLog.log("favorites save json FAILED: ${e.javaClass.simpleName}")
        }
    }

    /** 双写旧 prefs：旧版本/旧路径仍读得到，且与 JSON 互为备份。 */
    private fun writePrefs(c: Context, uris: List<String>) {
        try {
            val arr = JSONArray()
            uris.forEach { arr.put(it) }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (e: Exception) {
            // 同上：不影响主流程
        }
    }
}
