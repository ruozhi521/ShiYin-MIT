package com.example.subtitleplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 首字母索引条（3.0.4，微信式触摸选中）。
 *
 * 为什么不像原来那样用一堆 TextView 排起来：
 * 1. 微信式的用法是**按住拖动**，手指一路划过字母实时跳转 —— 这需要把整个条当成
 *    一个连续的触摸区域处理，逐个 TextView 各自 `setOnClickListener` 做不到
 *    「手指在 A 上按下、滑到 M 才松手」这种连续手势；
 * 2. 拖动时还要给当前字母加**高亮**（不然不知道选到哪了，弱志 2026-10-30 的原话），
 *    以及由调用方弹出中央气泡 —— 自绘一个圆底比改 N 个 TextView 的背景更直接。
 *
 * 触摸位置 → 下标的换算是纯函数 [AlphaIndexRules.indexAt]，可单测（越界/空表/零高度）。
 */
class AlphaIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var letters: List<Char> = emptyList()
    private var selected = -1
    private var accentColor = 0xFF3F51B5.toInt()
    private var normalColor = 0xFF888888.toInt()

    /** 拖动经过某个字母时回调（下标 + 字母本身）。 */
    var onLetterChange: ((index: Int, letter: Char) -> Unit)? = null

    /** 按下 / 松开：调用方据此显示或隐藏中央气泡。 */
    var onTouchStateChange: ((active: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 单个字母条目的高度（dp）。14dp 在 26 个字母时约 364dp，放得进主流屏幕。 */
    private val itemHeightDp = 14f

    fun setLetters(list: List<Char>, accent: Int, normal: Int) {
        letters = list
        accentColor = accent
        normalColor = normal
        selected = -1
        // 字号随条目高度走，长列表时也不会挤在一起
        textPaint.textSize = 10f * resources.displayMetrics.scaledDensity
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (22f * density).toInt()
        val h = (itemHeightDp * density * letters.size).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val itemH = itemHeightPx()
        for (i in letters.indices) {
            val cy = itemH * i + itemH / 2f
            if (i == selected) {
                // 选中：主题色圆底 + 白字（微信通讯录里也是这一套）
                bgPaint.color = accentColor
                canvas.drawCircle(width / 2f, cy, itemH * 0.42f, bgPaint)
                textPaint.color = 0xFFFFFFFF.toInt()
            } else {
                textPaint.color = normalColor
            }
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(letters[i].toString(), width / 2f, baseline, textPaint)
        }
    }

    private fun itemHeightPx(): Float = itemHeightDp * density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val idx = AlphaIndexRules.indexAt(event.y, itemHeightPx(), letters.size)
                if (idx < 0) return true
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onTouchStateChange?.invoke(true)
                }
                if (idx != selected) {
                    selected = idx
                    invalidate()
                    onLetterChange?.invoke(idx, letters[idx])
                }
                // 让父容器别抢走后续 MOVE（否则滑两下就断）
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selected = -1
                invalidate()
                onTouchStateChange?.invoke(false)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
