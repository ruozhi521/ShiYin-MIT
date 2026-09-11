package com.example.subtitleplayer

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * 自定义封面管理：把用户选的图片复制到 App 私有目录（不依赖授权持久化），
 * 歌单按名称、单曲按歌曲 uri 映射。Photo Picker 返回的 content uri 授权不持久，
 * 所以必须复制一份到内部存储。
 */
object CoverManager {

    private const val PREFS = "covers"
    private const val KEY_PLAYLISTS = "pl"
    private const val KEY_SONGS = "song"

    private fun file(c: Context): File {
        val dir = File(c.filesDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun coversDir(c: Context): File {
        val dir = File(c.filesDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun json(c: Context, key: String): JSONObject = try {
        JSONObject(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "{}") ?: "{}"
        )
    } catch (e: Exception) {
        JSONObject()
    }

    private fun saveJson(c: Context, key: String, obj: JSONObject) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, obj.toString()).apply()
    }

    // ---------- 读取 ----------

    /**
     * 单曲封面的稳定 key：文件名（URL 解码后取最后一段）。
     * 原来直接用完整 uri 当 key——文件一旦被移动到别的文件夹，uri 就变了，
     * 封面随之丢失（用户反馈的「移动文件后封面没了」）。
     */
    fun songKey(uri: String): String {
        val seg = try {
            java.net.URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
        } catch (e: Exception) {
            uri.substringAfterLast('/')
        }
        return seg.substringAfterLast('/').substringBefore('?')
    }

    fun playlistCover(c: Context, name: String): Uri? {
        val obj = json(c, KEY_PLAYLISTS)
        obj.optString(name, "").ifEmpty { null }?.let { return Uri.parse(it) }
        // 兼容旧版本（1.24 前 folder 是目录名而非相对路径）：回退查最后一段
        val short = name.substringAfterLast('/')
        if (short != name) {
            obj.optString(short, "").ifEmpty { null }?.let { url ->
                // 文件夹换了父目录：按最后一段命中，顺手迁移到新的完整路径 key
                obj.put(name, url)
                saveJson(c, KEY_PLAYLISTS, obj)
                return Uri.parse(url)
            }
        }
        return null
    }

    fun songCover(c: Context, songUri: String): Uri? {
        val obj = json(c, KEY_SONGS)
        // 稳定 key（文件名）优先：文件被移动到别的文件夹后仍能命中
        val key = songKey(songUri)
        if (key.isNotEmpty()) {
            obj.optString(key, "").ifEmpty { null }?.let { return Uri.parse(it) }
        }
        // 兼容旧数据（key 是完整 uri）：命中后迁移到稳定 key，下次直接命中
        obj.optString(songUri, "").ifEmpty { null }?.let { url ->
            if (key.isNotEmpty() && key != songUri) {
                obj.put(key, url)
                saveJson(c, KEY_SONGS, obj)
            }
            return Uri.parse(url)
        }
        return null
    }

    // ---------- 写入 ----------

    fun setPlaylistCover(c: Context, name: String, src: Uri): Uri? {
        val dst = copyToInternal(c, src, "pl_" + Integer.toHexString(name.hashCode()) + ".jpg")
        if (dst != null) {
            val obj = json(c, KEY_PLAYLISTS)
            obj.put(name, dst.toString())
            saveJson(c, KEY_PLAYLISTS, obj)
        }
        return dst
    }

    fun setSongCover(c: Context, songUri: String, src: Uri): Uri? {
        val key = songKey(songUri)
        val dst = copyToInternal(c, src, "s_" + Integer.toHexString(key.hashCode()) + ".jpg")
        if (dst != null) {
            val obj = json(c, KEY_SONGS)
            obj.put(key, dst.toString())
            saveJson(c, KEY_SONGS, obj)
        }
        return dst
    }

    // ---------- 清除 ----------

    fun clearPlaylistCover(c: Context, name: String) {
        val obj = json(c, KEY_PLAYLISTS)
        val uri = obj.optString(name, "")
        obj.remove(name)
        saveJson(c, KEY_PLAYLISTS, obj)
        deleteFileIfInternal(c, uri)
    }

    fun clearSongCover(c: Context, songUri: String) {
        val obj = json(c, KEY_SONGS)
        val key = songKey(songUri)
        // 稳定 key 与旧 uri key 都清掉，别留下会「复活」的残留
        val uri = obj.optString(key, "").ifEmpty { obj.optString(songUri, "") }
        obj.remove(key)
        obj.remove(songUri)
        saveJson(c, KEY_SONGS, obj)
        deleteFileIfInternal(c, uri)
    }

    // ---------- 内部工具 ----------

    private fun copyToInternal(c: Context, src: Uri, fileName: String): Uri? {
        return try {
            val target = File(coversDir(c), fileName)
            val input = c.contentResolver.openInputStream(src) ?: run {
                android.util.Log.e("ShiYinCover", "copyToInternal openInputStream null src=$src")
                return null
            }
            input.use { ins ->
                target.outputStream().use { out -> ins.copyTo(out) }
            }
            // 复制失败检测：文件不存在或 0 字节视为失败，绝不写入坏引用
            if (!target.exists() || target.length() == 0L) {
                android.util.Log.e("ShiYinCover", "copyToInternal empty file $fileName")
                target.delete()
                return null
            }
            Uri.fromFile(target)
        } catch (e: Exception) {
            android.util.Log.e("ShiYinCover", "copyToInternal failed ${e.message}")
            null
        }
    }

    /** 批量封面：源图只复制一次，返回共享内部文件 uri（Photo Picker 的 content uri 多是一次性读取）。 */
    fun copySharedCover(c: Context, src: Uri): Uri? =
        copyToInternal(c, src, "shared_" + System.currentTimeMillis() + ".jpg")

    /** 直接用内部文件 uri 写入单曲封面映射（不重新复制）。 */
    fun setSongCoverInternal(c: Context, songUri: String, internalUri: Uri) {
        val obj = json(c, KEY_SONGS)
        // 与 setSongCover 同用稳定 key（文件名），移动文件后封面不丢
        obj.put(songKey(songUri), internalUri.toString())
        saveJson(c, KEY_SONGS, obj)
    }

    private fun deleteFileIfInternal(c: Context, uri: String) {
        if (uri.startsWith("file://")) {
            try {
                File(Uri.parse(uri).path ?: "").delete()
            } catch (_: Exception) {
            }
        }
    }
}
