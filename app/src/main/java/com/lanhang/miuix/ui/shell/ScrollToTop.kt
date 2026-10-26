package com.lanhang.miuix.ui.shell

import androidx.compose.foundation.gestures.animateScrollBy
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
 * 「慢慢滚回顶部」。
 *
 * `LazyListState.animateScrollToItem(0)` 只在首尾之间插值，列表一长就是「唰」地瞬移，
 * 用户明确说太快了。这里改成**按屏翻页**的滚动：一次滚过一个可见项的高度，
 * 段数随距离增长（越远每段略快），总时长控制在 0.4~0.9 秒，
 * 看起来像手指连着往上推了几屏，而不是瞬移。
 */
suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToTopSlow() {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    val pages = firstVisibleItemIndex
    val total = (420 + pages * 55).coerceIn(420, 900)
    val perPage = (total / (pages + 1)).coerceIn(110, 320)
    var guard = 0
    while (firstVisibleItemIndex > 0 && guard++ < 80) {
        val step = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: break
        if (step <= 0) break
        animateScrollBy(
            -step.toFloat(),
            androidx.compose.animation.core.tween(
                durationMillis = perPage,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }
    if (firstVisibleItemScrollOffset > 0) {
        animateScrollBy(
            -firstVisibleItemScrollOffset.toFloat(),
            androidx.compose.animation.core.tween(
                durationMillis = (perPage + 80).coerceAtMost(320),
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }
    // 兜底：可见项高度异常（0 / null）时仍然保证真的回到顶部
    if (firstVisibleItemIndex > 0) animateScrollToItem(0)
}
