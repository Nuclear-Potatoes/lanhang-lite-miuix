package com.lanhang.miuix.ui.shell

import androidx.compose.foundation.gestures.animateScrollBy
import kotlin.math.pow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 底栏「双击回到顶部」的信号中枢。
 *
 * 底栏在主 Activity 的 ComposeView 里，各页面（Compose 屏幕 / 课表的 View 网格）在别的树里，
 * 直接互相持有引用很脏，所以用一个进程内的 Compose 状态做单向下行通知：
 * 底栏双击 → [request] 自增 [tick] → 各页面 `LaunchedEffect(ScrollToTop.tick)` 里判断
 * [tab] 是不是自己，是就把列表滚回第 0 项。
 *
 * 用 Compose 状态（而不是 Flow/回调）是因为使用者全是 Composable：状态变 → 自动重组，
 * 不需要每页在 onDestroy 里反注册，页面被销毁重建也不会漏。
 */
object ScrollToTop {
    /** 每次双击自增；页面只把它当「有新请求」的信号，不关心数值本身 */
    var tick by mutableIntStateOf(0)
        private set

    /** 最近一次请求的 Tab（[ShellState.TAB_SCHEDULE] 等） */
    var tab by mutableIntStateOf(-1)
        private set

    fun request(tabIndex: Int) {
        tab = tabIndex
        tick++
    }
}

/**
 * 「只响应**新的**双击请求」的副作用。
 *
 * 各页面原来直接写 `LaunchedEffect(ScrollToTop.tick) { if (tab == 本页) 滚到顶 }`，
 * 有个致命时序：页面从二级页（他人主页 / 相册 / 成绩详情…）返回时，整个 Compose 组合是
 * **重建**的，`LaunchedEffect` 会以**同一个旧 tick** 再跑一遍 —— 于是用户刚滚到的位置
 * 又被拽回顶部。
 *
 * 2026-10-30 用户反馈：「双击滚到页面顶部后，再次滚到页面下部，点击个人头像后，
 * 没有任何操作，app 就自动滚到了页面顶端」。拆开就是这条 —— 真正的点因是「本页组合重建」，
 * 而重建的触发点（点头像进主页）看起来跟滚动毫无关系，所以现象特别费解。
 *
 * 修法：每页 remember 一个「上次处理过的 tick」，只有 tick 真的变了才滚。
 * remember 在组合重建时会重新初始化成当前值，正好实现「重建不响应、只响应新请求」。
 */
@androidx.compose.runtime.Composable
fun ScrollToTopEffect(tabIndex: Int, onScrollTop: suspend () -> Unit) {
    val handled = androidx.compose.runtime.remember { intArrayOf(ScrollToTop.tick) }
    androidx.compose.runtime.LaunchedEffect(ScrollToTop.tick) {
        if (ScrollToTop.tick == handled[0]) return@LaunchedEffect
        handled[0] = ScrollToTop.tick
        if (ScrollToTop.tab == tabIndex) onScrollTop()
    }
}


/**
 * 「慢慢滚回顶部」—— **四个 Tab 共用这一个实现**。
 *
 * 节奏对齐课表页那条 View 版动画（`ScheduleFragment.scrollSlowly`）：
 * 一次连续滚动、减速曲线、时长按距离 380~820ms。
 *
 * 为什么不再「按可见项一屏一屏地滚」（v1.0.x 的做法）：
 * 那样每一屏都是一条独立 tween，屏与屏之间有停顿，列表项矮的页面（工作圈）看起来就是
 * 「一跳一跳」地往上窜。用户 2026-10-28 明确指出要统一成我的页 / 课表页那种顺滑。
 *
 * 距离用「已过项数 × 可见项平均高度 + 当前偏移」估算：懒加载列表拿不到精确总高度，
 * 但 `animateScrollBy` 滚过头会被夹在顶部，估大一点是安全的，估小了末尾补一次即可。
 */
suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToTopSlow() {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    val visible = layoutInfo.visibleItemsInfo
    val avg = if (visible.isEmpty()) 0f else visible.sumOf { it.size }.toFloat() / visible.size
    val distance = if (avg <= 0f) 0f else firstVisibleItemIndex * avg + firstVisibleItemScrollOffset
    if (distance <= 1f) {
        animateScrollToItem(0)
        return
    }
    val duration = (380 + (distance / 4f).toInt()).coerceIn(380, 820)
    animateScrollBy(
        -distance,
        androidx.compose.animation.core.tween(
            durationMillis = duration,
            // DecelerateInterpolator(1.7) 的同一条曲线：1 - (1-t)^1.7
            easing = androidx.compose.animation.core.Easing { t ->
                1f - (1f - t).toFloat().pow(1.7f)
            },
        ),
    )
    // 估算偏小 / 滚动被夹住时的兜底：直接落到第 0 项
    if (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0) animateScrollToItem(0)
}
