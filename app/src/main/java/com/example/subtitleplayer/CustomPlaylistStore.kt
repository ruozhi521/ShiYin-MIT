package com.example.subtitleplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义歌单（2.14）：用户自由组合的播放列表。
 *
 * 与「文件夹歌单」的区别：文件夹歌单是**扫描结果**（由目录结构决定，只读），
 * 这里是**用户手写**的集合，可以跨文件夹挑选单曲自由搭配（粉丝的建议）。
 *
 * 存储：应用私有目录 `custom_playlists.json`，格式 `{ "歌单名": ["uri", ...] }`。
 * 整文件覆盖写，加锁 + 原子替换——与 [RecentPlaylist] / [LyricTranslationCache]
 * 保持同一套纪律（写一半被杀不会留下半个 JSON）。
 *
 * 「收藏」不在这里：它是历史悠久的 [FavoritesManager]（SharedPreferences），
 * 迁移过来会动到老用户的收藏数据。列表页把它当固定第一项显示，
 * 两者各存各的，互不影响。
 */
object CustomPlaylistStore {

    private const val FILE_NAME = "custom_playlists.json"
    /** 歌单数上限，防文件无限增长。 */
    private const val MAX_PLAYLISTS = 200
    /** 单个歌单的歌曲数上限。 */
    private const val MAX_SONGS = 5000
    /** 歌单名长度上限（防止超长名把界面撑坏）。 */
    const val MAX_NAME_LEN = 40

    private val lock = Any()

    private fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    /** 读取全部自定义歌单（保持创建顺序）。文件损坏/不存在都返回空表，不抛。 */
    fun load(c: Context): LinkedHashMap<String, List<String>> {
        synchronized(lock) {
            val out = LinkedHashMap<String, List<String>>()
            try {
                val f = file(c)
                if (!f.exists() || f.length() == 0L) return out
                val root = JSONObject(f.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val arr = root.optJSONArray(name) ?: continue
                    val uris = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i, "")
                        if (u.isNotEmpty()) uris.add(u)
                    }
                    out[name] = uris
                }
            } catch (e: Exception) {
                // 解析失败按空表处理，不影响其它功能
            }
            return out
        }
    }

    /** 歌单名列表（创建顺序）。 */
    fun names(c: Context): List<String> = load(c).keys.toList()

    /** 某个歌单里的歌曲 uri 列表（顺序即用户排的顺序）。 */
    fun uris(c: Context, name: String): List<String> = load(c)[name] ?: emptyList()

    fun exists(c: Context, name: String): Boolean = load(c).containsKey(name)

    /**
     * 新建歌单。
     * @return false = 名字为空/过长/已存在
     */
    fun create(c: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > MAX_NAME_LEN) return false
        synchronized(lock) {
            val map = load(c)
            if (map.containsKey(n)) return false
            if (map.size >= MAX_PLAYLISTS) return false
            map[n] = emptyList()
            save(c, map)
            return true
        }
    }

    /**
     * 重命名歌单。
     * @return false = 原名不存在 / 新名非法 / 新名已被占用
     */
    fun rename(c: Context, old: String, newName: String): Boolean {
        val n = newName.trim()
        if (n.isEmpty() || n.length > MAX_NAME_LEN) return false
        if (n == old) return true
        synchronized(lock) {
            val map = load(c)
            val songs = map[old] ?: return false
            if (map.containsKey(n)) return false
            // 保持原有位置：重建一遍顺序
            val rebuilt = LinkedHashMap<String, List<String>>()
            for ((k, v) in map) {
                if (k == old) rebuilt[n] = songs else rebuilt[k] = v
            }
            save(c, rebuilt)
            return true
        }
    }

    /** 删除歌单（只删记录，不碰手机里的音频文件）。 */
    fun delete(c: Context, name: String) {
        synchronized(lock) {
            val map = load(c)
            if (map.remove(name) == null) return
            save(c, map)
        }
    }

    /**
     * 把一首歌加进歌单（去重）。
     * @return false = 歌单不存在 / 已经在里面 / 已满
     */
    fun add(c: Context, name: String, uri: String): Boolean {
        if (uri.isEmpty()) return false
        synchronized(lock) {
            val map = load(c)
            val cur = map[name] ?: return false
            if (cur.contains(uri)) return false
            if (cur.size >= MAX_SONGS) return false
            map[name] = cur + uri
            save(c, map)
            return true
        }
    }

    /** 从歌单移除一首歌。 */
    fun remove(c: Context, name: String, uri: String) {
        synchronized(lock) {
            val map = load(c)
            val cur = map[name] ?: return
            if (!cur.contains(uri)) return
            map[name] = cur.filter { it != uri }
            save(c, map)
        }
    }

    /**
     * 整表覆盖写（拖拽排序后保存新顺序用）。
     * 只接受已存在的歌单名，防止误写入凭空造歌单。
     */
    fun replaceSongs(c: Context, name: String, uris: List<String>) {
        synchronized(lock) {
            val map = load(c)
            if (!map.containsKey(name)) return
            map[name] = uris.take(MAX_SONGS)
            save(c, map)
        }
    }

    /** 原子写：先写 .tmp 再改名，进程中途被杀不会留下半个 JSON。 */
    private fun save(c: Context, data: Map<String, List<String>>) {
        try {
            val root = JSONObject()
            for ((name, uris) in data) {
                val arr = JSONArray()
                uris.take(MAX_SONGS).forEach { arr.put(it) }
                root.put(name, arr)
            }
            val json = root.toString()
            val f = file(c)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 保存失败不影响播放
        }
    }
}
