package com.example.subtitleplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 把扫描结果缓存到应用私有目录，供「关闭启动时自动扫描」时秒开。
 * 缓存的 uri 是 SAF 持久化授权的文档 uri，权限仍在时可直接播放。
 */
object LibraryCache {

    private const val FILE_NAME = "library.json"

    /** 从 SAF 文档 uri 提取文件名（去扩展名），用于乱码标题回退。 */
    private fun stemFromUri(uriStr: String): String {
        val raw = uriStr.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        return stemOf(decoded)
    }

    fun save(context: Context, lib: MusicLibrary) {
        try {
            val songs = JSONArray()
            for (s in lib.allSongs) {
                songs.put(
                    JSONObject()
                        .put("title", s.title)
                        .put("uri", s.uri.toString())
                        .put("folder", s.folder)
                        .put("artist", s.artist)
                        .put("fileStem", s.fileStem)
                        .put("size", s.size)
                        // 3.0.6：修改时间要随缓存持久化，否则重启后「按修改时间排序」全变 0 失效
                        .put("lastModified", s.lastModified)
                )
            }
            val lyrics = JSONArray()
            for (ref in lib.lyrics.values) {
                lyrics.put(
                    JSONObject()
                        .put("name", ref.displayName)
                        .put("uri", ref.uri.toString())
                        .put("folder", ref.folder)
                )
            }
            val root = JSONObject()
                .put("songs", songs)
                .put("lyrics", lyrics)
            // 3.0.6：文件夹封面也要缓存（否则重启后文件夹封面丢，只剩内嵌封面）
            if (lib.folderCovers.isNotEmpty()) {
                val covers = JSONObject()
                for ((k, v) in lib.folderCovers) covers.put(k, v.toString())
                root.put("folderCovers", covers)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // 缓存失败不影响主流程
        }
    }

    fun load(context: Context): MusicLibrary? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())

            val songsArr = root.optJSONArray("songs") ?: return null
            val songs = mutableListOf<Song>()
            for (i in 0 until songsArr.length()) {
                val o = songsArr.getJSONObject(i)
                val title = cleanTag(o.optString("title", "")) ?: o.optString("fileStem", "")
                    .ifBlank { stemFromUri(o.getString("uri")) }
                songs.add(
                    Song(
                        title,
                        Uri.parse(o.getString("uri")),
                        o.getString("folder"),
                        o.optString("artist", ""),
                        o.optString("fileStem", ""),
                        // 旧缓存无 size 字段 → 0：单曲封面 key 退回纯文件名（与旧版兼容）
                        o.optLong("size", 0L),
                        // 旧缓存无 lastModified → 0：按修改时间排序时排最后，不乱序
                        o.optLong("lastModified", 0L)
                    )
                )
            }
            if (songs.isEmpty()) return null

            val lyricsArr = root.optJSONArray("lyrics") ?: JSONArray()
            val lyrics = mutableMapOf<String, LyricRef>()
            for (i in 0 until lyricsArr.length()) {
                val o = lyricsArr.getJSONObject(i)
                val folder = o.optString("folder", "")
                val ref = LyricRef(o.getString("name"), Uri.parse(o.getString("uri")), folder)
                val stem = stemOf(o.getString("name")).lowercase(Locale.getDefault())
                // 新格式带路径；旧缓存无 folder 时回退无路径 key（findLyric 全局唯一兜底兼容）
                val key = if (folder.isNotBlank()) "$folder/$stem" else stem
                lyrics[key] = ref
                val doubleStem = stemOf(stem)
                if (doubleStem != stem) {
                    val key2 = if (folder.isNotBlank()) "$folder/$doubleStem" else doubleStem
                    lyrics[key2] = ref
                }
            }

            val byFolder = songs.groupBy { it.folder }
            // ⚠️ 排序单独兜底：**排序失败绝不能等于「库是空的」**。
            //
            // 这段以前写成 `NameOrder.sortedByNames(...)` 直接放在大 try 里，
            // 而外层是 `catch (e: Exception) { null }` —— 只要排序里抛任何异常
            // （ICU 转写失败、内存不足……），整个 load() 就返回 null，
            // 界面表现就是「明明有歌却显示没有添加歌曲」。
            //
            // 排序只是展示顺序，失败了退回原始顺序即可，没有理由丢掉整个音乐库。
            fun <T> sortOrKeep(items: List<T>, nameOf: (T) -> String?): List<T> =
                try {
                    NameOrder.sortedByNames(items, nameOf)
                } catch (e: Throwable) {
                    PlaybackLog.log("cache sort failed (keep original order): ${e.message}")
                    items
                }

            val playlists = byFolder.map { (name, list) ->
                Playlist(name, sortOrKeep(list) { it.title })
            }.let { sortOrKeep(it) { p -> p.name } }

            // 3.0.6：文件夹封面（可选字段，旧缓存没有 → 空表）
            val covers = mutableMapOf<String, android.net.Uri>()
            root.optJSONObject("folderCovers")?.let { o ->
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.optString(k, "")
                    if (v.isNotBlank()) covers[k] = Uri.parse(v)
                }
            }

            MusicLibrary(
                playlists = playlists,
                allSongs = sortOrKeep(songs) { it.title },
                lyrics = lyrics,
                folderCovers = covers
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun stemOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
