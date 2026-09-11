package com.example.subtitleplayer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 在 dispatchTouchEvent 层自行识别水平/垂直滑动（不依赖系统手势检测器）：
 * 无论子控件（CD 封面、RecyclerView 等）是否消费触摸，从页面任意位置
 * 发起的滑动切换都有效；坐标全部使用相对本布局的本地坐标，避免全面屏
 * 状态栏/刘海导致的屏幕坐标换算偏差。
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /**
     * 水平滑动回调。
     * @param direction > 0 表示左滑，< 0 表示右滑
     * @param downYLocal 手指按下点相对本布局顶部的 Y 坐标（本地坐标系）
     */
    var onHorizontalSwipe: ((direction: Int, downYLocal: Float) -> Unit)? = null

    /**
     * 垂直滑动回调（1.33：播放页上下滑切歌）。
     * @param direction > 0 表示上滑（手指上移），< 0 表示下滑（手指下移）
     */
    var onVerticalSwipe: ((direction: Int) -> Unit)? = null

    private var lastX = 0f
    private var accX = 0f
    private var accY = 0f
    private var downYLocal = 0f
    private var active = false
    private var swiped = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                downYLocal = ev.y
                accX = 0f
                accY = 0f
                active = true
                swiped = false
                // 关键：强制消费 DOWN，让父级持续把 MOVE/UP 分发给本布局。
                // 否则按下点落在不消费触摸的区域（CD 封面等）时，DOWN 无 target，
                // 父级会拦截后续 MOVE，手势检测将永远收不到事件。
                super.dispatchTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active && !swiped) {
                    val dx = ev.x - lastX
                    lastX = ev.x
                    accX += dx
                    // 净垂直位移（相对按下点），横竖判定共用
                    accY = ev.y - downYLocal
                    // 水平位移 ≥ 60px 且明显水平主导（2 倍于垂直）即判定为横滑
                    if (Math.abs(accX) >= 60 && Math.abs(accX) >= Math.abs(accY) * 2) {
                        // accX > 0 = 手指右移（右滑），accX < 0 = 手指左移（左滑）
                        onHorizontalSwipe?.invoke(if (accX > 0) -1 else 1, downYLocal)
                        swiped = true
                    } else if (Math.abs(accY) >= 60 && Math.abs(accY) >= Math.abs(accX) * 2) {
                        // 垂直主导：上滑 = 下一首，下滑 = 上一首
                        onVerticalSwipe?.invoke(if (accY < 0) 1 else -1)
                        swiped = true
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                return super.dispatchTouchEvent(ev)
            }
            else -> return super.dispatchTouchEvent(ev)
        }
    }
}
