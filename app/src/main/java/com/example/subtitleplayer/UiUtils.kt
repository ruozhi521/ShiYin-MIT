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

/**
 * 按用户设置决定封面的显示方式（3.0.5）。
 *
 * 背景：粉丝反馈「音频封面显示不全，好像是封面比较大的话就只显示中间一小块」。
 * 根因是**所有封面容器都是固定正方形 + `centerCrop`** —— 非 1:1 的封面必然被居中裁掉
 * 两侧或上下（解码链路没问题：见 CoverLoader，只做 inSampleSize 降采样，不改宽高比）。
 *
 * 所以给出开关（弱志 2026-10-30 选定方案 B：交给用户选）：
 * - `false`（默认，**裁满**）= `centerCrop`，保持观感与之前完全一致；
 * - `true`（**完整**）= `fitCenter`，整张图缩放进容器，不裁切；空出来的地方露出
 *   ImageView 的 background（渐变/纯色底），所以不会出现突兀的白块。
 *
 * ⚠️ 只应在**绑定 holder 时**调用一次，不要塞进每个 `setImageBitmap` 回调里 ——
 * 那会把同一个判断做很多遍，而且回调是异步的，容易漏。
 */
fun applyCoverScale(view: View, fullImage: Boolean) {
    val iv = view as? android.widget.ImageView ?: return
    val target = if (fullImage) {
        android.widget.ImageView.ScaleType.FIT_CENTER
    } else {
        android.widget.ImageView.ScaleType.CENTER_CROP
    }
    if (iv.scaleType != target) iv.scaleType = target
}

fun checkByTag(rg: RadioGroup, value: Int) {
    for (i in 0 until rg.childCount) {
        val child = rg.getChildAt(i)
        if (child.tag?.toString()?.toIntOrNull() == value) {
            (child as? RadioButton)?.isChecked = true
            return
        }
    }
}
