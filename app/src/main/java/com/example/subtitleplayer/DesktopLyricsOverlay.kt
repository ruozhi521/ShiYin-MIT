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
 * 支持拖动（未锁定时）、字号、背景透明度、文字颜色、位置记忆。
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
        const val KEY_COLOR = "desktop_lyrics_color"
        const val KEY_X = "desktop_lyrics_x"
        const val KEY_Y = "desktop_lyrics_y"

        /** 背景透明度档位对应的背景色（0 不透明 / 1 半透明 / 2 更透明 / 3 完全透明）。 */
        fun bgColor(alphaLevel: Int): Int = when (alphaLevel) {
            1 -> (0x99 shl 24) or 0x1E1E1E
            2 -> (0x66 shl 24) or 0x1E1E1E
            3 -> Color.TRANSPARENT
            else -> -0x00E1E1E2 // 0xFF1E1E1E
        }

        /** 文字颜色：-1 默认白色，其余为用户自选色值。 */
        fun textColor(saved: Int): Int = if (saved == -1) Color.WHITE else saved

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
        this.wm = wm
        this.view = tv
        this.params = p
        applyLockFlags()
        tv.setOnTouchListener(DragListener(tv, p))
        wm.addView(tv, p)
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
        applyLockFlags()
    }

    private fun locked(): Boolean = prefs.getBoolean(KEY_LOCKED, false)

    /**
     * 锁定状态同步到窗口触摸属性（1.31）：
     * 锁定时加 FLAG_NOT_TOUCHABLE，手指点在歌词上直接穿透给下层应用；
     * 解锁后恢复可触摸，才能重新拖动定位。
     */
    private fun applyLockFlags() {
        val p = params ?: return
        val tv = view ?: return
        val want = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (locked()) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        if (p.flags != want) {
            p.flags = want
            try {
                wm?.updateViewLayout(tv, p)
            } catch (e: Exception) {
                // 窗口可能已移除
            }
        }
    }

    private fun applyStyle(tv: TextView) {
        val alphaLevel = prefs.getInt(KEY_ALPHA, 1)
        tv.setTextSize(sizeSp(prefs.getInt(KEY_SIZE, 0)))
        tv.setBackgroundColor(bgColor(alphaLevel))
        tv.setTextColor(textColor(prefs.getInt(KEY_COLOR, -1)))
        if (alphaLevel >= 3) {
            // 全透明背景：加淡阴影保证浅色壁纸上仍可读
            val d = appContext.resources.displayMetrics.density
            tv.setShadowLayer(4 * d, d, d, 0x66000000)
        } else {
            // TextView 无 clearShadowLayer，用全零参数清除
            tv.setShadowLayer(0f, 0f, 0f, 0)
        }
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
