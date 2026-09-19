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
     * 单曲封面的稳定 key：文件名 + 文件大小（2.11）。
     * 2.0 用完整 uri 当 key——文件一移动封面就丢；2.1 改纯文件名——
     * 移动后不丢了，但不同文件夹的同名歌曲会共用同一封面（用户反馈的串图 bug）。
     * 现在用「文件名|大小」：移动文件不改内容所以大小不变，仍稳定；
     * 同名歌曲大小不同 → 各归各。size<=0（旧缓存/兜底）退回纯文件名，与 2.1 行为一致。
     */
    fun songKey(uri: String, size: Long = 0L): String {
        val seg = try {
            java.net.URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
        } catch (e: Exception) {
            uri.substringAfterLast('/')
        }
        val name = seg.substringAfterLast('/').substringBefore('?')
        return if (size > 0L) "$name|$size" else name
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

    fun songCover(c: Context, songUri: String, songSize: Long = 0L): Uri? {
        val obj = json(c, KEY_SONGS)
        // 稳定 key（文件名|大小）优先：移动文件、跨文件夹同名都不串
        val key = songKey(songUri, songSize)
        if (key.isNotEmpty()) {
            obj.optString(key, "").ifEmpty { null }?.let { return Uri.parse(it) }
        }
        // 兼容 2.1 旧数据（key 是纯文件名）：命中后迁移到带大小的 key，下次直接命中。
        // 只在 size>0 时迁移——size 未知时新旧 key 相同，迁移无意义。
        if (songSize > 0L) {
            val legacy = songKey(songUri)
            obj.optString(legacy, "").ifEmpty { null }?.let { url ->
                obj.put(key, url)
                saveJson(c, KEY_SONGS, obj)
                return Uri.parse(url)
            }
        }
        // 兼容更早数据（key 是完整 uri）：命中后迁移到稳定 key
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

    fun setSongCover(c: Context, songUri: String, songSize: Long, src: Uri): Uri? {
        val key = songKey(songUri, songSize)
        val dst = copyToInternal(c, src, "s_" + Integer.toHexString(key.hashCode()) + ".jpg")
        if (dst != null) {
            val obj = json(c, KEY_SONGS)
            obj.put(key, dst.toString())
            // 把 2.1 的纯文件名旧 key 清掉：否则其他文件夹的同名歌曲还会
            // 经「旧 key 兜底」读到这张封面（用户反馈的批量串图 bug）
            if (songSize > 0L) obj.remove(songKey(songUri))
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

    fun clearSongCover(c: Context, songUri: String, songSize: Long = 0L) {
        val obj = json(c, KEY_SONGS)
        val key = songKey(songUri, songSize)
        // 稳定 key、2.1 纯文件名 key、旧 uri key 都清掉，别留下会「复活」的残留
        val legacyName = songKey(songUri)
        val uri = obj.optString(key, "").ifEmpty {
            obj.optString(legacyName, "").ifEmpty { obj.optString(songUri, "") }
        }
        obj.remove(key)
        obj.remove(legacyName)
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
    fun setSongCoverInternal(c: Context, songUri: String, songSize: Long, internalUri: Uri) {
        val obj = json(c, KEY_SONGS)
        // 与 setSongCover 同用稳定 key（文件名|大小），移动文件、同名不串
        obj.put(songKey(songUri, songSize), internalUri.toString())
        // 同步清掉 2.1 的纯文件名旧 key，防止其他文件夹同名歌曲兜底读到这张封面
        if (songSize > 0L) obj.remove(songKey(songUri))
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
