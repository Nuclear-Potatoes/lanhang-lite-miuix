package com.lanhang.miuix.moments

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 可缩放图片：双指捏合、拖动平移、双击 1x/2.5x 切换、单击回调（关闭）。
 * scaleType 固定 MATRIX，加载完 drawable 后调用 [reset] 贴合视图。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** 单击（onSingleTapConfirmed，双击不触发） */
    var onTap: (() -> Unit)? = null

    /** 是否处于放大状态：放大时要让位给平移手势，不允许上滑关闭（见 DragDismissLayout） */
    val isZoomed: Boolean
        get() = drawableReady && curScale > baseScale * 1.01f

    private val matrixHelper = Matrix()
    private var baseScale = 1f
    private var curScale = 1f
    private var drawableReady = false
    private var anim: ValueAnimator? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (curScale > baseScale * 1.4f) baseScale else baseScale * 2.5f
                animateScaleTo(target)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (curScale > baseScale && !scaleDetector.isInProgress) {
                    (parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    matrixHelper.postTranslate(-distanceX, -distanceY)
                    clamp()
                    applyMatrix()
                }
                return true
            }
        })

    fun reset() {
        val d = drawable ?: return
        if (d.intrinsicWidth <= 0 || width <= 0 || height <= 0) return
        baseScale = min(
            width.toFloat() / d.intrinsicWidth,
            height.toFloat() / d.intrinsicHeight
        )
        curScale = baseScale
        matrixHelper.reset()
        matrixHelper.postScale(curScale, curScale)
        clamp()
        drawableReady = true
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) reset()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        drawableReady = false
        if (drawable != null) post { reset() }
    }

    private fun zoom(factor: Float, fx: Float, fy: Float) {
        if (!drawableReady) return
        (parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
        val target = (curScale * factor).coerceIn(baseScale, baseScale * MAX_SCALE)
        val actual = target / curScale
        if (actual == 1f) return
        anim?.cancel()
        matrixHelper.postScale(actual, actual, fx, fy)
        curScale = target
        clamp()
        applyMatrix()
    }

    private fun animateScaleTo(target: Float) {
        if (!drawableReady) return
        val start = curScale
        val factor = target / start
        anim?.cancel()
        anim = ValueAnimator.ofFloat(1f, factor).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                matrixHelper.postScale(f, f, width / 2f, height / 2f)
                curScale *= f
                clamp()
                applyMatrix()
            }
            start()
        }
    }

    /**
     * 图片「fitCenter」后在**视图坐标系**里的矩形（缩放/位移都为 0 时）。
     * 共享元素动画（从缩略图放大 / 缩回缩略图）用它算起止缩放比。
     */
    /** drawable 已就绪（可安全 reset/取 baseScale） */
    fun hasDrawable(): Boolean = drawableReady && drawable != null

    fun fittedRect(out: RectF): Boolean {
        val d = drawable ?: return false
        if (!drawableReady || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return false
        if (width <= 0 || height <= 0) return false
        val w = d.intrinsicWidth * baseScale
        val h = d.intrinsicHeight * baseScale
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        out.set(l, t, l + w, t + h)
        return true
    }

    private fun applyMatrix() {
        imageMatrix = matrixHelper
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrixHelper.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) {
            dx = width / 2f - r.centerX()
        } else {
            if (r.left > 0) dx = -r.left
            if (r.right < width) dx = width - r.right
        }
        if (r.height() <= height) {
            dy = height / 2f - r.centerY()
        } else {
            if (r.top > 0) dy = -r.top
            if (r.bottom < height) dy = height - r.bottom
        }
        if (dx != 0f || dy != 0f) matrixHelper.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            (parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(curScale > baseScale)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // 必须始终吃下事件：ImageView 未置 clickable 时 super.onTouchEvent 返回 false，
        // 手势会被 ViewPager2 的 RecyclerView 接管，`onTap`（单击关闭）永远不触发。
        return true
    }

    companion object {
        private const val MAX_SCALE = 8f
    }
}
