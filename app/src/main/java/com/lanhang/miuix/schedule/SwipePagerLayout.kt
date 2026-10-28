package com.lanhang.miuix.schedule

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 课表内容层的容器，唯一职责是**在子 View 之前**看到触摸事件，好让「左右滑动切周」在
 * 课程块 / 自定义表头上也能起手。
 *
 * 背景：以前把手势监听挂在 `gridScroll` 上，手指落在课程块上时事件被课程块的点击
 * 消费掉，`OnTouchListener` 根本收不到 —— 表现就是「有的地方能滑，有的地方划不动」。
 *
 * 约定：`pagerTouch` 返回 true 表示「这次事件归切周用」，容器会吃掉后续事件，
 * 并给子 View 补一个 ACTION_CANCEL（否则被中断的点击会残留按下态）。
 */
class SwipePagerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var pagerTouch: ((MotionEvent) -> Boolean)? = null

    private var consuming = false
    private var cancelSent = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handler = pagerTouch
        if (handler != null && handler(ev)) {
            if (!consuming) {
                consuming = true
                cancelSent = false
            }
            if (!cancelSent) {
                cancelSent = true
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                consuming = false
                cancelSent = false
            }
            return true
        }
        consuming = false
        cancelSent = false
        return super.dispatchTouchEvent(ev)
    }
}
