package com.example.subtitleplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「已隐藏的内容」名单（2.14）。
 *
 * 背景：弱志要求删除功能**不碰手机里的真实文件**。所以"删除"= 记进这份名单，
 * 让内容在界面里不再出现（曲库/搜索/歌单/树形/视频列表全部过滤）。
 *
 * 存两类：
 * - `songs`：歌曲 uri 列表（精确隐藏单曲）
 * - `folders`：文件夹路径列表（隐藏该文件夹**及全部子文件夹**，见 [HideRules]）
 *
 * 存储：应用私有目录 `hidden.json`，整文件覆盖写 + 加锁 + 原子替换
 * （与 `RecentPlaylist` / `CustomPlaylistStore` / `LyricTranslationCache` 同一套纪律）。
 *
 * **过滤发生在扫描结果上**，不在文件系统上——所以重新扫描后隐藏依然生效
 * （否则"删了下次扫描又回来"，用户会以为删除没用）。
 */
object HiddenStore {

    private const val FILE_NAME = "hidden.json"
    /** 上限，防文件无限增长。 */
    private const val MAX_SONGS = 20000
    private const val MAX_FOLDERS = 500

    private val lock = Any()

    private fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    /** 读取名单。损坏/不存在都返回空集，不抛。 */
    fun load(c: Context): Pair<Set<String>, Set<String>> {
        synchronized(lock) {
            return try {
                val f = file(c)
                if (!f.exists() || f.length() == 0L) return emptySet<String>() to emptySet()
                val root = JSONObject(f.readText())
                root.optJSONArray("songs")?.let { arr ->
                    val songs = HashSet<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { songs.add(it) }
                    }
                    val folders = HashSet<String>()
                    root.optJSONArray("folders")?.let { fa ->
                        for (i in 0 until fa.length()) {
                            fa.optString(i, "").takeIf { it.isNotEmpty() }?.let { folders.add(it) }
                        }
                    }
                    songs to folders
                } ?: (emptySet<String>() to emptySet())
            } catch (e: Exception) {
                emptySet<String>() to emptySet()
            }
        }
    }

    fun hiddenSongs(c: Context): Set<String> = load(c).first

    fun hiddenFolders(c: Context): Set<String> = load(c).second

    fun isEmpty(c: Context): Boolean {
        val (s, f) = load(c)
        return s.isEmpty() && f.isEmpty()
    }

    /** 隐藏一首歌（已存在则无变化）。 */
    fun hideSong(c: Context, uri: String) {
        if (uri.isEmpty()) return
        synchronized(lock) {
            val (s, f) = load(c)
            if (uri in s || s.size >= MAX_SONGS) return
            save(c, s + uri, f)
        }
    }

    /**
     * 批量隐藏（2.15 批量删除）。
     *
     * 一次写入而不是循环调 [hideSong]：每条都 load+save 一遍的话，
     * 选 50 首就要读写 50 次文件（还有 50 次原子替换），既慢又平白增加写坏的概率。
     */
    fun hideSongs(c: Context, uris: Collection<String>) {
        val add = uris.filter { it.isNotEmpty() }.toSet()
        if (add.isEmpty()) return
        synchronized(lock) {
            val (s, f) = load(c)
            val merged = s + add
            if (merged.size > MAX_SONGS) return
            save(c, merged, f)
        }
    }

    /** 隐藏一个文件夹（及全部子文件夹，见 [HideRules]）。 */
    fun hideFolder(c: Context, folder: String) {
        val n = folder.trim().trim('/')
        if (n.isEmpty()) return
        synchronized(lock) {
            val (s, f) = load(c)
            if (n in f || f.size >= MAX_FOLDERS) return
            save(c, s, f + n)
        }
    }

    /** 恢复一首歌。 */
    fun restoreSong(c: Context, uri: String) {
        synchronized(lock) {
            val (s, f) = load(c)
            if (uri !in s) return
            save(c, s - uri, f)
        }
    }

    /** 恢复一个文件夹。 */
    fun restoreFolder(c: Context, folder: String) {
        synchronized(lock) {
            val (s, f) = load(c)
            if (folder !in f) return
            save(c, s, f - folder)
        }
    }

    /** 全部恢复（清空名单）。 */
    fun restoreAll(c: Context) {
        synchronized(lock) {
            save(c, emptySet(), emptySet())
        }
    }

    /** 原子写：先写 .tmp 再改名，进程中途被杀不会留下半个 JSON。 */
    private fun save(c: Context, songs: Set<String>, folders: Set<String>) {
        try {
            val root = JSONObject().apply {
                put("songs", JSONArray().apply { songs.take(MAX_SONGS).forEach { put(it) } })
                put("folders", JSONArray().apply { folders.take(MAX_FOLDERS).forEach { put(it) } })
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
