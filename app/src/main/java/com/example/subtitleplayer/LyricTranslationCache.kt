package com.example.subtitleplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

typealias TranslationCacheStore =
    MutableMap<String, MutableMap<String, MutableMap<Int, String>>>

/**
 * 多引擎歌词译文缓存：provider -> uri -> { 行号 -> 译文 }。
 * 旧版本的 uri -> { 行号 -> 译文 } 会自动归入 openai，保持 AI 译文兼容。
 */
object LyricTranslationCache {

    private const val FILE_NAME = "translations.json"
    private const val LEGACY_PROVIDER = "openai"
    private val lock = Any()

    private fun parse(text: String): TranslationCacheStore {
        val root = JSONObject(text)
        val store: TranslationCacheStore = HashMap()
        val keys = root.keys()
        if (!keys.hasNext()) return store

        val firstKey = keys.next()
        val firstValue = root.optJSONObject(firstKey) ?: return store
        val firstInnerKey = firstValue.keys().let { if (it.hasNext()) it.next() else null }
        val isLegacy = firstInnerKey != null && firstInnerKey.toIntOrNull() != null
        if (isLegacy) {
            store[LEGACY_PROVIDER] = parseUriMap(root)
            return store
        }

        root.keys().forEach { provider ->
            val providerObject = root.optJSONObject(provider) ?: return@forEach
            store[provider] = parseUriMap(providerObject)
        }
        return store
    }

    private fun parseUriMap(root: JSONObject): MutableMap<String, MutableMap<Int, String>> {
        val map = HashMap<String, MutableMap<Int, String>>()
        root.keys().forEach { uri ->
            val obj = root.optJSONObject(uri) ?: return@forEach
            val inner = HashMap<Int, String>()
            obj.keys().forEach { key ->
                key.toIntOrNull()?.let { inner[it] = obj.optString(key) }
            }
            map[uri] = inner
        }
        return map
    }

    fun loadByProvider(context: Context): TranslationCacheStore {
        synchronized(lock) {
            for (name in arrayOf(FILE_NAME, "$FILE_NAME.bak")) {
                val file = File(context.filesDir, name)
                if (!file.exists()) continue
                try {
                    return parse(file.readText())
                } catch (_: Exception) {
                    // 主文件损坏时继续尝试 .bak。
                }
            }
            return HashMap()
        }
    }

    fun loadForProvider(context: Context, provider: String): MutableMap<String, MutableMap<Int, String>> =
        loadByProvider(context).getOrPut(provider) { HashMap() }

    fun saveByProvider(context: Context, data: TranslationCacheStore) {
        synchronized(lock) {
            try {
                val json = JSONObject().apply {
                    for ((provider, uriMap) in data) {
                        val providerObject = JSONObject()
                        for ((uri, inner) in uriMap) {
                            val lineObject = JSONObject()
                            for ((line, value) in inner) lineObject.put(line.toString(), value)
                            providerObject.put(uri, lineObject)
                        }
                        put(provider, providerObject)
                    }
                }.toString()
                val dir = context.filesDir
                val file = File(dir, FILE_NAME)
                val tmp = File(dir, "$FILE_NAME.tmp")
                val bak = File(dir, "$FILE_NAME.bak")
                if (file.exists()) {
                    bak.delete()
                    if (!file.renameTo(bak)) {
                        try { bak.writeText(file.readText()) } catch (_: Exception) { }
                    }
                }
                tmp.writeText(json)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
            } catch (_: Exception) {
                // 缓存失败不影响主流程。
            }
        }
    }
}
