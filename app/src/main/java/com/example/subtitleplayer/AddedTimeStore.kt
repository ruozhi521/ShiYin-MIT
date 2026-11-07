package com.example.subtitleplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 歌曲「首次收录时间」记录（3.0.6）。
 *
 * 为什么需要它：B 站粉丝要求「按添加到媒体库时间排序」，但 Android 的文件系统里
 * **没有**这个概念 —— 文件只知道自己什么时候被修改。用户下载一首歌，修改时间≈收录时间，
 * 但**复制/移动**过来的老歌，其修改时间可能是几年前的。
 *
 * 所以这里在每次扫描时记一笔「第一次见到这首歌」的时间，只对新出现的 uri 写入，
 * 已有的保持不变（否则每次扫描都会把所有歌刷成"刚加入"）。
 *
 * 存储：应用私有目录 `added_times.json`，`{ "uri": 时间戳 }`。
 * 与 [RecentPlaylist] / [HiddenStore] 同一套纪律（整文件覆盖写 + 加锁 + 原子替换）。
 *
 * ⚠️ 上限 [MAX_ENTRIES]：按时间保留最近的一批。清理时**优先丢最老的**，
 * 那些多半已经不在库里了；真被丢掉的老歌只会退化成"收录时间未知"（排到最后），
 * 不会报错、也不会影响播放。
 */
object AddedTimeStore {

    private const val FILE_NAME = "added_times.json"
    private const val MAX_ENTRIES = 20000

    private val lock = Any()

    private fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    fun load(c: Context): MutableMap<String, Long> {
        synchronized(lock) {
            return try {
                val f = file(c)
                if (!f.exists() || f.length() == 0L) return HashMap()
                val root = JSONObject(f.readText())
                val map = HashMap<String, Long>(root.length())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = root.optLong(k, 0L)
                }
                map
            } catch (e: Exception) {
                HashMap()
            }
        }
    }

    /**
     * 为本次扫描到的歌补充收录时间：没有记录的记为 [now]（= 这次发现的），已有的不动。
     *
     * 一次落盘：扫描完调用一次，不是每首歌写一次。
     */
    fun markSeen(c: Context, uris: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (uris.isEmpty()) return
        synchronized(lock) {
            try {
                val map = load(c)
                var changed = false
                for (u in uris) {
                    if (u.isEmpty()) continue
                    if (map.containsKey(u)) continue
                    map[u] = now
                    changed = true
                }
                if (!changed) return
                if (map.size > MAX_ENTRIES) {
                    // 丢最老的一批（多半已经不在库里了）
                    val keep = map.entries.sortedByDescending { it.value }.take(MAX_ENTRIES)
                    map.clear()
                    for ((k, v) in keep) map[k] = v
                }
                save(c, map)
            } catch (e: Exception) {
                // 记录失败不影响扫描
            }
        }
    }

    private fun save(c: Context, map: Map<String, Long>) {
        try {
            val root = JSONObject()
            for ((k, v) in map) root.put(k, v)
            val json = root.toString()
            val f = file(c)
            val tmp = File(f.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(json)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 忽略
        }
    }
}
