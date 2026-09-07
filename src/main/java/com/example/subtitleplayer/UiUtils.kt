package com.example.subtitleplayer

import android.content.Context
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import java.util.Locale

// ---------- 无状态 UI 工具（从 MainActivity 拆出）----------
// 顶层函数 / Context 扩展函数：MainActivity 内同名调用零改动（同包直接可见）。

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

fun formatTime(ms: Int): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

fun tagOf(rg: RadioGroup): Int =
    rg.findViewById<View>(rg.checkedRadioButtonId)
        ?.tag?.toString()?.toIntOrNull() ?: 0

fun checkByTag(rg: RadioGroup, value: Int) {
    for (i in 0 until rg.childCount) {
        val child = rg.getChildAt(i)
        if (child.tag?.toString()?.toIntOrNull() == value) {
            (child as? RadioButton)?.isChecked = true
            return
        }
    }
}
