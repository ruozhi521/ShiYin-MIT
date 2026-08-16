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
 * 读取音频内嵌封面（MediaMetadataRetriever.embeddedPicture）。
 * 后台线程加载 + 简单内存缓存，避免重复解码。
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

    /** 常规封面链：内嵌 → APIC/FLAC → 专辑封面 → 歌单自定义封面兜底。 */
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
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(appContext, uri)
                val data = retriever.embeddedPicture
                retriever.release()
                if (data != null) {
                    decodeScaled(data, targetSize)
                } else {
                    // 高分辨率封面 embeddedPicture 可能返回 null：ID3v2 APIC / FLAC PICTURE 兜底
                    val pic = Id3LyricsParser.extractEmbeddedPicture(appContext, uri)
                        ?: Id3LyricsParser.extractFlacPicture(appContext, uri)
                    if (pic != null) {
                        decodeScaled(pic, targetSize)
                    } else {
                        // 专辑封面（文件夹 cover.jpg）兜底
                        albumArtFallback(appContext, uri, targetSize)
                            // 歌单自定义封面兜底：文件夹设置了封面，里面歌曲同样显示
                            ?: folder?.let { f ->
                                CoverManager.playlistCover(appContext, f)
                                    ?.path?.let { path -> decodeFileScaled(path, targetSize) }
                            }
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                synchronized(cache) { cache[cacheKey] = bmp }
            }
            mainHandler.post { callback(bmp) }
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
