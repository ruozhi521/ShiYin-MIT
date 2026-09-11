package com.example.subtitleplayer

import android.content.Context
import org.json.JSONArray

/**
 * 收藏管理：收藏的歌曲 uri 列表（有序），存 SharedPreferences JSON。
 * 收藏是"快捷歌单"，不修改文件夹歌单体系；uri 失效的收藏项读取时自动跳过。
 */
object FavoritesManager {

    private const val PREFS = "favorites"
    private const val KEY_LIST = "uris"

    fun list(c: Context): List<String> = try {
        val arr = JSONArray(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, "[]") ?: "[]"
        )
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun isFavorite(c: Context, uri: String): Boolean = list(c).contains(uri)

    /** 切换收藏状态，返回切换后是否已收藏。 */
    fun toggle(c: Context, uri: String): Boolean {
        val cur = list(c).toMutableList()
        val fav = if (cur.contains(uri)) {
            cur.remove(uri)
            false
        } else {
            cur.add(0, uri)
            true
        }
        save(c, cur)
        return fav
    }

    fun remove(c: Context, uri: String) {
        save(c, list(c).filter { it != uri })
    }

    private fun save(c: Context, uris: List<String>) {
        val arr = JSONArray()
        uris.forEach { arr.put(it) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
