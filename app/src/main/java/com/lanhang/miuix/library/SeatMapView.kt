package com.lanhang.miuix.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 座位图渲染（原生 Canvas，无第三方画布库）。
 *
 * 数据是厂商的**网格坐标**（`x/y/w/h` 单位=格，平面图原图 20px/格），
 * 所以这里的全部工作是：算一个「裁剪窗口」→ 按窗口把格子换算成像素 → 画背景平面图 + 画色块。
 *
 * 四个刻意的取舍：
 *
 * 1. **只画座位包围盒**，不画整张 133×66 的平面图：整图缩到屏宽后每个座位只有 5dp，
 *    根本点不中；裁到座位区（留 2 格余量）后再按最小时长放大，才能保证可点。
 * 2. **内容比视口大时在 View 内部平移**，不再靠外层 `HorizontalScrollView` 横滚：
 *    缩放之后需要**双向**平移，而 Android 没有原生双向滚动容器（横滚 + 竖滚嵌套会互相抢手势）。
 * 3. **平面图只是背景**：加载失败就退化成纯色底，色块与命中检测完全不依赖它。
 * 4. **初始不做任何缩放**：进页面看到的就是「宽度刚好、座位 ≥7dp」的完整视图，
 *    和加缩放之前完全一致；缩放是用户主动触发的。
 *
 * 手势（2026-10-26 按用户要求补上放大缩小）：
 * - **双指捏合**：按双指中心缩放，范围 `minScale`（最多缩到整图适配）～4×；
 * - **双击**：1× ↔ 2.5× 切换，锚点是双击位置；
 * - **单指拖动**：平移。**只在内容真的超出视口的方向上抢手势**——竖向还能滚页面时
 *   一律让给外层 `NestedScrollView`，否则手指落在图上页面就滚不动了；
 * - **单指点击**：选座。用 `onSingleTapConfirmed` 而不是 `onSingleTapUp`，
 *   双击放大时才不会顺手把座位选中。
 */
class SeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSeatTap: ((SeatPoi) -> Unit)? = null

    private var map: SeatMapData? = null
    private var plan: Bitmap? = null
    private var selectedId: String? = null

    /** 裁剪窗口（格子坐标） */
    private var cropLeft = 0
    private var cropTop = 0
    private var cropCols = 0
    private var cropRows = 0

    // ==================== 缩放与平移 ====================

    /** 一格在**内容坐标系**里的边长（不随缩放变），至少 7dp 保证可点 */
    private var baseCell = 0f
    private var contentW = 0f
    private var contentH = 0f

    /** 视口 = View 的实际大小；内容比它大就靠平移查看 */
    private var viewportW = 0f
    private var viewportH = 0f

    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 按下点（判断拖动方向用） */
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /** 本次拖动是否由本 View 处理（false 表示让给外层滚动容器） */
    private var panning = false

    private val radiusPx = dp(3f)

    private val planPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelSize = dp(8f)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = labelSize
    }

    // 配色由外部（Miuix 主题）注入：本类只管几何与手势，不读 Material 主题属性。
    // 默认值是 Miuix 亮色下的一组中性色，暗色由调用方覆盖（见 LibrarySeatScreen）。
    var freeFill = 0xFFF7F7F9.toInt()
    var takenFill = 0xFFE9E9EC.toInt()
    var closedFill = 0xFFEFF0F2.toInt()
    var freeStroke = 0xFFD7D8DC.toInt()
    var selectedFill = 0xFF3482FF.toInt()
    var onSelected = 0xFFFFFFFF.toInt()
    var planBackdrop = 0xFFF2F3F5.toInt()
    var takenText = 0xFF8A8A8E.toInt()

    private val seatRect = RectF()
    private val planSrc = Rect()
    private val planDst = Rect()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleAround(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                tapSeat(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击缩放：已经放大过就回到 1×，否则放大到 2.5×
                val target = if (scale > 1.05f) 1f else 2.5f
                scaleAround(target / scale, e.x, e.y)
                return true
            }
        }
    )

    fun setMap(data: SeatMapData?) {
        map = data
        selectedId = null
        plan = null
        // 换房间/换楼层时回到初始视图，不要把上一个房间的缩放带过来
        scale = 1f
        tx = 0f
        ty = 0f
        computeCrop()
        requestLayout()
        invalidate()
    }

    /** 平面图背景（可空：加载失败时退化成纯色底） */
    fun setPlanBitmap(bitmap: Bitmap?) {
        plan = bitmap
        invalidate()
    }

    fun selectedSeat(): SeatPoi? = map?.seats?.firstOrNull { it.id == selectedId }

    fun clearSelection() {
        if (selectedId == null) return
        selectedId = null
        invalidate()
    }

    private fun computeCrop() {
        val data = map
        if (data == null || data.seats.isEmpty()) {
            cropLeft = 0
            cropTop = 0
            cropCols = data?.width?.coerceAtLeast(1) ?: 1
            cropRows = data?.height?.coerceAtLeast(1) ?: 1
            return
        }
        // 座位包围盒 + 2 格余量（留出平面图里桌椅、隔断的上下文），再钳进整图范围
        val padding = 2
        val minX = data.seats.minOf { it.x } - padding
        val minY = data.seats.minOf { it.y } - padding
        val maxX = data.seats.maxOf { it.x + it.w } + padding
        val maxY = data.seats.maxOf { it.y + it.h } + padding
        val width = data.width.coerceAtLeast(1)
        val height = data.height.coerceAtLeast(1)
        cropLeft = minX.coerceIn(0, width - 1)
        cropTop = minY.coerceIn(0, height - 1)
        cropCols = (maxX.coerceAtMost(width) - cropLeft).coerceAtLeast(1)
        cropRows = (maxY.coerceAtMost(height) - cropTop).coerceAtLeast(1)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (map == null || cropCols <= 0 || cropRows <= 0) {
            setMeasuredDimension(
                resolveSize(dp(200f).roundToInt(), widthMeasureSpec),
                resolveSize(dp(160f).roundToInt(), heightMeasureSpec),
            )
            return
        }
        val available = MeasureSpec.getSize(widthMeasureSpec)
            .takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableH = MeasureSpec.getSize(heightMeasureSpec)
            .takeIf { it > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
        viewportW = available.toFloat()
        // 有高度约束时按「整张平面图都装得下」算基准格，**不设下限**：
        // Compose 的 AndroidView 不会把越界绘制裁掉，格子只要大一点点就会画到卡片外面去
        // （实测图例被座位盖住）。想看细节用双指放大，缩放上限 4×。
        // 没有高度约束（wrap_content）时才按宽度适配并托底 7dp。
        baseCell = if (availableH != null) {
            min(viewportW / cropCols, availableH.toFloat() / cropRows)
        } else {
            max(viewportW / cropCols, dp(7f))
        }
        contentW = baseCell * cropCols
        contentH = baseCell * cropRows
        // 视口 = 外面给的高度约束；没有约束（wrap_content）时就是内容高度
        viewportH = availableH?.toFloat() ?: contentH
        // 允许缩到「整图适配宽度」；已经比视口窄时就不必再小了
        minScale = min(1f, min(viewportW / contentW, viewportH / contentH))
        setMeasuredDimension(viewportW.roundToInt(), viewportH.roundToInt())
        clampOffsets()
    }

    override fun onDraw(canvas: Canvas) {
        val data = map ?: return
        if (cropCols <= 0 || cropRows <= 0 || baseCell <= 0f) return

        // 底色铺满视口：缩小或平移时边缘不能露出卡片的背景色
        canvas.drawColor(planBackdrop)

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)

        drawPlan(canvas, data)

        val cell = baseCell
        val originX = -cropLeft * cell
        val originY = -cropTop * cell

        val seatWidth = cell * 2 * scale
        val drawLabels = seatWidth >= dp(11f)
        for (seat in data.seats) {
            val left = originX + seat.x * cell + dp(0.6f)
            val top = originY + seat.y * cell + dp(0.6f)
            val right = originX + (seat.x + seat.w) * cell - dp(0.6f)
            val bottom = originY + (seat.y + seat.h) * cell - dp(0.6f)
            if (right <= left || bottom <= top) continue
            seatRect.set(left, top, right, bottom)

            val selected = seat.id == selectedId
            when {
                selected -> {
                    fillPaint.color = selectedFill
                    canvas.drawRoundRect(seatRect, radiusPx, radiusPx, fillPaint)
                }

                seat.isFree -> {
                    fillPaint.color = freeFill
                    canvas.drawRoundRect(seatRect, radiusPx, radiusPx, fillPaint)
                    strokePaint.color = freeStroke
                    canvas.drawRoundRect(seatRect, radiusPx, radiusPx, strokePaint)
                }

                seat.state == 2 -> {
                    fillPaint.color = closedFill
                    fillPaint.alpha = 110
                    canvas.drawRoundRect(seatRect, radiusPx, radiusPx, fillPaint)
                    fillPaint.alpha = 255
                }

                else -> {
                    fillPaint.color = takenFill
                    canvas.drawRoundRect(seatRect, radiusPx, radiusPx, fillPaint)
                }
            }

            if (drawLabels && seat.title.isNotEmpty()) {
                textPaint.color = if (selected) onSelected else takenText
                // 座号按格子宽收放：一格两座时（如「95 94」）固定 8dp 会糊成一团
                textPaint.textSize = min(labelSize, cell * seat.w * 0.46f)
                val baseline = seatRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(seat.title, seatRect.centerX(), baseline, textPaint)
            }
        }

        canvas.restore()
    }

    /** 把平面图的「裁剪窗口」区域画满整个内容区 */
    private fun drawPlan(canvas: Canvas, data: SeatMapData) {
        val bitmap = plan
        if (bitmap == null || data.width <= 0 || data.height <= 0) {
            fillPaint.color = planBackdrop
            canvas.drawRect(0f, 0f, contentW, contentH, fillPaint)
            return
        }
        // 厂商约定 20px/格，但实际图片尺寸可能不一致，按实际比例换算
        val scaleX = bitmap.width.toFloat() / data.width
        val scaleY = bitmap.height.toFloat() / data.height
        planSrc.set(
            (cropLeft * scaleX).roundToInt(),
            (cropTop * scaleY).roundToInt(),
            ((cropLeft + cropCols) * scaleX).roundToInt(),
            ((cropTop + cropRows) * scaleY).roundToInt(),
        )
        planDst.set(0, 0, contentW.roundToInt(), contentH.roundToInt())
        canvas.drawBitmap(bitmap, planSrc, planDst, planPaint)
    }

    // ==================== 手势 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (map == null || baseCell <= 0f) return false

        // 两个检测器先跑：双击在 DOWN 阶段就会触发，缩放需要连续的 MOVE
        var consumed = scaleDetector.onTouchEvent(event)
        consumed = gestureDetector.onTouchEvent(event) || consumed

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                panning = false
                consumed = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 变成双指后不再算单指拖动，缩放交给 scaleDetector
                dragging = false
                panning = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!dragging && (abs(event.x - downX) > touchSlop ||
                            abs(event.y - downY) > touchSlop)
                    ) {
                        dragging = true
                        // 只有这个方向真的能平移，才把父容器的滚动抢过来；
                        // 否则（比如图没放大、只想滚页面）就让 NestedScrollView 继续滚
                        panning = canPan(event.x - downX, event.y - downY)
                        if (panning) parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (panning) {
                        panBy(dx, dy)
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                panning = false
                // 无条件放开：双指缩放也会 disallow，这里忘了放开列表就再也滚不动
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return consumed
    }

    /** 这个方向的内容是否真的超出了视口（超出才值得平移） */
    private fun canPan(dx: Float, dy: Float): Boolean {
        val canHorizontal = contentW * scale > viewportW + 1f
        val canVertical = contentH * scale > viewportH + 1f
        return if (abs(dx) >= abs(dy)) canHorizontal else canVertical
    }

    private fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
        clampOffsets()
        invalidate()
    }

    /**
     * 以 ([focusX], [focusY]) 为锚点缩放：锚点下的内容点保持不动。
     * 不带动画——缩放要跟手，动画反而拖沓。
     */
    private fun scaleAround(factor: Float, focusX: Float, focusY: Float) {
        if (viewportW <= 0f || contentW <= 0f) return
        val newScale = (scale * factor).coerceIn(minScale, MAX_SCALE)
        if (newScale == scale) return
        val contentX = (focusX - tx) / scale
        val contentY = (focusY - ty) / scale
        scale = newScale
        tx = focusX - contentX * scale
        ty = focusY - contentY * scale
        clampOffsets()
        invalidate()
    }

    /** 平移量钳在边界内；内容小于视口时居中 */
    private fun clampOffsets() {
        if (viewportW <= 0f || viewportH <= 0f) return
        val scaledW = contentW * scale
        val scaledH = contentH * scale
        tx = if (scaledW <= viewportW) (viewportW - scaledW) / 2f else tx.coerceIn(viewportW - scaledW, 0f)
        ty = if (scaledH <= viewportH) (viewportH - scaledH) / 2f else ty.coerceIn(viewportH - scaledH, 0f)
    }

    /** 点击选座：先把触点逆变换回内容坐标，再按 3dp 容差命中 */
    private fun tapSeat(touchX: Float, touchY: Float) {
        val data = map ?: return
        if (cropCols <= 0 || cropRows <= 0 || baseCell <= 0f) return
        val cell = baseCell
        val x = (touchX - tx) / scale + cropLeft * cell
        val y = (touchY - ty) / scale + cropTop * cell
        // 容差按屏幕 dp 算，放大后不能跟着变宽松
        val slop = dp(3f) / scale
        val hit = data.seats.lastOrNull { seat ->
            x >= seat.x * cell - slop && x <= (seat.x + seat.w) * cell + slop &&
                y >= seat.y * cell - slop && y <= (seat.y + seat.h) * cell + slop
        } ?: return
        if (!hit.isFree) {
            onSeatTap?.invoke(hit)
            return
        }
        selectedId = if (selectedId == hit.id) null else hit.id
        invalidate()
        onSeatTap?.invoke(hit)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** 上限 4×：再大就只剩几个座位、失去位置感 */
        const val MAX_SCALE = 4f
    }
}
