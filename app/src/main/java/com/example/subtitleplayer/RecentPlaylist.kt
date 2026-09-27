package com.example.subtitleplayer

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 歌单最近播放时间记录（2.13）。
 *
 * 用途：音乐库页「按最近播放排序」需要知道每个歌单最后一次播放的时间。
 * 现有数据都不能用：
 * - `KEY_LAST_URI`（statePrefs）只记**一首**歌，且是单曲粒度；
 * - `per_song_positions` 记的是**播放进度**不是时间戳，且仅在「每首歌独立进度」开启时才有。
 * 所以单独建一份轻量记录：歌单名 -> 最后播放时间戳（毫秒）。
 *
 * 存储：应用私有目录 `playlist_recent.json`（整文件覆盖写，加锁 + 原子替换，
 * 与项目里其他缓存类保持同一套纪律）。
 */
object RecentPlaylist {

    private const val FILE_NAME = "playlist_recent.json"
    /** 只保留最近的一批歌单，避免文件无限增长。 */
    private const val MAX_ENTRIES = 500

    private val lock = Any()

    private fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    /** 读取「歌单名 -> 最后播放时间戳」。文件损坏/不存在都返回空表，不抛。 */
    fun load(c: Context): MutableMap<String, Long> {
        synchronized(lock) {
            return try {
                val f = file(c)
                if (!f.exists() || f.length() == 0L) return HashMap()
                val root = JSONObject(f.readText())
                val map = HashMap<String, Long>()
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

    /** 记下某歌单的播放时刻（单调取较大值，防止乱序写入把时间改早）。 */
    fun markPlayed(c: Context, playlistName: String, at: Long = System.currentTimeMillis()) {
        if (playlistName.isEmpty()) return
        synchronized(lock) {
            try {
                val map = load(c)
                val old = map[playlistName] ?: 0L
                if (at > old) map[playlistName] = at
                save(c, map)
            } catch (e: Exception) {
                // 记录失败不影响播放
            }
        }
    }

    /** 原子写：先写 .tmp 再改名，进程中途被杀不会留下半个 JSON。 */
    private fun save(c: Context, data: Map<String, Long>) {
        try {
            // 裁剪到最近 N 个歌单
            val trimmed = data.entries
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
            val json = JSONObject().apply {
                for ((k, v) in trimmed) put(k, v)
            }.toString()
            val f = file(c)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
        }
    }
}
