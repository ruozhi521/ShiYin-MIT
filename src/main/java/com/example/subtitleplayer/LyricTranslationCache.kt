package com.example.subtitleplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 歌词译文本地缓存：uri -> { 行号 -> 译文 }。
 * 每首歌只翻译一次，重启不重复请求。
 */
object LyricTranslationCache {

    private const val FILE_NAME = "translations.json"

    fun load(context: Context): MutableMap<String, MutableMap<Int, String>> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return HashMap()
            val root = JSONObject(file.readText())
            val map = HashMap<String, MutableMap<Int, String>>()
            root.keys().forEach { uri ->
                val obj = root.getJSONObject(uri)
                val inner = HashMap<Int, String>()
                obj.keys().forEach { k -> inner[k.toInt()] = obj.getString(k) }
                map[uri] = inner
            }
            map
        } catch (e: Exception) {
            HashMap()
        }
    }

    fun save(context: Context, data: Map<String, Map<Int, String>>) {
        try {
            val root = JSONObject()
            for ((uri, inner) in data) {
                val o = JSONObject()
                for ((k, v) in inner) o.put(k.toString(), v)
                root.put(uri, o)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // 缓存失败不影响主流程
        }
    }
}
