package com.example.subtitleplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 读取音频内嵌封面（MediaMetadataRetriever.embeddedPicture）。
 * 后台线程加载 + 简单内存缓存，避免重复解码。
 */
object CoverLoader {

    private val pool = Executors.newFixedThreadPool(2)
    private val cache = HashMap<String, Bitmap>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(context: Context, uri: Uri, targetSize: Int, callback: (Bitmap?) -> Unit) {
        val key = uri.toString()
        // 自定义单曲封面优先
        val custom = CoverManager.songCover(context, key)
        if (custom != null) {
            loadFile(context, custom, key, targetSize, callback)
            return
        }
        cache[key]?.let {
            callback(it)
            return
        }
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
                    // 高分辨率封面 embeddedPicture 可能返回 null：用 ID3v2 APIC 帧兜底
                    val pic = Id3LyricsParser.extractEmbeddedPicture(appContext, uri)
                    if (pic != null) decodeScaled(pic, targetSize) else null
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                synchronized(cache) { cache[key] = bmp }
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
                if (path != null) decodeFileScaled(path, targetSize) else null
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                synchronized(cache) { cache[cacheKey] = bmp }
            }
            mainHandler.post { callback(bmp) }
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

    /** 清除自定义封面的缓存（设置/清除封面后调用）。 */
    fun invalidate(cacheKey: String) {
        synchronized(cache) { cache.remove(cacheKey) }
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
