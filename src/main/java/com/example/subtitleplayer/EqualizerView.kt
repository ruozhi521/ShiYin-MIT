package com.example.subtitleplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 均衡器滑块面板（复刻「选择音乐均衡器」截图 UI）：
 * 白色面板 + 水平 dB 刻度线 + 每频段一根渐变柱 + 蓝色圆形滑块 + 底部频率标签。
 * 频段数由设备决定（AudioFxManager.numberOfBands）。
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 各频段增益（dB）。 */
    var gains: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    /** 频段中心频率（Hz）。 */
    var freqs: IntArray = IntArray(0)

    /** 增益范围。 */
    var minDb: Float = -12f
    var maxDb: Float = 12f

    /** 自定义模式（可拖动）。 */
    var editable: Boolean = true

    /** 拖动回调。 */
    var onBandChanged: ((band: Int, gainDb: Float) -> Unit)? = null

    private val linePaint = Paint().apply {
        color = Color.parseColor("#bfbfbf")
        strokeWidth = dp(1f)
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#e0e0e0")
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#757575")
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint().apply {
        color = Color.parseColor("#1976d2")
        alpha = 60
    }
    private val knobPaint = Paint().apply {
        color = Color.parseColor("#1976d2")
        isAntiAlias = true
    }
    private val knobRing = Paint().apply {
        color = Color.parseColor("#1976d2")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }

    private val bottomLabelH = dp(22f)
    private val leftPad = dp(26f)
    private val rightPad = dp(8f)
    private val topPad = dp(10f)

    private var dragBand = -1

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = gains.size
        if (n == 0 || freqs.size != n) return

        val chartLeft = leftPad
        val chartRight = width - rightPad
        val chartTop = topPad
        val chartBottom = height - bottomLabelH
        val chartH = chartBottom - chartTop
        val range = (maxDb - minDb).coerceAtLeast(1f)

        // 水平刻度线：顶/中/底
        canvas.drawLine(chartLeft, chartTop, chartRight, chartTop, linePaint)
        canvas.drawLine(chartLeft, chartTop + chartH / 2f, chartRight, chartTop + chartH / 2f, linePaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, linePaint)
        // 中间再补两条细分线（±6dB 处）
        for (q in intArrayOf(1, 3)) {
            val y = chartTop + chartH * q / 4f
            canvas.drawLine(chartLeft, y, chartRight, y, axisPaint)
        }

        // 左标签：最大 / 0 / 最小
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatDb(maxDb), chartLeft - dp(6f), chartTop + sp(4f), labelPaint)
        canvas.drawText("0", chartLeft - dp(6f), chartTop + chartH / 2f + sp(4f), labelPaint)
        canvas.drawText(formatDb(minDb), chartLeft - dp(6f), chartBottom + sp(4f), labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER

        // 每频段：柱 + 滑块 + 垂直线
        val slot = (chartRight - chartLeft) / n
        for (i in 0 until n) {
            val cx = chartLeft + slot * i + slot / 2f
            val db = gains[i].coerceIn(minDb, maxDb)
            val y = valueToY(db, chartTop, chartH, range)

            // 垂直线
            canvas.drawLine(cx, chartTop, cx, chartBottom, axisPaint)
            // 渐变柱（从 0dB 线到滑块位置）
            val zeroY = valueToY(0f, chartTop, chartH, range)
            if (y != zeroY) {
                val top = Math.min(y, zeroY)
                val bottom = Math.max(y, zeroY)
                barPaint.alpha = 55
                canvas.drawRect(RectF(cx - slot / 2f + dp(4f), top, cx + slot / 2f - dp(4f), bottom), barPaint)
            }
            // 滑块（圆环 + 实心圆）
            val r = dp(9f)
            canvas.drawCircle(cx, y, r, knobRing)
            canvas.drawCircle(cx, y, r - dp(2.5f), knobPaint)

            // 频率标签
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(freqLabel(freqs[i]), cx, height - dp(6f), labelPaint)
        }
    }

    private fun valueToY(db: Float, top: Float, h: Float, range: Float): Float {
        val t = (db - minDb) / range
        return top + h * (1f - t)
    }

    private fun yToValue(y: Float, top: Float, h: Float, range: Float): Float {
        val t = 1f - ((y - top) / h).coerceIn(0f, 1f)
        return minDb + t * range
    }

    private fun formatDb(v: Float): String {
        val i = Math.round(v)
        return if (i > 0) "+$i" else "$i"
    }

    private fun freqLabel(hz: Int): String = when {
        hz >= 1000 -> "${hz / 1000}k"
        else -> "$hz"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable || gains.isEmpty()) return false
        val n = gains.size
        val chartLeft = leftPad
        val chartRight = width - rightPad
        val chartTop = topPad
        val chartBottom = height - bottomLabelH
        val chartH = chartBottom - chartTop
        val range = (maxDb - minDb).coerceAtLeast(1f)
        val slot = (chartRight - chartLeft) / n

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                var best = -1
                var bestDist = Float.MAX_VALUE
                for (i in 0 until n) {
                    val cx = chartLeft + slot * i + slot / 2f
                    val d = Math.abs(x - cx)
                    if (d < bestDist) {
                        bestDist = d
                        best = i
                    }
                }
                if (best >= 0 && bestDist < slot * 1.2f) {
                    dragBand = best
                    updateFromY(best, y, chartTop, chartH, range)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragBand >= 0) {
                    updateFromY(dragBand, event.y, chartTop, chartH, range)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragBand = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromY(band: Int, y: Float, top: Float, h: Float, range: Float) {
        val db = yToValue(y, top, h, range)
        gains[band] = db
        onBandChanged?.invoke(band, db)
        invalidate()
    }
}
