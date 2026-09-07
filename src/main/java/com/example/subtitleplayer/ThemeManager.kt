package com.example.subtitleplayer

import android.content.Context
import android.graphics.Color

/**
 * 主题色管理：预设色板 + 动态应用强调色（accent）。
 * 颜色存 "player" SharedPreferences（与主设置同文件）。
 */
object ThemeManager {

    private const val KEY_ACCENT = "accent_color"
    const val DEFAULT_ACCENT = 0xFF4A6CF7.toInt()

    /** 预设色板（8 色）。 */
    val PRESETS = intArrayOf(
        DEFAULT_ACCENT,          // 默认靛蓝
        0xFF1976D2.toInt(),      // 蓝
        0xFF0E9F6E.toInt(),      // 绿
        0xFFE0245E.toInt(),      // 红
        0xFFF59E0B.toInt(),      // 橙
        0xFF8B5CF6.toInt(),      // 紫
        0xFF06B6D4.toInt(),      // 青
        0xFFEC4899.toInt()       // 粉
    )

    fun accent(context: Context): Int =
        context.getSharedPreferences("player", Context.MODE_PRIVATE)
            .getInt(KEY_ACCENT, DEFAULT_ACCENT)

    fun save(context: Context, color: Int) {
        context.getSharedPreferences("player", Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACCENT, color).apply()
    }

    /** 强调色的暗化版本（译文、深色强调等）。 */
    fun accentDark(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * 0.82f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }
}
