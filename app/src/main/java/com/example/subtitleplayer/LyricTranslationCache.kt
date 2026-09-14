package com.example.subtitleplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 歌词译文本地缓存：uri -> { 行号 -> 译文 }。
 * 每首歌只翻译一次，重启不重复请求。
 *
 * 2.2 修复「重启后译文丢失」：
 * - 写入必须原子：先写 .tmp 再改名，进程中途被杀也不会留下半个 JSON
 *   （旧行为 writeText 直接覆盖，写一半被杀 → 文件损坏 → load 静默失败返回空，
 *   之后任何一次保存都会把"空"固化进文件，全部译文消失）
 * - 读写同一把锁：识别线程（ASR 实时翻译）与 UI 线程可能同时 save，交错写也会损坏文件
 * - 保留上一份好文件为 .bak：主文件损坏时自动回退，自愈而不是清零
 */
object LyricTranslationCache {

    private const val FILE_NAME = "translations.json"
    private val lock = Any()

    private fun parse(text: String): MutableMap<String, MutableMap<Int, String>> {
        val root = JSONObject(text)
        val map = HashMap<String, MutableMap<Int, String>>()
        root.keys().forEach { uri ->
            val obj = root.getJSONObject(uri)
            val inner = HashMap<Int, String>()
            obj.keys().forEach { k -> inner[k.toInt()] = obj.getString(k) }
            map[uri] = inner
        }
        return map
    }

    fun load(context: Context): MutableMap<String, MutableMap<Int, String>> {
        synchronized(lock) {
            // 主文件损坏时回退 .bak（上一份好文件），避免一次写坏就全丢
            for (name in arrayOf(FILE_NAME, "$FILE_NAME.bak")) {
                val file = File(context.filesDir, name)
                if (!file.exists()) continue
                try {
                    return parse(file.readText())
                } catch (e: Exception) {
                    // 该文件损坏：继续尝试下一份
                }
            }
            return HashMap()
        }
    }

    fun save(context: Context, data: Map<String, Map<Int, String>>) {
        synchronized(lock) {
            try {
                val json = JSONObject().apply {
                    for ((uri, inner) in data) {
                        val o = JSONObject()
                        for ((k, v) in inner) o.put(k.toString(), v)
                        put(uri, o)
                    }
                }.toString()
                val dir = context.filesDir
                val file = File(dir, FILE_NAME)
                val tmp = File(dir, "$FILE_NAME.tmp")
                val bak = File(dir, "$FILE_NAME.bak")
                // 1) 先把当前好文件挪成 .bak（rename 是原子的；失败则复制兜底）
                if (file.exists()) {
                    bak.delete()
                    if (!file.renameTo(bak)) {
                        try { bak.writeText(file.readText()) } catch (_: Exception) { }
                    }
                }
                // 2) 写 .tmp 再改名，任何时刻磁盘上都有一个完整可解析的文件
                tmp.writeText(json)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    // 个别文件系统 rename 跨情形失败：直接写兜底
                    file.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // 缓存失败不影响主流程
            }
        }
    }
}
