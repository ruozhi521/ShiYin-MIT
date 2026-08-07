package com.example.subtitleplayer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 在 dispatchTouchEvent 层把触摸事件转发给 GestureDetector：
 * 无论子控件（CD 封面、RecyclerView 等）是否消费了触摸，
 * 从页面任意位置发起的滑动切换都始终有效。
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var gestureDetector: GestureDetector? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
