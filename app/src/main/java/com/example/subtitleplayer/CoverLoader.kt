package com.example.subtitleplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.Executors

/**
 * 读取音频内嵌封面。
 * 链路：APIC/FLAC 字节级解析（安全）→ MMR embeddedPicture（仅非 MP3，防 JNI abort）→
 * MediaStore 专辑封面 → 歌单自定义封面。后台线程加载 + 简单内存缓存，避免重复解码。
 */
object CoverLoader {

    private val pool = Executors.newFixedThreadPool(2)
    private val cache = HashMap<String, Bitmap>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(
        context: Context,
        uri: Uri,
        targetSize: Int,
        folder: String? = null,
        callback: (Bitmap?) -> Unit
    ) {
        val key = uri.toString()
        val cacheKey = key + "#" + targetSize
        // 自定义单曲封面优先
        val custom = CoverManager.songCover(context, key)
        android.util.Log.d("ShiYinCover", "load uri=$key size=$targetSize custom=${custom?.toString() ?: "null"}")
        if (custom != null) {
            loadFile(context, custom, cacheKey, targetSize) { bmp ->
                if (bmp != null) {
                    callback(bmp)
                } else {
                    // 自定义封面读取失败（文件缺失/损坏）：回退常规封面链，避免完全无封面
                    loadEmbeddedFallback(context, uri, targetSize, folder, cacheKey, callback)
                }
            }
            return
        }
        cache[cacheKey]?.let {
            callback(it)
            return
        }
        loadEmbeddedFallback(context, uri, targetSize, folder, cacheKey, callback)
    }

    /**
     * 常规封面链：APIC/FLAC 字节级解析 → MMR 内嵌（仅非 MP3）→ 专辑封面 → 歌单自定义封面。
     * MP3 一律不走 MediaMetadataRetriever（乱码 metadata 会触发 native JNI abort，
     * 与 Scanner/ArtistLoader 同一套规避约定）；字节级解析对无 ID3 头的格式快速返回 null，开销可忽略。
     */
    private fun loadEmbeddedFallback(
        context: Context,
        uri: Uri,
        targetSize: Int,
        folder: String?,
        cacheKey: String,
        callback: (Bitmap?) -> Unit
    ) {
        val appContext = context.applicationContext
        pool.execute {
            val bmp = try {
                val pic = Id3LyricsParser.extractEmbeddedPicture(appContext, uri)
                    ?: Id3LyricsParser.extractFlacPicture(appContext, uri)
                if (pic != null) {
                    decodeScaled(pic, targetSize)
                } else if (isMp3(uri)) {
                    android.util.Log.d("ShiYinCover", "no apic/flac pic, skip mmr for mp3 uri=$uri")
                    null
                } else {
                    mmrEmbedded(appContext, uri, targetSize)
                }
                    // 专辑封面（文件夹 cover.jpg）兜底
                    ?: albumArtFallback(appContext, uri, targetSize)
                        // 歌单自定义封面兜底：文件夹设置了封面，里面歌曲同样显示
                        ?: folder?.let { f ->
                            CoverManager.playlistCover(appContext, f)
                                ?.path?.let { path -> decodeFileScaled(path, targetSize) }
                        }
            } catch (e: Exception) {
                android.util.Log.d("ShiYinCover", "embedded fallback error uri=$uri err=${e.message}")
                null
            }
            if (bmp == null) {
                android.util.Log.d("ShiYinCover", "cover not found uri=$uri folder=$folder")
            }
            if (bmp != null) {
                synchronized(cache) { cache[cacheKey] = bmp }
            }
            mainHandler.post { callback(bmp) }
        }
    }

    /** uri 指向的文件是否为 mp3（document uri 末段带扩展名；解码失败按不匹配处理）。 */
    private fun isMp3(uri: Uri): Boolean {
        return try {
            val seg = java.net.URLDecoder.decode(
                uri.lastPathSegment ?: return false, "UTF-8"
            ).lowercase()
            seg.substringBefore('?').endsWith(".mp3")
        } catch (e: Exception) {
            false
        }
    }

    /** MMR 提取内嵌封面：release 放 finally 防泄漏（泄漏会耗尽 fd 导致后续封面全部失败）。 */
    private fun mmrEmbedded(context: Context, uri: Uri, targetSize: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val data = retriever.embeddedPicture
            if (data != null) decodeScaled(data, targetSize) else null
        } catch (e: Exception) {
            android.util.Log.d("ShiYinCover", "mmrEmbedded failed uri=$uri err=${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    /** 加载本地图片文件（自定义封面用，file:// 直接 decodeFile，ContentResolver 读不了 file scheme），带缓存。 */
    fun loadFile(
        context: Context,
        fileUri: Uri,
        cacheKey: String,
        targetSize: Int,
        callback: (Bitmap?) -> Unit
    ) {
        cache[cacheKey]?.let {
            callback(it)
            return
        }
        pool.execute {
            val bmp = try {
                val path = fileUri.path
                android.util.Log.d("ShiYinCover", "loadFile path=$path exists=${path?.let { java.io.File(it).exists() }}")
                if (path != null) decodeFileScaled(path, targetSize) else null
            } catch (e: Exception) {
                null
            }
            android.util.Log.d("ShiYinCover", "loadFile result bmp=${bmp != null}")
            if (bmp != null) {
                synchronized(cache) { cache[cacheKey] = bmp }
            }
            mainHandler.post { callback(bmp) }
        }
    }

    /** 无内嵌封面时兜底：查 MediaStore 专辑封面（文件夹 cover.jpg / 同专辑共享封面）。 */
    private fun albumArtFallback(context: Context, uri: Uri, targetSize: Int): Bitmap? {
        return try {
            var albumId: Long = -1
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) albumId = c.getLong(0)
            }
            if (albumId < 0) return null
            var artPath: String? = null
            context.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
                MediaStore.Audio.Albums._ID + " = ?",
                arrayOf(albumId.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) artPath = c.getString(0)
            }
            if (artPath == null) return null
            decodeFileScaled(artPath, targetSize)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFileScaled(path: String, target: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target &&
                bounds.outHeight / (sample * 2) >= target
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** 清除缓存（设置/清除封面后调用；按前缀清除该 uri 的所有尺寸变体）。 */
    fun invalidate(cacheKey: String) {
        synchronized(cache) {
            val it = cache.keys.iterator()
            while (it.hasNext()) {
                if (it.next().startsWith(cacheKey)) it.remove()
            }
        }
    }

    private fun decodeScaled(data: ByteArray, target: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target &&
                bounds.outHeight / (sample * 2) >= target
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            try {
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            } catch (e: OutOfMemoryError) {
                // 内存不足时进一步降采样重试（高清封面兜底）
                opts.inSampleSize = sample * 2
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            }
        } catch (e: Exception) {
            null
        }
    }
}
