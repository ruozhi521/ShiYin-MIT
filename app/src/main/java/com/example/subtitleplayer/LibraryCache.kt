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
                songs.add(
                    Song(
                        o.getString("title"),
                        Uri.parse(o.getString("uri")),
                        o.getString("folder"),
                        o.optString("artist", ""),
                        o.optString("fileStem", "")
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
            val playlists = byFolder.map { (name, list) ->
                Playlist(name, list.sortedBy { it.title })
            }.sortedBy { it.name }

            MusicLibrary(
                playlists = playlists,
                allSongs = songs.sortedBy { it.title },
                lyrics = lyrics
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
