package com.lanhang.miuix.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 外壳（顶栏 + 底栏）的共享状态。
 *
 * 页面仍然是 Fragment（课表是不可替换的自绘网格），外壳用 Miuix 组件在 Compose 里重画，
 * 二者通过这个对象通信：Fragment 调 [MainActivity 的 showToolbarMain/showToolbarSub]
 * 改标题，用户可以点底栏换 Tab。
 */
@Stable
class ShellState {

    /** 当前 Tab（0 课表 / 1 工作 / 2 工作圈 / 3 我的） */
    var tab by mutableIntStateOf(TAB_SCHEDULE)

    /** 顶栏主标题 */
    var title by mutableStateOf("")

    /** 顶栏副标题（Miuix SmallTopAppBar 的 subtitle） */
    var subtitle by mutableStateOf("")

    /** 非空 = 二级页，顶栏左侧显示返回箭头 */
    var backAction by mutableStateOf<(() -> Unit)?>(null)

    /**
     * 非空 = 顶栏左侧在返回箭头**之前**再多一个「首页」键（2026-10-26 用户要求）。
     *
     * 只有通讯录这类深层目录页挂它：返回箭头退一级目录，首页键一步回本 Tab 的根页面。
     */
    var homeAction by mutableStateOf<(() -> Unit)?>(null)

    /** 顶栏右上角的文字动作（如二级页的「发表」）；null = 不显示 */
    var actionText by mutableStateOf<String?>(null)

    /** 文字动作的回调 */
    var onAction by mutableStateOf<(() -> Unit)?>(null)

    // 2026-10-26 第十一轮：原来的 `barMotion`（INSTANT/MORPH/FADE 三档）已删除。
    // 顶栏怎么动只有一份实现：ui/shell/ShellBars.kt 里的 `object BarAnimation` +
    // `LanhangTopBar` 的单一进度。调用方（MainActivity / 各 Fragment）不再传任何动效参数。

    companion object {
        const val TAB_SCHEDULE = 0
        /**
         * 2026-10-26 第六轮：底栏原「成绩」与「我的 → 工作」互换——
         * 工作（办事大厅）升为一级 Tab，成绩降为「我的」里的二级页。
         */
        const val TAB_WORK = 1
        const val TAB_MOMENTS = 2
        /** 0.8.0：原「设置」Tab 升成「我的」，设置本身降为「我的」里的二级页 */
        const val TAB_MINE = 3
    }
}

/**
 * 顶栏「大标题 → 居中小标题」的收起状态。
 *
 * 页面还是 Fragment，顶栏却住在 MainActivity 的另一个 ComposeView 里，两者之间没有
 * 嵌套滚动关系，所以没法用 Miuix 自带的 `ExitUntilCollapsedScrollBehavior`
 * （它靠 `Modifier.nestedScroll` 从内容侧收到滚动）。这里改成「由页面主动上报」：
 * 每个列表页用 [ReportListScroll] 把自己的滚动位置写进来，顶栏读它决定用哪一档。
 */
object ShellScroll {
    /**
     * 每个 Tab 各自记一份收起进度（0 = 课表 / 1 = 工作 / 2 = 工作圈 / 3 = 我的）。
     *
     * 2026-10-26 用户反馈「从课表切到工作圈，无论有没有滚动，顶栏都变成最大状态」：
     * 原因是进度只有一个全局值，离开课表时课表的上报把前台顶栏清零，而工作圈那段
     * 组合是复用的、`snapshotFlow` 只在列表位置变化时才发值，没人把它补回去。
     * 现在按 Tab 分开记账，谁在前台就用谁的。
     */
    private val perTab = mutableStateMapOf<Int, Float>()

    /** 当前前台的 Tab，由 [MainActivity.switchTab] 经 [switchTo] 写入 */
    var activeTab by mutableIntStateOf(ShellState.TAB_SCHEDULE)
        private set

    /**
     * 前台顶栏的收起进度：0f = 大标题，1f = 居中小标题。
     *
     * 2026-10-25 用户第二次反馈「顶栏收缩和文字不同步 / 文字突然闪到顶部」：
     * 上一版只有一个布尔值，到位后整条顶栏换成另一套组件做交叉淡入，切换是离散的。
     * 现在由列表滚动位置逐帧算出 0..1 的进度，标题字号与位置按它插值，和手指完全同步。
     */
    var progress by mutableStateOf(0f)
        private set

    val collapsed: Boolean get() = progress > 0.995f

    /**
     * 页面/视图上报自己的滚动进度。
     *
     * 带 [tab] 不只是记账：**只有前台 Tab 的上报才会改 [progress]**。离开的页面
     * （课表 `onPause`、刚被遮住的列表页）照样能更新自己那份记录，但拽不动前台顶栏。
     */
    fun report(tab: Int, value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (kotlin.math.abs((perTab[tab] ?: 0f) - v) > 0.004f) perTab[tab] = v
        if (tab != activeTab) return
        if (kotlin.math.abs(progress - v) > 0.004f) progress = v
    }

    /** 切 Tab：底栏选中谁，就用谁上次记录的进度，切过去不会先闪一下大标题 */
    fun switchTo(tab: Int) {
        activeTab = tab
        val v = (perTab[tab] ?: 0f).coerceIn(0f, 1f)
        if (progress != v) progress = v
    }

    fun reset() {
        perTab.clear()
        if (progress != 0f) progress = 0f
    }
}

/**
 * 列表往下滑过 [spanPx] 就把顶栏收成小标题；滑回顶部再展开。
 *
 * [tab] 是这一页属于哪个底栏 Tab —— 进度按 Tab 记账，非前台页面的上报不会顶掉前台顶栏。
 */
@Composable
fun ReportListScroll(state: LazyListState, tab: Int, spanPx: Int = 320) {
    fun current(): Float =
        if (state.firstVisibleItemIndex > 0) {
            1f
        } else {
            (state.firstVisibleItemScrollOffset / spanPx.toFloat()).coerceIn(0f, 1f)
        }

    LaunchedEffect(state, tab) {
        snapshotFlow { current() }.collect { ShellScroll.report(tab, it) }
    }
    // 切回本页时补报一次：列表位置没变（状态是复用的），snapshotFlow 不会重新发值，
    // 少了这一句顶栏就会停在上次离开时的档位（用户 2026-10-26 反馈的那个 bug）。
    LaunchedEffect(tab, ShellScroll.activeTab) {
        if (ShellScroll.activeTab == tab) ShellScroll.report(tab, current())
    }
}

