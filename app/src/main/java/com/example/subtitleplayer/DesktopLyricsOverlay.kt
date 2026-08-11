package com.example.subtitleplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 桌面歌词悬浮窗：在其他应用上层显示当前歌词行。
 * 支持拖动（未锁定时）、字号、背景透明度、位置记忆。
 */
class DesktopLyricsOverlay(context: Context) {

    private val appContext = context.applicationContext
    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var visible = false

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "player"
        const val KEY_LOCKED = "desktop_lyrics_locked"
        const val KEY_SIZE = "desktop_lyrics_size"
        const val KEY_ALPHA = "desktop_lyrics_alpha"
        const val KEY_X = "desktop_lyrics_x"
        const val KEY_Y = "desktop_lyrics_y"

        /** 背景透明度档位对应的背景色（0 不透明 / 1 半透明 / 2 更透明）。 */
        fun bgColor(alphaLevel: Int): Int = when (alphaLevel) {
            1 -> (0x99 shl 24) or 0x1E1E1E
            2 -> (0x66 shl 24) or 0x1E1E1E
            else -> -0x00E1E1E2 // 0xFF1E1E1E
        }

        fun sizeSp(level: Int): Float = when (level) {
            1 -> 15f
            2 -> 22f
            else -> 18f
        }
    }

    fun isVisible(): Boolean = visible

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (visible) return
        if (!Settings.canDrawOverlays(appContext)) return
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(appContext)
        tv.text = appContext.getString(R.string.desktop_lyrics_ready)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(16), dp(8), dp(16), dp(8))
        tv.maxWidth = dp(560)
        applyStyle(tv)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs.getInt(KEY_X, 0)
        p.y = prefs.getInt(KEY_Y, 0)
        tv.setOnTouchListener(DragListener(tv, p))
        wm.addView(tv, p)
        this.wm = wm
        this.view = tv
        this.params = p
        visible = true
    }

    fun hide() {
        if (!visible) return
        try {
            wm?.removeView(view)
        } catch (e: Exception) {
            // view 可能已移除
        }
        wm = null
        view = null
        params = null
        visible = false
    }

    fun updateText(text: String) {
        if (!visible) return
        view?.text = text.ifEmpty { appContext.getString(R.string.desktop_lyrics_ready) }
    }

    /** 字号/透明度设置变更后刷新样式（悬浮窗已显示时）。 */
    fun refreshStyle() {
        view?.let { applyStyle(it) }
    }

    private fun applyStyle(tv: TextView) {
        tv.setTextSize(sizeSp(prefs.getInt(KEY_SIZE, 0)))
        tv.setBackgroundColor(bgColor(prefs.getInt(KEY_ALPHA, 1)))
    }

    private fun dp(v: Int): Int = (v * appContext.resources.displayMetrics.density).toInt()

    /** 拖动监听：锁定开关关闭时可拖动，松手记忆位置。 */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener(
        private val tv: TextView,
        private val p: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (prefs.getBoolean(KEY_LOCKED, false)) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        wm?.updateViewLayout(tv, p)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
                    }
                    moved = false
                    return true
                }
            }
            return false
        }
    }
}
