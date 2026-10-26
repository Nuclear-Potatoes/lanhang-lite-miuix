package com.lanhang.miuix.moments

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 图片预览的「上滑关闭」手势容器（2026-10-24 八次改版新增）。
 *
 * 只在**竖向拖动**且 [Listener.canDrag] 为真（当前图片没被放大）时才接管事件：
 * 一旦超过 touchSlop 且竖向分量占优就从 ViewPager2 手里抢过事件流
 * （ViewPager2 的横向翻页此时收到 CANCEL，不会和竖向手势打架），
 * 拖动过程交给宿主做跟手位移/缩放，抬手时由宿主决定「关闭」还是「回弹」。
 *
 * 为什么不用 `ViewPager2` 的 `setPageTransformer` 或第三方控件：项目红线是「优先原生实现、
 * 不新增第三方库」，而这里只需要一层 ViewGroup 拦截 + 宿主动画即可。
 */
class DragDismissLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        /** 当前这页能不能拖（图片已放大时要让位给平移手势） */
        fun canDrag(): Boolean

        fun onDragStart()

        /** dy 为相对按下点的竖向位移（px，上滑为负） */
        fun onDrag(dy: Float)

        /** 抬手：宿主根据位移/速度决定关闭或回弹 */
        fun onDragEnd(dy: Float, velocityY: Float)
    }

    var listener: Listener? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!dragging && listener?.canDrag() == true) {
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    // 竖向为主（1.2 倍容差）且超过 slop 才接管，避免误吞横向翻页
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                        dragging = true
                        listener?.onDragStart()
                        listener?.onDrag(dy)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                listener?.onDrag(ev.y - downY)
                return true
            }

            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                listener?.onDragEnd(ev.y - downY, vy)
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    listener?.onDragEnd(ev.y - downY, 0f)
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
