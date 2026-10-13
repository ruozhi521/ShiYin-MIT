package com.example.subtitleplayer

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 音乐库页「歌单」的展示顺序（3.0）。
 *
 * 为什么单独存：音乐库页的歌单是**扫描结果**（`all.groupBy { folder }` 后按名字排序），
 * 属于只读数据，没地方放用户拖动过的顺序。这里用一个独立的名字序列记录。
 *
 * 与 [CustomPlaylistStore] 的区别：那是**自建歌单内部**的歌曲顺序；
 * 本对象存的是**歌单与歌单之间**的排列顺序。两者互不干扰。
 *
 * 兼容策略（重要，避免升级后用户觉得"顺序丢了/歌单少了"）：
 * - 从没拖过 → 文件不存在 → [load] 返回空表 → [apply] 原样返回，**行为与 3.0 之前完全一致**；
 * - 拖过 → 按记录顺序排；**不在记录里的歌单**（之后新扫描出来的文件夹）按原序追加到末尾，不会丢；
 * - 记录里有、但扫描结果里没有的名字（文件夹被删或改名）自动跳过，**不做清理**——
 *   清理会让「临时把文件夹移走再移回」丢掉已经排好的顺序。
 *
 * 存储：应用私有目录 `playlist_order.json`，格式 `["歌单名", ...]`。
 * 整文件覆盖写 + 加锁 + 原子替换（先 .tmp 再改名），与 [CustomPlaylistStore]、
 * [RecentPlaylist]、[LyricTranslationCache] 保持同一套纪律。
 */
object PlaylistOrderStore {

    private const val FILE_NAME = "playlist_order.json"
    /** 顺序表长度上限，防文件无限增长。 */
    private const val MAX_NAMES = 500

    private val lock = Any()

    private fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    /** 读取用户保存的顺序。文件不存在或损坏都返回空表，不抛。 */
    fun load(c: Context): List<String> {
        synchronized(lock) {
            return try {
                val f = file(c)
                if (!f.exists() || f.length() == 0L) return emptyList()
                val arr = JSONArray(f.readText())
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i, "")
                    if (n.isNotEmpty()) out.add(n)
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** 覆盖保存顺序（拖拽结束后调用）。 */
    fun save(c: Context, names: List<String>) {
        synchronized(lock) {
            try {
                val arr = JSONArray()
                names.take(MAX_NAMES).forEach { arr.put(it) }
                val json = arr.toString()
                val f = file(c)
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // 保存失败不影响展示（下次拖动重新保存）
            }
        }
    }

    /**
     * 按保存的顺序重排 [lists]。
     *
     * 未记录的项按**原序**追加末尾（保持稳定，不重排）；记录里多出来的名字忽略。
     * 没保存过任何顺序时原样返回 —— 这是"不打扰老用户"的关键。
     */
    fun apply(c: Context, lists: List<Playlist>): List<Playlist> {
        if (lists.size <= 1) return lists
        val saved = load(c)
        if (saved.isEmpty()) return lists
        val byName = lists.associateBy { it.name }
        val out = ArrayList<Playlist>(lists.size)
        val used = HashSet<String>(lists.size)
        for (n in saved) {
            val p = byName[n] ?: continue
            if (used.add(n)) out.add(p)
        }
        for (p in lists) {
            if (used.add(p.name)) out.add(p)
        }
        return out
    }

    /**
     * 把 [current] 里第 [from] 项移到第 [to] 位，保存并返回新顺序。
     * 返回新列表供调用方立即同步界面数据源（与歌曲拖拽同一约定）。
     */
    fun moveAndSave(c: Context, current: List<String>, from: Int, to: Int): List<String> {
        if (from !in current.indices || to !in current.indices) return current
        val list = current.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        save(c, list)
        return list
    }
}
