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

    fun playlistCover(c: Context, name: String): Uri? {
        val obj = json(c, KEY_PLAYLISTS)
        obj.optString(name, "").ifEmpty { null }?.let { return Uri.parse(it) }
        // 兼容旧版本（1.24 前 folder 是目录名而非相对路径）：回退查最后一段
        val short = name.substringAfterLast('/')
        if (short != name) {
            obj.optString(short, "").ifEmpty { null }?.let { return Uri.parse(it) }
        }
        return null
    }

    fun songCover(c: Context, songUri: String): Uri? =
        json(c, KEY_SONGS).optString(songUri, "").ifEmpty { null }?.let { Uri.parse(it) }

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
        val dst = copyToInternal(c, src, "s_" + Integer.toHexString(songUri.hashCode()) + ".jpg")
        if (dst != null) {
            val obj = json(c, KEY_SONGS)
            obj.put(songUri, dst.toString())
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
        val uri = obj.optString(songUri, "")
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
        obj.put(songUri, internalUri.toString())
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
