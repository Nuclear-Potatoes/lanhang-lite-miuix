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
import kotlin.math.abs
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

    /** 长按（松手前不触发单击）：图片查看器用它弹「保存图片」 */
    var onLongPress: (() -> Unit)? = null

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

    override fun onDetachedFromWindow() {
        cancelPendingClose()
        super.onDetachedFromWindow()
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    /**
     * 自己算双击，不用系统的 `onSingleTapConfirmed` + `onDoubleTap`（第二十四轮）。
     *
     * 系统的双击窗口是 `ViewConfiguration.getDoubleTapTimeout()`（300ms，不可改）：
     * 手慢一点、或者无障碍/自动化点击间隔大一点，第二下就落在窗口外 —— 第一下已经被判成
     * 「单击」把查看器关掉了，用户想要的是放大。这里改成 400ms 的宽窗口：
     * 第一下按下时挂一个「稍后关闭」，第二下只要在窗口内到达就取消关闭并切换缩放，
     * 手感更宽容，慢手也不会误退出。
     */
    private val closeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClose: Runnable? = null

    /** 双击是否落在放大态：放大过（双击 / 捏合）就还原原比例，否则放大 */
    private fun toggleZoom(fx: Float, fy: Float) {
        val zoomedIn = curScale > baseScale * 1.05f
        val target = if (zoomedIn) baseScale else baseScale * DOUBLE_TAP_SCALE
        animateScaleTo(target, fx, fy)
    }

    private fun cancelPendingClose() {
        pendingClose?.let { closeHandler.removeCallbacks(it) }
        pendingClose = null
    }

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (pendingClose != null) {
                    // 第二下（双击）：不关，改缩放，锚点用这一下的位置
                    cancelPendingClose()
                    toggleZoom(e.x, e.y)
                } else {
                    val r = Runnable {
                        pendingClose = null
                        onTap?.invoke()
                    }
                    pendingClose = r
                    closeHandler.postDelayed(r, DOUBLE_TAP_WINDOW_MS)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                cancelPendingClose()
                onLongPress?.invoke()
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

    /**
     * 平滑缩放到 [target]，以 ([fx], [fy]) 为锚点。
     *
     * 每帧的增量 = 本帧累计倍率 / 上一帧累计倍率，**累计倍率从起始倍率一次算出来**，
     * 不再「每帧 curScale *= f」：后者把浮点误差也乘进去，连续双击十几次后
     * 会偏出零点几个百分点，用户就会觉得「缩不回原比例」。
     */
    private fun animateScaleTo(target: Float, fx: Float, fy: Float) {
        if (!drawableReady) return
        val start = curScale
        val total = target / start
        if (abs(total - 1f) < 0.001f) return
        anim?.cancel()
        // 缩小时锚在视图中心：图变小后会被 clamp 居中，锚在指尖反而会「飘」
        val pivotX = if (total < 1f) width / 2f else fx
        val pivotY = if (total < 1f) height / 2f else fy
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            var applied = 1f
            addUpdateListener { v ->
                val p = v.animatedValue as Float
                val cum = 1f + (total - 1f) * p
                val step = cum / applied
                applied = cum
                matrixHelper.postScale(step, step, pivotX, pivotY)
                curScale = start * cum
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
        /** 双击放大到的倍数（相对「贴合视图」的比例） */
        private const val DOUBLE_TAP_SCALE = 2.5f

    /** 双击窗口：比系统的 300ms 宽一点，手慢也不会被误判成两次单击 */
    private const val DOUBLE_TAP_WINDOW_MS = 400L
    }
}
