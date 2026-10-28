package com.lanhang.miuix.schedule

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.MainActivity
import com.lanhang.miuix.ui.miuix.isNightMode
import com.lanhang.miuix.ui.miuix.showMiuixDialog
import com.lanhang.miuix.ui.miuix.MiuixDialogSpec
import com.lanhang.miuix.ui.miuix.showMiuixPicker
import com.lanhang.miuix.ui.shell.ShellScroll
import com.lanhang.miuix.ui.shell.ShellState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

class ScheduleFragment : Fragment() {

    /** Miuix 头部（周次胶囊 + 网格/周视图分段控件），2026-10-24 取代四个 ImageButton */
    private lateinit var headerState: ScheduleHeaderState
    private lateinit var contentWeek: View
    private lateinit var contentGrid: View
    private lateinit var dayStrip: LinearLayout
    private lateinit var daySections: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    /** 当前配色令牌：只来自应用主题（浅色/深色） */
    private lateinit var style: ScheduleStyle

    private var currentWeek = 1
    private var maxWeek = 20
    private var startWeek = 1
    private var actualCurrentWeek = 1
    private var scheduleData: ScheduleData? = null
    /** 按周缓存的服务端数据（官方 GetSchedule&week=N 每周一份） */
    private val weekData = HashMap<Int, ScheduleData>()
    private var showingGrid = true  // 默认网格视图

    // 课程配色：HSL 黄金角算法（同课同色）
    private val courseColorMap = mutableMapOf<String, Int>()
    private lateinit var gridScroll: android.view.View
    private var gridRelayoutPending = false

    /** 换周/切视图过渡用的「旧内容快照」View 的标记（清理残留时按它识别） */
    private val GHOST_TAG = "schedule_swap_ghost"

    /** 课表内容层的诊断日志 tag（`adb logcat -s ScheduleUi`） */
    private val TAG = "ScheduleUi"

    /** 双击回顶的滚动动画；再次触发时先取消上一个 */
    private var scrollAnimator: android.animation.ValueAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerState = ScheduleHeaderState()
        contentWeek = view.findViewById(R.id.contentWeek)
        contentGrid = view.findViewById(R.id.contentGrid)
        dayStrip = view.findViewById(R.id.dayStrip)
        daySections = view.findViewById(R.id.daySections)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        // 覆盖层空态已废弃：空态改画在列表流里（renderWeekView 会在没有课程时补一个居中文案）。
        // 这个 TextView 是 contentWeek 里居中的覆盖层，一旦被点亮就会压在日程卡中间
        // （第四十六轮实测：第 6/7 周明明有 4 天课，「本周无课」却浮在周一日程卡上）。
        // 直接从视图树移除，之后任何路径都点不亮它。
        (tvEmpty.parent as? android.view.ViewGroup)?.removeView(tvEmpty)
        progress = view.findViewById(R.id.progress)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        // 刷新圆环的位置默认贴着内容顶端，会正好压在「第 N 周」胶囊上；
        // 往下挪到表头之下，出现时不挡住周次
        val dens = resources.displayMetrics.density
        swipeRefresh.setProgressViewOffset(false, (96 * dens).toInt(), (150 * dens).toInt())

        style = ScheduleStyle.fromTheme(requireContext())
        applyChromeStyle()

        val header = view.findViewById<androidx.compose.ui.platform.ComposeView>(R.id.scheduleHeader)
        header.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        header.setContent {
            com.lanhang.miuix.ui.miuix.LanhangMiuixTheme(
                darkTheme = androidx.compose.ui.platform.LocalContext.current.isNightMode()
            ) {
                ScheduleHeader(
                    state = headerState,
                    onPrev = { switchWeek(currentWeek - 1) },
                    onNext = { switchWeek(currentWeek + 1) },
                    onPickWeek = { showWeekPicker(header) },
                    onSelectView = { grid -> if (grid != showingGrid) switchView(grid) },
                    onToday = { switchWeek(actualCurrentWeek) },
                )
            }
        }
        swipeRefresh.setOnRefreshListener { refresh() }
        val weekScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.weekScroll)
        installWeekSwipe(view.findViewById(R.id.contentWeek))
        gridScroll = view.findViewById(R.id.gridScroll)
        installWeekSwipe(view.findViewById(R.id.contentGrid))

        // 2026-10-25 用户要求：课表也要像工作圈那样，往下滚时顶栏跟着收起/展开。
        // 工作圈/成绩/设置是 Compose 列表，用 ReportListScroll 上报；课表是自绘网格 +
        // NestedScrollView，所以这里直接从 View 的滚动位置上报同一套进度。
        weekScroll.setOnScrollChangeListener { _, _, y, _, _ ->
            ShellScroll.report(ShellState.TAB_SCHEDULE, y / BAR_COLLAPSE_SPAN_PX)
        }
        (gridScroll as? androidx.core.widget.NestedScrollView)?.setOnScrollChangeListener { _, _, y, _, _ ->
            ShellScroll.report(ShellState.TAB_SCHEDULE, y / BAR_COLLAPSE_SPAN_PX)
        }

        // 先读缓存再上样式：反过来的话首帧会拿「currentWeek 还是默认 1、数据还没进来」去渲染，
        // 屏幕闪一下空态提示（第十一轮排查时在 logcat 里看到的那次 renderGrid: 无数据）
        loadCacheOrRefresh()
        applyThemeStyle()
    }

    /**
     * 双击底栏「课表」：网格与周视图都滚回顶部。
     *
     * 课表不是 Compose 列表，所以不走 [com.lanhang.miuix.ui.shell.ScrollToTop] 信号，
     * 由 MainActivity 直接调用（见 `onTabDoubleTap`）。
     */
    fun scrollToTop() {
        scrollSlowly(gridScroll as? androidx.core.widget.NestedScrollView)
        scrollSlowly(view?.findViewById(R.id.weekScroll))
    }

    /**
     * 用 ValueAnimator 自己跑一条缓出曲线。
     *
     * `NestedScrollView.smoothScrollTo` 的时长由内部的 OverScroller 决定，
     * 距离一大就是「唰」一下到底（用户反馈太快），也调不了速度；
     * 自己插值就能按距离给 0.4~0.8 秒，跟 Compose 那几页的节奏对齐。
     */
    private fun scrollSlowly(sv: androidx.core.widget.NestedScrollView?) {
        if (sv == null) return
        val from = sv.scrollY
        if (from <= 0) return
        scrollAnimator?.cancel()
        val animator = android.animation.ValueAnimator.ofInt(from, 0)
        animator.duration = (380L + from / 4L).coerceIn(380L, 820L)
        animator.interpolator = android.view.animation.DecelerateInterpolator(1.7f)
        animator.addUpdateListener { sv.scrollTo(0, it.animatedValue as Int) }
        scrollAnimator = animator
        animator.start()
    }

    override fun onResume() {
        super.onResume()
        // 从「课表背景」设置返回后，背景/配色可能已变化
        applyThemeStyle()
    }

    /**
     * 刷新课表配色令牌：**只跟随应用主题**（浅色/深色），没有自定义背景这个概念。
     *
     * 历史：旧版本支持「课表背景」（图片/纯色/渐变），会按背景明暗推导正文色；
     * 推导出的正文色与底色接近时整页就是白字白底 —— 表现为「课表一片空白」。
     * 该功能已整体移除（见 README「已移除」一节），配色只有主题一个来源。
     */
    private fun applyThemeStyle() {
        val ctx = context ?: return
        style = ScheduleStyle.fromTheme(ctx)
        applyChromeStyle()
        renderAll()
    }

    /** 面板与头部配色随令牌刷新（头部是 Compose，靠 headerState 上的颜色覆盖） */
    private fun applyChromeStyle() {
        val density = resources.displayMetrics.density
        // 网格面板底色：**默认透明**（参照 NexioSchedule 主课表——网格直接铺在页面底色上，
        // 只有课程胶囊本身是实心块，没有大白色卡片框着）。设了自定义课表背景（图片/纯色）时
        // 才重新铺一层半透明面板，否则壁纸会透进课程网格里、字看不清。
        // 「课表背景」功能已移除：网格永远直接铺在页面底色上，不铺自定义半透明面板
        contentGrid.background = null
        tvEmpty.setTextColor(style.onBackgroundVariant)
        if (::headerState.isInitialized) {
            // 页头高亮文字/图标要用**可见**的主色：accentFill 是 colorSecondaryContainer（近白），
            // 用在「第 N 周」上会和页面底色融为一体（用户 2026-10-24 指出第 9 周看不见）
            headerState.accent = androidx.compose.ui.graphics.Color(style.accent)
            headerState.onAccent = androidx.compose.ui.graphics.Color(style.onAccentFill)
            headerState.container = androidx.compose.ui.graphics.Color(style.chipFill)
            headerState.onContainer = androidx.compose.ui.graphics.Color(style.onBackground)
        }
        updateWeekPill()
    }

    /**
     * 第三十六轮（用户录屏反馈「依旧严重闪动、拖影，两周的课表叠成一个反复切换」）。
     *
     * 把录屏逐帧拆开看出来的真凶有两个：
     *  1. **横向拖动时 SwipeRefreshLayout 被拉起来了** —— 圆环指示器正好出现在「第 N 周」胶囊上，
     *     它一边转圈一边触发刷新重绘，内容既被手指平移、又被刷新反复重画，就是「两周叠着乱闪」；
     *  2. `ACTION_CANCEL`（父控件把手势抢走时发的）被当成正常松手处理，`event.x` 归零后
     *     算出巨大位移，直接判成切周 —— 于是内容停在半路，周次和内容对不上。
     *
     * 现在三条硬改动：
     *  1. DOWN 就 `requestDisallowInterceptTouchEvent(true)` 占住手势，只有判定为**竖向**
     *     才交还给父控件（下拉刷新照旧可用）；判定为横向时顺手把 `swipeRefresh` 关掉；
     *  2. 整个手势期间只跟**两张静态位图**（旧周 + 新周）走，内容层子 View 全程 INVISIBLE：
     *     任何重绘都插不进动画中间，结构上不可能再叠影/闪动；
     *  3. `ACTION_CANCEL` 一律回弹、绝不切周；判过不过阈值只用最后一帧 MOVE 的位移。
     */

    /** 跟手切周的现场 */
    /** 网格视图的时间轴宽度（renderGrid 里算出来的 timeW + gapW） */
    private var gridAxisW = 0

    private var pagerGhost: ImageView? = null
    private var pagerNew: ImageView? = null
    private var pagerAxis: ImageView? = null
    private var pagerBase = -1
    private var pagerTarget = -1
    private var pagerDx = 0f
    private var pagerChromeOff = false
    /** 正在跑的收尾动画（第四十五轮：快速连滑时必须能就地取消，否则多条动画互相踩状态） */
    private var pagerAnim: android.animation.ValueAnimator? = null
    /** 手势序号：只有「当前这一段」的动画结果允许写状态，过期的结果一律作废 */
    private var pagerSeq = 0

    private fun installWeekSwipe(target: SwipePagerLayout) {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var horizontal = false

        target.pagerTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 上一段还在收尾动画里：**同步**收干净再起手（第四十五轮）。
                    // 以前这里调 finishPager(false)，那只是又起一条 210ms 动画，
                    // 字段要等它的 onAnimationEnd 才复位 —— 新手势就从「currentWeek 已改、
                    // pagerBase 还是旧值甚至已被复位成 -1」的状态起手（「第 -1 周」就是这么来的）。
                    if (pagerTarget >= 0) abortPager()
                    downX = event.x
                    downY = event.y
                    horizontal = false
                    pagerDx = 0f
                    activeContentLayer().parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!horizontal) {
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            horizontal = true
                            activeContentLayer().parent?.requestDisallowInterceptTouchEvent(true)
                            if (swipeRefresh.isEnabled) {
                                swipeRefresh.isEnabled = false
                                pagerChromeOff = true
                            }
                            val want = if (dx < 0f) currentWeek + 1 else currentWeek - 1
                            if (want in startWeek..maxWeek) startPager(want)
                        } else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                            activeContentLayer().parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    if (horizontal) {
                        pagerDx = dx
                        if (pagerTarget >= 0) applyPagerDrag(dx) else applyPagerDamp(dx)
                    }
                    horizontal
                }

                MotionEvent.ACTION_UP -> {
                    val wasHorizontal = horizontal
                    horizontal = false
                    if (pagerTarget >= 0) {
                        val width = (activeContentLayer().width.takeIf { it > 0 } ?: 1).toFloat()
                        val dx = if (pagerDx != 0f) pagerDx else event.x - downX
                        val dy = event.y - downY
                        val pass = abs(dx) > width * 0.2f && abs(dx) > abs(dy) * 1.2f
                        val forward =
                            if (dx < 0f) pagerTarget > pagerBase else pagerTarget < pagerBase
                        finishPager(pass && forward)
                        true
                    } else {
                        if (wasHorizontal) applyPagerDamp(0f)
                        restorePagerChrome()
                        wasHorizontal
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    horizontal = false
                    if (pagerTarget >= 0) finishPager(false) else applyPagerDamp(0f)
                    restorePagerChrome()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 左侧时间轴（节次 + 上下课时间）的像素宽度。
     *
     * 两种视图各有自己的轴：列表视图是 [daySections]，网格视图是 `gridContent` 里
     * `body` 的第一个子 View（`timeCol`）。切周时这一列**不该跟着滑**——它每周都一样，
     * 滑出去再滑回来看着就像坏了。
     */
    private fun axisWidthPx(): Int {
        val active = runCatching { activeContentLayer() }.getOrNull()
        val gridW = if (gridAxisW > 0) gridAxisW else dp(40)
        val listW = runCatching { daySections.left + daySections.width }.getOrDefault(0)
        val w = if (active === contentGrid) gridW else (if (listW > 0) listW else dp(40))
        return w
    }

    /**
     * 开始拖动。
     *
     * 这一版的硬约束（第三十八轮，用户第三次反馈闪动/拖影/颤动）：
     *  - **绝不在手势期间重绘**：拖动一开始就把内容层的子 View 全部 `INVISIBLE`，
     *    整个手势只跟三张静态位图走（钉住的时间轴 + 旧周日期列 + 新周日期列）；
     *  - **绝不手动 measure/layout**（上一轮就是被 `EXACTLY` 重新量了一次，画面被压扁）；
     *  - 新周的内容要等系统布局完才能拍，所以拍照放在 `post` 的下一帧里 ——
     *    在那之前旧周位图已经在跟手，用户感觉不到延迟；
     *  - 新周位图**从轴线右侧裁起**，这样滑进来的那一页不会把自己的时间轴也带进来
     *    （「两个时间轴一闪一闪」就是这么来的）。
     */
    private fun startPager(target: Int) {
        val layer = activeContentLayer()
        if (layer.width <= 0 || layer.height <= 0) return
        val content = activeContentScroll() ?: return
        val oldBmp = capturePagerBase(layer, content) ?: return
        pagerAnim?.cancel()
        pagerAnim = null
        pagerSeq++
        pagerBase = currentWeek
        pagerTarget = target
        pagerDx = 0f
        currentWeek = target
        renderAll() // 新周进内容层，交给系统正常布局；下一帧再拍照

        val ghost = ImageView(requireContext()).apply {
            setImageBitmap(oldBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
        }
        layer.addView(ghost, overlayParams(layer, layer.width, layer.height))
        pagerGhost = ghost

        val axisW = (content.left + axisWidthPx()).coerceIn(0, layer.width / 2)
        runCatching {
            if (axisW > 0) {
                val strip = Bitmap.createBitmap(oldBmp, 0, 0, axisW, oldBmp.height)
                val axis = ImageView(requireContext()).apply {
                    setImageBitmap(strip)
                    scaleType = ImageView.ScaleType.FIT_XY
                    isClickable = false
                    isFocusable = false
                }
                layer.addView(axis, overlayParams(layer, axisW, layer.height))
                pagerAxis = axis
            }
        }

        // 第四十五轮（用户：「课表页面左滑时，时间列会出现一个极短时间的拖影」）：
        // 以前要等 post 里拍完「新周」才把真实内容藏起来，于是**第一帧**就是
        // 「没动的真实内容（自带一条时间列）+ 已经跟手的旧周位图（也自带一条时间列）」两份叠着，
        // 一闪而过就是那条拖影。ghost 已经拍好了，这里立刻藏，拍新周时再临时放出来。
        hideContentChildren(layer)

        val seq = pagerSeq
        layer.post {
            if (seq != pagerSeq || pagerTarget < 0) return@post
            val w = layer.width
            val h = layer.height
            if (w <= 0 || h <= 0) return@post
            val ghostVis = pagerGhost?.visibility
            val axisVis = pagerAxis?.visibility
            pagerGhost?.visibility = View.INVISIBLE
            pagerAxis?.visibility = View.INVISIBLE
            showContentChildren(layer)   // 拍「新周」必须是真的内容在场
            val newBmp = capturePagerBase(layer, content)
            hideContentChildren(layer)   // 拍完立刻藏回去：之后任何重绘都插不进手势里
            pagerGhost?.visibility = ghostVis ?: View.VISIBLE
            pagerAxis?.visibility = axisVis ?: View.VISIBLE
            if (newBmp == null) return@post
            runCatching {
                val colsW = (w - axisW).coerceAtLeast(1)
                val view = ImageView(requireContext()).apply {
                    setImageBitmap(Bitmap.createBitmap(newBmp, minOf(axisW, newBmp.width - 1), 0, minOf(colsW, newBmp.width - axisW), newBmp.height))
                    scaleType = ImageView.ScaleType.FIT_XY
                    isClickable = false
                    isFocusable = false
                }
                layer.addView(view, overlayParams(layer, colsW, h))
                pagerNew = view
                pagerAxis?.bringToFront() // 新图后加，默认会盖住时间轴，提上来
                applyPagerDrag(pagerDx)
            }
        }
    }

    /** 跟手：旧周整页跟着手指走，新周（已裁掉时间轴）从隔壁屏走进来 */
    private fun applyPagerDrag(dx: Float) {
        val layer = activeContentLayer()
        val ghost = pagerGhost ?: return
        val dir = if (pagerTarget > pagerBase) 1f else -1f
        val w = layer.width.toFloat()
        val axisW = pagerAxis?.layoutParams?.width ?: 0
        val colsW = w - axisW
        ghost.translationX = dx
        pagerAxis?.translationX = 0f
        pagerNew?.translationX = axisW + dx + dir * colsW
    }

    /** 到头：只给一点阻尼，明确告诉用户「没有下一周了」 */
    private fun applyPagerDamp(dx: Float) {
        val layer = activeContentLayer()
        val ghost = pagerGhost
        if (ghost != null) {
            val dir = if (pagerTarget > pagerBase) 1f else -1f
            val w = layer.width.toFloat()
            val axisW = pagerAxis?.layoutParams?.width ?: 0
            ghost.translationX = dx * 0.35f
            pagerNew?.translationX = axisW + dx * 0.35f + dir * (w - axisW)
        } else {
            for (i in 0 until layer.childCount) {
                layer.getChildAt(i).translationX = dx * 0.35f
            }
        }
    }

    /**
     * 松手：完成或回弹。
     *
     * 第四十五轮：**把「这一次手势」的目标/基准/序号先快照下来**。
     * 以前 `onAnimationEnd` 里直接读 `pagerTarget` / `pagerBase`，而这两个字段可能已经被
     * 下一段手势改写、或者已经被 `cleanupPager` 复位成哨兵值 -1 —— 快速连滑两次就会出现
     * `currentWeek = -1`，即用户看到的「第 -1 周」。现在只有「当前这一段」的动画能写状态。
     */
    private fun finishPager(commit: Boolean) {
        val layer = activeContentLayer()
        val ghost = pagerGhost
        if (ghost == null) {
            cleanupPager()
            restorePagerChrome()
            pagerTarget = -1
            pagerBase = -1
            return
        }
        pagerAnim?.cancel()
        pagerAnim = null
        pagerSeq++
        val seq = pagerSeq
        val myTarget = pagerTarget
        val myBase = pagerBase
        val w = (layer.width.takeIf { it > 0 } ?: 1).toFloat()
        val axisW = (pagerAxis?.layoutParams?.width ?: 0).toFloat()
        val colsW = w - axisW
        val dir = if (myTarget > myBase) 1f else -1f
        val ghostFrom = ghost.translationX
        val ghostTo = if (commit) -dir * w else 0f
        val newFrom = axisW + ghostFrom + dir * colsW
        val newTo = if (commit) axisW else axisW + dir * colsW
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = if (commit) 190L else 210L
        anim.interpolator = android.view.animation.DecelerateInterpolator(1.7f)
        anim.addUpdateListener { va ->
            val f = va.animatedValue as Float
            ghost.translationX = ghostFrom + (ghostTo - ghostFrom) * f
            pagerNew?.translationX = newFrom + (newTo - newFrom) * f
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // 有更新的手势接管了：这次的结果作废，一个字段都不许写
                if (seq != pagerSeq) return
                cleanupPager()
                if (commit) {
                    updateWeekPill()
                    if (myTarget in startWeek..maxWeek) ensureWeekData(myTarget)
                } else {
                    if (myBase in startWeek..maxWeek) currentWeek = myBase
                    renderAll()
                    updateWeekPill()
                }
                pagerTarget = -1
                pagerBase = -1
                pagerDx = 0f
                pagerAnim = null
                restorePagerChrome()
            }
        })
        pagerAnim = anim
        anim.start()
    }

    /**
     * 同步收掉还没走完的切周（第四十五轮）。
     *
     * 与 [finishPager] 的区别：这里**不等动画**，当场清场、把 currentWeek 复原到手势开始的那一周。
     * 快速连滑时 ACTION_DOWN 撞上上一段收尾，必须走这一条。
     */
    private fun abortPager() {
        pagerAnim?.cancel()
        pagerAnim = null
        pagerSeq++
        val base = pagerBase
        cleanupPager()
        restorePagerChrome()
        pagerTarget = -1
        pagerBase = -1
        pagerDx = 0f
        if (base in startWeek..maxWeek && base != currentWeek) currentWeek = base
        renderAll()
        updateWeekPill()
    }

    /** 手势期间把真实内容藏起来（只留三张位图）；[showContentChildren] 成对恢复 */
    private fun hideContentChildren(layer: FrameLayout) {
        for (i in 0 until layer.childCount) {
            val child = layer.getChildAt(i)
            if (child === pagerGhost || child === pagerAxis || child === pagerNew) continue
            child.visibility = View.INVISIBLE
        }
    }

    private fun showContentChildren(layer: FrameLayout) {
        for (i in 0 until layer.childCount) {
            val child = layer.getChildAt(i)
            if (child === pagerGhost || child === pagerAxis || child === pagerNew) continue
            child.visibility = View.VISIBLE
        }
    }

    /** 清场：去掉所有位图，位移归零、内容层恢复显示 */
    private fun cleanupPager() {
        val layer = activeContentLayer()
        pagerGhost?.let { runCatching { layer.removeView(it) } }
        pagerGhost = null
        pagerNew?.let { runCatching { layer.removeView(it) } }
        pagerNew = null
        pagerAxis?.let { runCatching { layer.removeView(it) } }
        pagerAxis = null
        for (i in 0 until layer.childCount) {
            val child = layer.getChildAt(i)
            child.translationX = 0f
            child.visibility = View.VISIBLE
        }
    }

    /** 把临时关掉的下拉刷新 / 父控件拦截开关恢复 */
    private fun restorePagerChrome() {
        if (pagerChromeOff) {
            swipeRefresh.isEnabled = true
            pagerChromeOff = false
        }
        activeContentLayer().parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** 数据固定在本地：有缓存直接显示，不自动联网；只有手动下拉刷新才重新拉取覆盖 */
    private fun loadCacheOrRefresh() {
        val cached = ScheduleRepository.loadCached()
        if (cached?.data != null) {
            scheduleData = cached.data
            applyWeekInfo(cached.data)
            ScheduleRepository.loadCachedWeek(actualCurrentWeek)?.data?.let {
                weekData[actualCurrentWeek] = it
            }
            renderAll()
        } else {
            progress.visibility = View.VISIBLE
            refresh()
        }
    }

    /**
     * @param keepBrowseWeek true = 只更新「本学期第几周」这条信息，**不动用户正在看的周**。
     *
     * 第四十五轮（用户：「下拉刷新时如果展示的不是本周，周次栏会短时间闪到本周又跳回来」）：
     * 以前这里无条件 `currentWeek = it` 并顺手 `updateWeekPill()`，于是刷新的第一帧周次栏
     * 先被改成本周（用户看到闪一下），后面 `refresh()` 再复原 —— 闪动就是这么来的。
     */
    private fun applyWeekInfo(data: ScheduleData, keepBrowseWeek: Boolean = false) {
        data.weekInfo?.let { info ->
            info.currentWeek?.toIntOrNull()?.let {
                actualCurrentWeek = it
                if (!keepBrowseWeek) currentWeek = it
            }
            info.endWeek?.toIntOrNull()?.let { maxWeek = it }
            info.startWeek?.toIntOrNull()?.let { startWeek = it }
        }
        if (!keepBrowseWeek) updateWeekPill()
    }

    /** 手动下拉刷新：从教务系统重新拉取并覆盖本地缓存；刷新期间保持当前浏览周不变 */
    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val browseWeek = currentWeek
                val first = withContext(Dispatchers.IO) { ScheduleRepository.refresh(null) }
                scheduleData = first.data
                // 只更新「本学期第几周」，绝不把浏览周打回本周（否则周次栏会闪一下再跳回来）
                first.data?.let { applyWeekInfo(it, keepBrowseWeek = true) }
                currentWeek = browseWeek
                if (browseWeek == actualCurrentWeek) {
                    first.data?.let { weekData[browseWeek] = it }
                } else {
                    // 浏览的不是当前实际周：再拉一次浏览周数据覆盖该周缓存
                    try {
                        val byWeek = withContext(Dispatchers.IO) { ScheduleRepository.refresh(browseWeek) }
                        byWeek.data?.let { weekData[browseWeek] = it }
                    } catch (_: Exception) {
                    }
                }
                updateWeekPill()
                renderAll()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.schedule_refresh_failed, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
                // 拉取失败且本来就没数据：重画一次，让**当前可见层**里出现「暂无数据」提示
                // （以前只把 tvEmpty 点亮，而 tvEmpty 住在周视图层里，网格模式下根本看不见
                //  → 用户看到的就是「课表一片空白」，2026-10-26 第十一轮）
                if (scheduleData == null) renderAll()
            } finally {
                progress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun switchWeek(week: Int) {
        if (week < startWeek || week > maxWeek) return
        if (week == currentWeek) {
            // 已经在这一周：再点左上角「回到本周」不应该再播一次切换动画（用户反馈）
            ensureWeekData(week)
            return
        }
        val direction = if (week > currentWeek) 1 else -1
        val layer = activeContentLayer()
        val snapshot = captureSnapshot(layer)
        currentWeek = week
        updateWeekPill()
        renderAll()
        slideSwap(layer, snapshot, direction)
        ensureWeekData(week)
    }

    /** 当前可见的内容层（网格或周视图），两者都是 FrameLayout */
    private fun activeContentLayer(): FrameLayout =
        (if (showingGrid) contentGrid else contentWeek) as FrameLayout

    /**
     * 当前视图真正装课表的滚动容器。
     *
     * 切周的位图**必须只拍它**：拍外层 FrameLayout 会把「盖在上面的旧周位图」和
     * 「钉住的时间轴」一起拍进去，新周那张图里就混进旧周的像素和多出来的一条时间轴。
     */
    private fun activeContentScroll(): View? = runCatching {
        requireView().findViewById<View>(if (showingGrid) R.id.gridScroll else R.id.weekScroll)
    }.getOrNull()

    /**
     * 给位图垫一层不透明底色（`page_bg`）。
     *
     * `View.draw()` 画出来的位图背景是透明的，两张透明位图叠在一起会互相透出来，
     * 屏幕上就是「两周课表叠加」。铺垫底色后每张图都是实心的。
     */
    /**
     * 按**内容层**尺寸拍一张不透明的整页位图。
     *
     * 关键：位图尺寸必须等于 `layer.width x layer.height`，并且把滚动容器按它自己的
     * 偏移（padding）画进去。以前按滚动容器尺寸拍、再用 `FIT_XY` 塞进内容层，
     * 两者高度差 2%，于是整页被拉伸 —— 用户看到「按住切周时课表放大一下，松手缩回去」。
     */
    private fun capturePagerBase(layer: FrameLayout, content: View): Bitmap? {
        if (layer.width <= 0 || layer.height <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.page_bg)
            )
            canvas.save()
            canvas.translate(content.left.toFloat(), content.top.toFloat())
            content.draw(canvas)
            canvas.restore()
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 叠加层（幽灵位图 / 时间轴条 / 新页位图）的 LayoutParams。
     *
     * 内容层是有 padding 的 FrameLayout（左 4dp、上 3dp），子 View 会被放在 padding 之内；
     * 而这三个位图是**在屏幕坐标里整层拍的**，再被 padding 推一下就会整体右移 11px、下移 8px ——
     * 用户看到的就是「切周时左侧时间轴往右伸展、日期行往下伸展」。用负 margin 抵掉。
     */
    private fun overlayParams(layer: FrameLayout, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = -layer.paddingLeft
            topMargin = -layer.paddingTop
        }

    private fun opaqueCopy(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.page_bg)
        )
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    /** 把当前内容画进位图，做「旧内容滑出」的那一层 */
    private fun captureSnapshot(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** 渲染优先用该周专属数据；无则回退主缓存全量+本地周次过滤 */
    private fun dataForWeek(week: Int): ScheduleData? =
        weekData[week]
            ?: ScheduleRepository.loadCachedWeek(week)?.data?.also { weekData[week] = it }
            ?: scheduleData

    /** 该周无数据时按官方方式 GetSchedule&week=N 补拉 */
    private fun ensureWeekData(week: Int) {
        if (weekData.containsKey(week)) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ScheduleRepository.refresh(week) }
                resp.data?.let { weekData[week] = it }
                if (currentWeek == week) renderAll()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateWeekPill() {
        if (!::headerState.isInitialized) return
        headerState.weekLabel = getString(R.string.schedule_week_format, currentWeek)
        headerState.isCurrentWeek = currentWeek == actualCurrentWeek
    }

    /** 跳周：Miuix 对话框列表（原来是 Material PopupMenu） */
    private fun showWeekPicker(anchor: View?) {
        val weeks = (startWeek..maxWeek).toList()
        showMiuixPicker(
            context = requireContext(),
            title = getString(R.string.schedule_jump_week),
            items = weeks.map { getString(R.string.schedule_week_format, it) },
            selectedIndex = weeks.indexOf(currentWeek),
            cancelText = getString(R.string.common_cancel),
            onPick = { index -> weeks.getOrNull(index)?.let { switchWeek(it) } },
        )
    }

    /** 网格 / 周视图切换（顶部 TabRow 触发），带淡入 + 横向位移的过渡 */
    private fun switchView(grid: Boolean) {
        val layer = activeContentLayer()
        val snapshot = captureSnapshot(layer)
        showingGrid = grid
        headerState.showingGrid = grid
        contentWeek.visibility = if (grid) View.GONE else View.VISIBLE
        contentGrid.visibility = if (grid) View.VISIBLE else View.GONE
        if (grid) renderGrid() else renderWeekView()
        slideSwap(activeContentLayer(), snapshot, if (grid) 1 else -1)
    }

    /**
     * 换周 / 切视图的过渡：新内容从侧向 26dp 落下并淡入。
     *
     * 网格是自绘 View，做不了 Compose 的共享元素转场，这里用最朴素的位移+透明度动画，
     * 但足以让「翻周」不再是一帧硬切（用户反馈「翻课程表时没有过渡动画」）。
     */
    private fun slideSwap(layer: FrameLayout, snapshot: Bitmap?, direction: Int) {
        val width = layer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val shift = width.toFloat() * if (direction >= 0) 1f else -1f
        val others = (0 until layer.childCount).map { layer.getChildAt(it) }
        if (snapshot == null) {
            // 首帧/未量到尺寸：没有旧内容快照，只让新内容滑入
            others.forEach {
                it.animate().cancel()
                it.translationX = shift
                it.animate().translationX(0f)
                    .setDuration(MainActivity.TAB_SWAP_MS)
                    .setInterpolator(MainActivity.TAB_INTERPOLATOR)
                    .start()
            }
            return
        }
        // 参照 Tab 切换：旧内容（快照）整幅滑出，新内容从另一侧滑入，纯位移 220ms
        val ghost = ImageView(requireContext()).apply {
            tag = GHOST_TAG
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        layer.addView(ghost, FrameLayout.LayoutParams(width, layer.height))
        ghost.animate().cancel()
        ghost.animate().translationX(-shift)
            .setDuration(MainActivity.TAB_SWAP_MS)
            .setInterpolator(MainActivity.TAB_INTERPOLATOR)
            .withEndAction { layer.removeView(ghost) }
            .start()
        others.forEach {
            it.animate().cancel()
            it.translationX = shift
            it.animate().translationX(0f)
                .setDuration(MainActivity.TAB_SWAP_MS)
                .setInterpolator(MainActivity.TAB_INTERPOLATOR)
                .start()
        }
    }

    private fun renderAll() {
        if (showingGrid) renderGrid() else renderWeekView()
    }

    /**
     * 切回本 Tab 时调用（MainActivity.switchTab）。
     *
     * 用户 2026-10-25 反馈：从工作圈切回课表，课表一片空白。原因是切走时若正好有
     * 换周/切视图的位移动画在跑，层里的子 View 会停在 translationX = ±整屏宽 的位置，
     * 而 Fragment 是 detach/attach（视图复用），回来后没人把位移复位，看起来就是空的。
     * 这里把残留（位移动画、幽灵快照）清干净再重画一遍。
     */
    fun onTabSelected() {
        if (!isAdded || view == null || !::contentGrid.isInitialized) return
        android.util.Log.d(
            TAG,
            "onTabSelected: grid=$showingGrid week=$currentWeek data=${scheduleData != null} " +
                "weekKeys=${weekData.keys} gridVis=${contentGrid.visibility} weekVis=${contentWeek.visibility}"
        )
        clearTransitionLeftovers(contentGrid as android.view.ViewGroup)
        clearTransitionLeftovers(contentWeek as android.view.ViewGroup)
        // 视图是 detach/attach **复用**的：把「当前模式」和实际可见层重新对齐一遍。
        // 第十一轮彻底排查：只要这两者不同步（例如切换/动画被打断后状态回到默认），
        // 就会出现「标题栏正常、下面一片空白」——因为内容画在了看不见的那一层里。
        contentWeek.visibility = if (showingGrid) View.GONE else View.VISIBLE
        contentGrid.visibility = if (showingGrid) View.VISIBLE else View.GONE
        headerState.showingGrid = showingGrid
        swipeRefresh.isRefreshing = false
        // 2026-10-28 第三十三轮（用户：「从小组件进来课表会闪动一下」）：
        // 原来是**无条件** renderAll()，于是每次切回课表（包括从桌面小组件进来）
        // 都把整张网格 removeAllViews 再重建 —— 中间那一帧是空的，深浅色都能看见「闪一下」。
        // 现在只在内容层真的空了才重画；有内容就原样复用，只做一次自检。
        if (contentFilled() == 0) renderAll()
        view?.post { ensureContentNotEmpty() }
    }

    /**
     * 自检：当前内容层到底有没有东西。
     *
     * 「切走再回来课表一片空白」这类问题不该靠用户反馈才发现，所以这里每次切回本页都查一遍，
     * 并把当时的真实状态打进 logcat（tag [TAG]），下次再有线索直接看日志即可。
     */
    /** 当前可见内容层里有多少个子 View（0 = 空的，需要重画） */
    private fun contentFilled(): Int {
        if (!isAdded || view == null) return 0
        return if (showingGrid) {
            view?.findViewById<LinearLayout>(R.id.gridContent)?.childCount ?: 0
        } else {
            dayStrip.childCount + daySections.childCount
        }
    }

    private fun ensureContentNotEmpty() {
        if (!isAdded || view == null) return
        val filled = if (showingGrid) {
            view?.findViewById<LinearLayout>(R.id.gridContent)?.childCount ?: 0
        } else {
            dayStrip.childCount + daySections.childCount
        }
        android.util.Log.d(
            TAG,
            "ensureContentNotEmpty: grid=$showingGrid filled=$filled data=${scheduleData != null} " +
                "week=$currentWeek progress=${progress.visibility}"
        )
        if (filled > 0) {
            if (tvEmpty.visibility == View.VISIBLE && !showingGrid) tvEmpty.visibility = View.GONE
            return
        }
        // 真的空了：重画一次（数据还在的话这一步就能恢复），并明确亮出提示
        renderAll()
        val refilled = if (showingGrid) {
            view?.findViewById<LinearLayout>(R.id.gridContent)?.childCount ?: 0
        } else {
            dayStrip.childCount + daySections.childCount
        }
        if (refilled == 0) {
            android.util.Log.w(TAG, "课表内容层两次渲染后仍为空（数据=${scheduleData != null}）")
            progress.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            // 空态统一由 renderWeekView 负责（画在列表流里），这里不再点亮覆盖层 tvEmpty，
            // 否则它会压在已经画出来的日程卡上（第四十六轮踩过的坑）
        }
    }

    /** 数据没加载出来时的提示（绝不留一张纯白屏） */
    private fun noDataHint(textRes: Int = R.string.schedule_no_data_hint): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = getString(textRes)
            setTextColor(style.onBackgroundVariant)
            gravity = Gravity.CENTER
            setPadding(0, dp(56), 0, dp(56))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /** 清掉 slideSwap 留下的位移与幽灵快照 */
    private fun clearTransitionLeftovers(layer: ViewGroup) {
        for (i in layer.childCount - 1 downTo 0) {
            val c = layer.getChildAt(i)
            if (c is ImageView && c.tag == GHOST_TAG) {
                layer.removeViewAt(i)
                continue
            }
            c.animate().cancel()
            c.translationX = 0f
            c.translationY = 0f
            c.alpha = 1f
        }
    }

    // ==================== 周视图 ====================

    private fun renderWeekView() {
        val week = currentWeek
        val data = dataForWeek(week)
        dayStrip.removeAllViews()
        daySections.removeAllViews()
        if (data == null) {
            android.util.Log.w(
                TAG,
                "renderWeekView: 无数据 week=$week scheduleData=${scheduleData != null} weekKeys=${weekData.keys}"
            )
            daySections.addView(noDataHint())
            tvEmpty.visibility = View.GONE   // noDataHint 自己有文案，空态不要再叠一层
            return
        }

        val density = resources.displayMetrics.density
        val s = style

        val today = LocalDate.now()
        val dayNames = resources.getStringArray(R.array.schedule_day_names)
        val monday = today.with(DayOfWeek.MONDAY)
        val offset = week - actualCurrentWeek
        val weekMonday = monday.plusWeeks(offset.toLong())

        // 相邻的同名同教室连续节次（如高等数学B1 连上 1-2 节）算**一门课**：
        // 统计与预览都必须用合并后的列表，否则周一 3 门课会显示成「6 门」
        // （用户 2026-10-25 反馈：一二节都是高等数学B1，应视为一节课）
        //
        // 2026-10-28 用户：列表视图同样隐藏「整天没课」的那一天（与网格视图隐藏空列一致）。
        // 所以先一次性算好 7 天合并后的课程，再决定渲染哪几天。
        val mergedByDay = (0 until 7).map { d -> mergeCourses(getCoursesForDay(data, d, week)) }
        val totalCourses = mergedByDay.count { it.isNotEmpty() }
        // 先收掉上一次留下的空态文案，避免它压在新画出来的内容上
        tvEmpty.visibility = View.GONE

        for (dayIdx in 0 until 7) {  // 周一~周日
            val merged = mergedByDay[dayIdx]
            if (merged.isEmpty()) continue   // 空日：摘要卡 + 课程卡整块不画
            val date = weekMonday.plusDays(dayIdx.toLong())
            val isToday = date == today

            // 摘要格
            val summary = LayoutInflater.from(context).inflate(R.layout.item_day_summary, dayStrip, false)
            scaleXmlText(summary)
            summary.background = s.rounded(density, 12f, if (isToday) s.accentFill else s.chipFill)
            summary.findViewById<TextView>(R.id.tvDayName).apply {
                text = dayNames[dayIdx]
                setTextColor(if (isToday) s.onAccentFill else s.onBackground)
            }
            summary.findViewById<TextView>(R.id.tvCount).apply {
                // 2026-10-25 用户反馈：「第 8 周周四显示 0 而非 0 门」——plurals 在窄卡里被
                // 父容器量成窄宽度后直接裁掉了量词。改成固定格式串 + 单行 + 卡片内满宽居中，
                // 保证「0 门 / 4 门」这种短文本永远完整。
                text = resources.getString(R.string.schedule_day_count_format, merged.size)
                setSingleLine(true)
                ellipsize = null
                textSize = ts(9.5f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dp(6) }
                background = s.rounded(density, 50f, s.badgeFill(isToday))
                setTextColor(if (isToday) s.onAccentFill else s.onBackgroundVariant)
            }

            val preview = summary.findViewById<LinearLayout>(R.id.coursePreview)
            // 只放合并后的课名，且**单行省略**：摘要卡固定 132dp，课程名换行会把最后一行
            // 顶到卡片边缘截断（用户看到的就是「文字异常换行」）
            merged.take(4).forEach { ci ->
                val tv = TextView(context).apply {
                    text = ci.course.name ?: ""
                    textSize = ts(8.5f)
                    setTextColor(if (isToday) s.onAccentFill else s.onBackgroundVariant)
                    // 2026-10-25 用户反馈：窄卡里课名被省略号吃掉了。
                    // 改成最多 2 行自动换行，常见课名（高等数学B1、大学计算机基础训练）能完整显示
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 1, 0, 1)
                    // 2026-10-25 用户反馈：课名右侧留了空白就变成「高等数…」。
                    // 显式给 MATCH_PARENT，省略号才会落在卡片右边缘。
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                preview.addView(tv)
            }
            dayStrip.addView(summary)

            // 课程区
            val section = LayoutInflater.from(requireContext()).inflate(R.layout.item_day_section, daySections, false)
            scaleXmlText(section)
            section.background = s.rounded(density, 16f, s.cardFill)
            val header = section.findViewById<TextView>(R.id.tvDayHeader)
            header.text = if (isToday) {
                getString(R.string.schedule_day_today, dayNames[dayIdx])
            } else {
                dayNames[dayIdx]
            }
            header.setTextColor(if (isToday) s.accent else s.onBackground)
            val container = section.findViewById<LinearLayout>(R.id.lessonContainer)

            merged.forEach { ci ->
                addLessonRow(container, ci)
            }
            daySections.addView(section)
        }

        // 空态**画在列表流里**，不再用 XML 那个覆盖层 tvEmpty：
        // 覆盖层是叠在 daySections 之上的，一旦被点亮就会压在日程卡中间
        // （第四十六轮实测：第 6/7 周明明有 4 天课，「本周无课」却浮在周一日程卡上）。
        // 现在覆盖层永远 GONE，有课就啥都不加，没课就补一个居中文案，路径只有一条。
        tvEmpty.visibility = View.GONE
        if (totalCourses == 0) {
            daySections.addView(noDataHint(R.string.schedule_week_no_course))
        }
        android.util.Log.d(
            TAG,
            "renderWeekView 完成: week=$week 有课天数=$totalCourses 空态=${totalCourses == 0}"
        )
    }

    private fun addLessonRow(parent: LinearLayout, ci: CourseItem) {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lesson_row, parent, false)
        val color = courseColor(ci.course.name ?: "?")
        val density = resources.displayMetrics.density
        view.background = style.rounded(density, 12f, style.courseFill(color))
        view.findViewById<View>(R.id.vAccent).background = style.rounded(density, 1.5f, color)
        val textColor = style.onCourseTint(color)

        val periodLabel = if (ci.periodStart == ci.periodEnd) {
            getString(R.string.schedule_period_single, ci.periodStart)
        } else {
            getString(R.string.schedule_period_range, ci.periodStart, ci.periodEnd)
        }
        view.findViewById<TextView>(R.id.tvTimeLabel).apply {
            text = periodLabel
            setTextColor(textColor)
        }
        view.findViewById<TextView>(R.id.tvName).apply {
            text = ci.course.name ?: ""
            setTextColor(textColor)
        }
        view.findViewById<TextView>(R.id.tvMeta).apply {
            text = "${ci.course.teacher ?: ""} · ${ci.course.room ?: ""}"
            setTextColor(textColor)
            alpha = 0.78f
        }

        view.setOnClickListener { showCourseDetail(ci) }
        parent.addView(view)
    }

    /** 课程详情：Miuix 对话框（键值行版），标题用课程名，副标题给色块色相提示 */
    private fun showCourseDetail(ci: CourseItem) {
        val odd = when (ci.course.oddEven) {
            "1" -> getString(R.string.course_odd_suffix)
            "2" -> getString(R.string.course_even_suffix)
            else -> ""
        }
        val week = ci.course.weekRange?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.course_week_value, it, odd) } ?: "—"
        val period = if (ci.periodStart == ci.periodEnd) {
            getString(R.string.course_period_single, ci.periodStart)
        } else {
            getString(R.string.course_period_range, ci.periodStart, ci.periodEnd)
        }
        showMiuixDialog(
            requireContext(),
            MiuixDialogSpec(
                title = ci.course.name?.takeIf { it.isNotBlank() } ?: "—",
                rows = listOf(
                    getString(R.string.course_label_room) to (ci.course.room?.takeIf { it.isNotBlank() } ?: "—"),
                    getString(R.string.course_label_teacher) to (ci.course.teacher?.takeIf { it.isNotBlank() } ?: "—"),
                    getString(R.string.course_label_week) to week,
                    getString(R.string.course_label_period) to period,
                ),
                actions = listOf(getString(R.string.common_ok) to {}),
            ),
        )
    }

    // ==================== 网格视图 ====================

    private fun renderGrid() {
        val container = requireView().findViewById<LinearLayout>(R.id.gridContent)
        container.removeAllViews()
        val data = dataForWeek(currentWeek)
        if (data == null) {
            // 以前这里直接 return：容器已经被清空，屏幕上就是一片空白，连句提示都没有
            android.util.Log.w(
                TAG,
                "renderGrid: 无数据 week=$currentWeek scheduleData=${scheduleData != null} weekKeys=${weekData.keys}"
            )
            container.addView(noDataHint())
            return
        }

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val s = style
        val today = LocalDate.now()
        val dayNames = resources.getStringArray(R.array.schedule_day_names)
        val monday = today.with(DayOfWeek.MONDAY)
        val weekMonday = monday.plusWeeks((currentWeek - actualCurrentWeek).toLong())

        // 每天：收集并合并连续节次的同一课程（跨节大块）
        val dayCourses = Array(7) { d -> mergeCourses(getCoursesForDay(data, d, currentWeek)) }
        val maxPeriod = dayCourses.maxOf { list -> list.maxOfOrNull { it.periodEnd } ?: 0 }
        if (maxPeriod == 0) {
            container.addView(TextView(ctx).apply {
                text = getString(R.string.schedule_week_no_course)
                setTextColor(s.onBackgroundVariant)
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            })
            return
        }

        // ===== 自适应行列（用户 2026-10-28）：空行/空列自动隐藏，剩余行列重新分配空间 =====
        //
        // 以前「一律画满 11 节」，只要后半段没课下半屏就是空气；周末没课也照样占 2 列。
        // 现在只画「真的被课程覆盖到的节次」与「真的有课的星期」，行高/列宽按数量反推：
        //   rowOrder：有课的节次（升序）。连堂块覆盖到的节次全部在内，所以块仍然连续；
        //   colOrder：有课的星期（升序）。
        // 切周动画（三张位图跟手）拍的是「整个内容层」，而行列自适应只改内部排布、
        // 不改内容层尺寸，所以泳道动画的位图尺寸假设不受影响。
        val rowOrder = (1..PeriodClock.count).filter { p ->
            dayCourses.any { list -> list.any { ci -> ci.periodStart <= p && p <= ci.periodEnd } }
        }
        val colOrder = (0 until 7).filter { d -> dayCourses[d].isNotEmpty() }
        val rowCount = rowOrder.size
        val colCount = colOrder.size
        if (rowCount == 0 || colCount == 0) {
            container.addView(TextView(ctx).apply {
                text = getString(R.string.schedule_week_no_course)
                setTextColor(s.onBackgroundVariant)
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            })
            return
        }
        val rowIdx = IntArray(PeriodClock.count + 2) { -1 }.also { m ->
            rowOrder.forEachIndexed { i, p -> if (p in m.indices) m[p] = i }
        }
        val colIdx = IntArray(7) { -1 }.also { m -> colOrder.forEachIndexed { i, d -> m[d] = i } }
        fun rowIndexOf(p: Int): Int = if (p in 1..PeriodClock.count) rowIdx[p] else -1
        fun colIndexOf(d: Int): Int = if (d in 0..6) colIdx[d] else -1

        // 尺寸
        //
        // 2026-10-24 用户要求「尽量能在一个屏幕范围内容纳」：行高不再写死 56dp，
        // 而是用网格滚动区的实际高度反推（11 节 + 10 个间隙 + 2 个休息带 + 表头），
        // 并夹在 32~56dp 之间——高屏一屏装完，矮屏才允许滑动。
        // 2026-10-25 十次改版（用户：节次没到屏幕最左端、右侧出现裁切）：
        // 宽度不再用「屏幕宽 - 常量」估算，直接量 contentGrid 的**内宽**。
        // 之前少减了面板左右 padding，colW 偏大 12dp，最后一列被父容器裁掉。
        // 午休/晚休的位置（键必须与下面 breaksAfter 一致）
        val breakPeriods = listOf(4, 8)
        // 只有「上方有可见行、下方也有可见行」的休息带才画，否则就是一个孤立胶囊
        val visibleBreaks = breakPeriods.filter { k -> rowOrder.any { it <= k } && rowOrder.any { it > k } }
        val gapH = dp(3)
        val availW = contentGrid.let { it.width - it.paddingLeft - it.paddingRight }
            .takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(2 * 2 + 2 * 4))
        val bandH = dp(18)
        val headerRowH = dp(46)
        val panelPad = dp(9)    // 面板上下内边距（3dp×2）+ 表头下方 2px 的真实预留
        val availH = gridScroll.height
        // 只在「完全没量到高度」（首帧）时补一次重绘；量到高度后无论多小都不再重排，
        // 否则可视区高度真的偏小时会自己触发自己（死循环）。
        if (availH == 0 && !gridRelayoutPending) {
            gridRelayoutPending = true
            gridScroll.post {
                gridRelayoutPending = false
                if (isAdded && view != null) renderAll()
            }
        }
        val usable = availH - panelPad - headerRowH - bandH * visibleBreaks.size -
            gapH * (rowCount - 1).coerceAtLeast(0)
        // 行高上限随屏幕放大（平板可用高度更多，卡在 96dp 会留一大片空白）。
        // 160dp 只是极端保护（竖着看 4:3 平板别把每行撑成一块广场），正常平板上
        // usable/11 就已经小于它，所以课表会真正铺满到 tab 栏。
        val rowH = (usable / rowCount).coerceIn(dp(32), (dp(160) * textScale).toInt())

        // ===== 宽度自适应（2026-10-27 第二十轮用户反馈：窄屏上「最左侧的时间被挡住」）=====
        // 以前的写法是「时间列固定 44dp + 列宽夹到至少 30dp」，窄屏里两者一起把总宽顶出容器，
        // 左边的节次时间就被裁掉。现在改成「先量字宽、再用剩下的宽度分 7 列」：
        //   1. 间隙窄屏收窄（4dp → 3dp → 2dp）；
        //   2. 时间列宽度 = 「08:45」在当前字号下的实际宽度 + 内边距（不再写死 44dp）；
        //   3. 列宽永远等于「剩下的宽度 / 7」，不给下限，靠字号自适应保证可读。
        val scaledDensity = resources.displayMetrics.scaledDensity
        val measure = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun textPx(text: String, sizeSp: Float): Float {
            measure.textSize = sizeSp * scaledDensity
            return measure.measureText(text)
        }
        fun fontH(sizeSp: Float): Float {
            measure.textSize = sizeSp * scaledDensity
            val fm = measure.fontMetrics
            return fm.descent - fm.ascent
        }
        val gapW = when {
            availW < dp(300) -> dp(2)
            availW < dp(340) -> dp(3)
            else -> dp(4)
        }
        // 时间格最宽的那一行是「08:45」；窄屏字号会降到 7sp，所以按 8sp 量出来就够用
        val timeW = (textPx("08:45", ts(8f)) + dp(4)).toInt()
            .coerceIn(dp(26), dp(52))
        val colW = ((availW - timeW - gapW * colCount - dp(1)) / colCount.toFloat()).toInt()
            .coerceAtLeast(dp(20))
        gridAxisW = timeW + gapW // 切周时要把这一列钉住
        val breaksAfter = mapOf(
            4 to getString(R.string.schedule_break_noon),
            8 to getString(R.string.schedule_break_evening)
        )
        // 可见行序号 i 上方叠了几个休息带
        fun bandsAbove(i: Int): Int = visibleBreaks.count { k -> k < rowOrder[i] }
        fun yOfRow(i: Int): Int = i * (rowH + gapH) + bandsAbove(i) * bandH

        // ===== 表头行：左占位 + 7 天头（小爱风格：无底色，星期+日期） =====
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(timeW + gapW, headerRowH)
        })
        for (ci2 in colOrder.indices) {
            val d = colOrder[ci2]
            val date = weekMonday.plusDays(d.toLong())
            val isToday = date == today
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(colW, headerRowH).apply {
                    if (ci2 < colCount - 1) marginEnd = gapW
                }
                if (isToday) {
                    background = s.rounded(density, 10f, s.accentFill)
                }
            }
            head.addView(TextView(ctx).apply {
                text = dayNames[d]
                textSize = ts(13f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isToday) s.onAccentFill else s.onBackground)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            head.addView(TextView(ctx).apply {
                text = String.format("%d/%d", date.monthValue, date.dayOfMonth)
                textSize = ts(9f)
                setTextColor(if (isToday) s.onAccentFill else s.onBackgroundVariant)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            headerRow.addView(head)
        }
        container.addView(headerRow)
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(2)) })

        // ===== 主体：左时间列 + 块区 =====
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 左侧节次列（小爱风格：节次号 + 起止时间，无底色）
        val timeCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(timeW + gapW, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // 一格要塞「节次号 + 起 + 止」三行字。矮屏 rowH 只有 32dp，三行放不下就会被裁掉
        // （用户反馈的「最左侧的时间被挡住」就是这个）。这里按实际字高挑一档能放下的组合：
        // 三行 → 「节次号 + 起始时间」两行 → 只有节次号，永远不会裁字。
        // 候选档整体乘 textScale：手机上等于原值，平板上上限抬到 15sp / 9.2sp
        val numSizeCandidates = floatArrayOf(13f, 12f, 11f).map { ts(it) }.toFloatArray()
        val timeSizeCandidates = floatArrayOf(8f, 7.5f, 7f).map { ts(it) }.toFloatArray()
        var numSize = numSizeCandidates.last()
        var timeSize = timeSizeCandidates.last()
        var timeLines = 0
        loop@ for (ls in intArrayOf(2, 1, 0)) {
            for (ns in numSizeCandidates) {
                for (ts in timeSizeCandidates) {
                    val need = fontH(ns) + (if (ls > 0) ls * fontH(ts) else 0f) + dp(2)
                    if (need <= rowH) {
                        numSize = ns
                        timeSize = ts
                        timeLines = ls
                        break@loop
                    }
                }
            }
        }
        for (p in rowOrder) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(timeW, rowH).apply { bottomMargin = gapH }
            }
            cell.addView(TextView(ctx).apply {
                text = "$p"
                textSize = numSize
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(s.onBackground)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            val times = PeriodClock.rangeText(p, p).split("-")
            times.take(timeLines).forEach { t ->
                cell.addView(TextView(ctx).apply {
                    text = t
                    textSize = timeSize
                    setTextColor(s.onBackgroundVariant)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }
            timeCol.addView(cell)
            // 午休/晚休：时间列只留同高占位（文案统一由块区的全宽胶囊显示，
            // 否则「午休」会在时间列和胶囊里各出现一次）
            breaksAfter[p]?.let {
                timeCol.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(timeW + gapW, bandH)
                })
            }
        }
        body.addView(timeCol)

        // 课程块区（FrameLayout 绝对定位）
        val gridH = yOfRow(rowCount - 1) + rowH + gapH
        val blockArea = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(colCount * colW + (colCount - 1) * gapW, gridH)
        }

        // 横向网格线（有休息带的行不画）
        for (i in 0 until rowCount - 1) {
            if (visibleBreaks.contains(rowOrder[i])) continue
            val divider = View(ctx)
            divider.setBackgroundColor(s.divider)
            divider.alpha = 0.5f
            val dlp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1))
            dlp.topMargin = yOfRow(i) + rowH + gapH / 2
            blockArea.addView(divider, dlp)
        }

        // 纵向网格线：参照 NexioSchedule 主课表 —— **不画**。
        // 以前每列都有一条 1px 竖线，整块看起来像 Excel 表格，满屏线条抢课程块的视觉重心；
        // 去掉后课程胶囊成为唯一主体，页面底色（page_bg）直接透出来，观感轻盈很多。
        // 横向线保留但压得很淡（见上），仍然能看出「第几节」的节奏。

        // 午休/晚休分隔带
        for ((k, label) in breaksAfter) {
            if (!visibleBreaks.contains(k)) continue
            // 2026-10-25 用户要求：午休/晚休不要叠一层灰底（看着像阴影），正常显示文字即可
            val band = TextView(ctx).apply {
                text = label
                textSize = ts(10f)
                setTextColor(s.onBackgroundVariant)
                alpha = 0.7f
                gravity = Gravity.CENTER
            }
            val blp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bandH)
            // 休息带落在「k 之后第一个可见行」的上方；k 本身可能因为没课被隐藏
            val firstBelow = rowOrder.indexOfFirst { it > k }
            blp.topMargin = if (firstBelow >= 0) yOfRow(firstBelow) - bandH else 0
            blockArea.addView(band, blp)
        }

        // 课程块
        for (d0 in 0 until 7) {
            val dayBlocks = dayCourses[d0]
            val lanes = laneLayout(dayBlocks)
            dayBlocks.forEachIndexed { bi, ci ->
                val info = lanes[bi]
                val startIdx = rowIndexOf(ci.periodStart).coerceAtLeast(0)
                val endIdx = rowIndexOf(ci.periodEnd).let { if (it >= 0) it else startIdx }
                val spanRows = endIdx - startIdx + 1
                val blockColor = courseColor(ci.course.name ?: "?")
                val blockText = style.onCourseTint(blockColor)
                // 参考截图的观感：整块浅色胶囊 + 同色系深字，块内居中，不加色条
                // 窄屏列宽只有 20~30dp，字号再按老值就会把课名挤成竖排；这里一并按列宽降档
                val nameSize = when {
                    colW < dp(30) -> ts(7.5f)
                    colW < dp(40) || rowH < dp(40) -> ts(8.5f)
                    else -> ts(9.5f)
                }
                // 外层 FrameLayout：底色胶囊 + 内容 + 右下角色点（参照 NexioSchedule 课程块右下角的实心小圆）
                val block = FrameLayout(ctx).apply {
                    background = s.rounded(density, 10f, style.courseFill(blockColor))
                }
                val blockInner = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(3), dp(2), dp(3))
                }
                block.addView(blockInner, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
                // 右下角色点 = 「这一格有多门课重合」的标记（NexioSchedule 的语义），
                // 正常不重合的块不画点（用户 2026-10-25 反馈：之前连堂就画点，语义不对）
                if (info.overlap) {
                    block.addView(View(ctx).apply {
                        background = s.rounded(density, 3f, blockColor)
                    }, FrameLayout.LayoutParams(dp(6), dp(6)).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        rightMargin = dp(5)
                        bottomMargin = dp(4)
                    })
                }
                blockInner.addView(TextView(ctx).apply {
                    text = ci.course.name ?: ""
                    textSize = nameSize
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(blockText)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = if (rowH < dp(40)) 4 else 8
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                })
                val room = ci.course.room ?: ""
                if (room.isNotEmpty()) {
                    blockInner.addView(TextView(ctx).apply {
                        text = room
                        textSize = nameSize - ts(1f)
                        setTextColor(blockText)
                        alpha = 0.8f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        maxLines = 2
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    })
                }
                // 重合时才分栏（两门课同时段横向各占一半），否则占满整列
                val blockW = if (info.lanes > 1) (colW - gapW) / info.lanes else colW
                val lp = FrameLayout.LayoutParams(blockW, spanRows * (rowH + gapH) - gapH)
                lp.leftMargin = colIndexOf(d0).coerceAtLeast(0) * (colW + gapW) +
                    info.lane * (blockW + gapW)
                lp.topMargin = yOfRow(startIdx)
                block.setOnClickListener { showCourseDetail(ci) }
                blockArea.addView(block, lp)
            }
        }
        body.addView(blockArea)
        container.addView(body)
    }

    // ==================== 数据处理 ====================

    private fun getCoursesForDay(data: ScheduleData, dayIdx: Int, week: Int): List<CourseItem> {
        val dayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val targetName = dayNames.getOrNull(dayIdx) ?: return emptyList()
        val day = data.schedule?.find { it.dayName == targetName } ?: return emptyList()

        val result = mutableListOf<CourseItem>()
        day.periods?.forEach { period ->
            val periodNum = period.periodName?.trim()?.toIntOrNull() ?: return@forEach
            period.courses?.forEach { course ->
                if (course.isWeekValid(week) && course.isOddEvenValid(week)) {
                    result.add(CourseItem(course, periodNum, periodNum))
                }
            }
        }
        return result.sortedBy { it.periodStart }
    }

    private fun mergeCourses(courses: List<CourseItem>): List<CourseItem> {
        if (courses.size <= 1) return courses
        val result = mutableListOf<CourseItem>()
        var cur = courses[0]
        for (i in 1 until courses.size) {
            val next = courses[i]
            // 连续节次且不跨越午休/晚休（4→5、8→9）
            if (next.course.name == cur.course.name && next.course.room == cur.course.room &&
                next.periodStart == cur.periodEnd + 1 &&
                cur.periodEnd != 4 && cur.periodEnd != 8
            ) {
                cur = cur.copy(periodEnd = next.periodEnd)
            } else {
                result.add(cur)
                cur = next
            }
        }
        result.add(cur)
        return result
    }

    // ==================== 配色 ====================

    // 第二十八轮：色相改由 [CourseColors] 统一分配 —— 本学期每门课均分 360°，互不重复，
    // 并且和桌面小组件用的是同一个色相（用户：「高数B 和 航空航天概论不能同色」）。
    private fun courseColor(courseName: String): Int {
        courseColorMap[courseName]?.let { return it }
        val hue = CourseColors.hueOf(courseName)
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.82f))
        courseColorMap[courseName] = color
        return color
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 界面放大系数（第四十五轮，用户第 6 条）。平板上字号与行高上限一起放大，
     * 见 [com.lanhang.miuix.ui.UiScale]；手机上恒为 1，行为与之前完全一致。
     */
    private val textScale: Float by lazy { com.lanhang.miuix.ui.UiScale.factor(requireContext()) }

    /** 静态字号统一过这里：手机上原样返回，平板上按比例放大 */
    private fun ts(v: Float): Float = if (textScale == 1f) v else v * textScale

    /**
     * 把一段 inflate 出来的布局里所有 TextView 的字号按 [textScale] 放大。
     *
     * xml 里的 sp 是写死的（`item_day_summary` 13sp / 10sp、`item_day_section` 14sp…），
     * 大屏上不动就显得小。只在 textScale > 1 时生效；**必须在设置代码字号之前调用**，
     * 否则会把后面显式设定的字号覆盖回去。
     */
    private fun scaleXmlText(root: android.view.View) {
        if (textScale == 1f) return
        val densityScaled = resources.displayMetrics.scaledDensity
        val stack = ArrayDeque<android.view.View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is TextView) v.textSize = v.textSize / densityScaled * textScale
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
        }
    }

    /** 一块课在当天占第几栏：同一时段有多门课时横向分栏，重合的块画色点 */
    private data class LaneInfo(val lane: Int, val lanes: Int, val overlap: Boolean)

    private fun laneLayout(blocks: List<CourseItem>): List<LaneInfo> {
        if (blocks.isEmpty()) return emptyList()
        val laneEnds = mutableListOf<Int>()
        val laneOf = IntArray(blocks.size)
        blocks.forEachIndexed { i, b ->
            var lane = laneEnds.indexOfFirst { it < b.periodStart }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds.add(b.periodEnd)
            } else {
                laneEnds[lane] = b.periodEnd
            }
            laneOf[i] = lane
        }
        val overlaps = blocks.indices.map { i ->
            blocks.indices.any { j ->
                j != i && blocks[j].periodStart <= blocks[i].periodEnd &&
                    blocks[i].periodStart <= blocks[j].periodEnd
            }
        }
        val lanes = maxOf(1, laneEnds.size)
        return blocks.indices.map { i -> LaneInfo(laneOf[i], lanes, overlaps[i]) }
    }

}

/** 向下滚多少像素后顶栏完全收起（和工作圈 ReportListScroll 的 320px 对齐） */
private const val BAR_COLLAPSE_SPAN_PX = 320f
