package com.example.subtitleplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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

    /** 封面背景遮罩透明度：70% 黑（深色底 + 浅色文字，任意封面上歌词都可读）。 */
    private const val COVER_DIM = 0xB3000000.toInt()

    /**
     * 生成封面背景 Drawable（2.12.1）：封面等比铺满裁剪 + 深色遮罩。
     * 与 [apply] 的区别：源图是内存里的封面 Bitmap，不是磁盘文件；且必须
     * centerCrop 铺满——封面多为正方形，按 FILL 拉伸会把画面压扁。
     * 裁剪矩阵在 Drawable 内部按实际 bounds 实时算：切页、旋转、软键盘
     * 引起的尺寸变化都不用重新生成 drawable，也不占额外位图内存。
     */
    fun coverDrawable(cover: Bitmap): Drawable = CoverBackgroundDrawable(cover)

    /**
     * 封面铺满裁剪参数（纯函数，便于 JVM 单测）。
     * @return FloatArray[scale, dx, dy]——先按 scale 等比缩放，再平移 (dx, dy)。
     *   scale 取 max 保证两个方向都盖满目标区（多余部分裁掉，绝不出现留白或拉伸变形）。
     */
    fun coverFitParams(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return floatArrayOf(1f, 0f, 0f)
        }
        val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        return floatArrayOf(
            scale,
            (dstW - srcW * scale) / 2f,
            (dstH - srcH * scale) / 2f
        )
    }

    private class CoverBackgroundDrawable(
        private val cover: Bitmap
    ) : Drawable() {

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = COVER_DIM }
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            if (cover.isRecycled || cover.width <= 0 || cover.height <= 0) return
            val p = coverFitParams(cover.width, cover.height, b.width(), b.height())
            matrix.setScale(p[0], p[0])
            matrix.postTranslate(b.left + p[1], b.top + p[2])
            canvas.drawBitmap(cover, matrix, bitmapPaint)
            canvas.drawRect(b, dimPaint)
        }

        override fun setAlpha(alpha: Int) {
            bitmapPaint.alpha = alpha
            dimPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bitmapPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.OPAQUE
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
