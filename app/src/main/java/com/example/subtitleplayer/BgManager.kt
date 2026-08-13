package com.example.subtitleplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import java.io.File

/**
 * 自定义背景图管理：播放页/歌词页背景（图片 + 半透明遮罩合成 LayerDrawable）。
 * 图片复制到内部存储；遮罩 60% 黑保证文字在深浅主题下都清晰。
 */
object BgManager {

    private const val PREFS = "player"
    private const val KEY_BG = "bg_image"

    private fun bgFile(c: Context): File = File(c.filesDir, "bg.jpg")

    fun bgUri(c: Context): Uri? {
        val f = bgFile(c)
        return if (f.exists()) Uri.fromFile(f) else null
    }

    /** 复制用户选中的图片到内部存储，成功返回 true。 */
    fun setBg(c: Context, src: Uri): Boolean {
        return try {
            val target = bgFile(c)
            c.contentResolver.openInputStream(src)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearBg(c: Context) {
        bgFile(c).delete()
    }

    /** 给页面设置背景（图 + 遮罩）；uri 为 null 时恢复纯色背景。 */
    fun apply(view: View, uri: Uri?) {
        if (uri == null) {
            view.background = null
            return
        }
        try {
            val bmp = decodeScaled(
                uri.path ?: "",
                if (view.width > 0) view.width else view.resources.displayMetrics.widthPixels,
                if (view.height > 0) view.height else view.resources.displayMetrics.heightPixels
            )
            if (bmp == null) {
                view.background = null
                return
            }
            val layers = arrayOf<android.graphics.drawable.Drawable>(
                BitmapDrawable(view.resources, bmp),
                ColorDrawable(0x99000000.toInt()) // 60% 黑遮罩
            )
            view.background = LayerDrawable(layers)
        } catch (e: Exception) {
            view.background = null
        }
    }

    private fun decodeScaled(path: String, targetW: Int, targetH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW.coerceAtLeast(1) &&
                bounds.outHeight / (sample * 2) >= targetH.coerceAtLeast(1)
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }
}
