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
                if (data != null) decodeScaled(data, targetSize) else null
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                synchronized(cache) { cache[key] = bmp }
            }
            mainHandler.post { callback(bmp) }
        }
    }

    private fun decodeScaled(data: ByteArray, target: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target &&
                bounds.outHeight / (sample * 2) >= target
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (e: Exception) {
            null
        }
    }
}
