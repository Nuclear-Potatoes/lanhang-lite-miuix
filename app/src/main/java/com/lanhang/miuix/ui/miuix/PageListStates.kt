package com.lanhang.miuix.ui.miuix

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * 页面栈里的「每个页面各自一份列表滚动位置」。
 *
 * 2026-10-26 第九轮（用户反馈）：从页面底部进入二级菜单再返回，一级页面会跳回顶部。
 * 根因：`AnimatedContent(targetState = page)` 只组合**当前**那一页，离开的页面整棵子树被销毁，
 * 页面内部的 `rememberLazyListState()` 跟着没了，回来时自然从 0 开始。
 *
 * 解法：把 [LazyListState] 按「页面 key」存在这上面，**挂在这层 AnimatedContent 之外**，
 * 每个页面进来时按 key 取回自己那一份。key 用什么由调用方决定（页面编号 / 下钻路径都行），
 * 只要同一个页面每次进来都算出同一个 key。
 */
@Stable
class PageListStates(private val namespace: String = "") {
    /**
     * 列表状态本身存在**进程级**的 [StickyStates] 里（第十三轮）：
     * Tab 切页是 detach/attach，view 重建后 `remember` 出来的东西会全丢，
     * 而「我的」根页/各列表页的位置必须活着，否则切回来就跳回顶部、顶栏还会闪。
     * [namespace] 用来隔开不同宿主（「我的」栈 / 通讯录 / 工作…），避免 key 撞车。
     */
    private fun nsKey(key: Any): String = if (namespace.isEmpty()) "p:$key" else "$namespace#$key"

    fun of(key: Any): LazyListState =
        StickyStates.listStates.getOrPut(nsKey(key)) { LazyListState() }

    /** 离开某页时的滚动位置（下标 + 偏移），等着数据回来后再补回去 */
    private val savedPositions = HashMap<Any, Pair<Int, Int>>()

    /**
     * 记下这一页现在滚到哪了，在页面被销毁前调用。
     *
     * 为什么光有 [of] 不够：通讯录这类页面的数据是**每次进来重新拉**的，列表会先变空，
     * LazyListState 在列表项变少时会被夹到别的下标上，等数据回来时位置已经丢了。
     * 所以离开时存一份，数据回来后用 [restorePosition] 补回去。
     */
    fun savePosition(key: Any, state: LazyListState) {
        savedPositions[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
    }

    /** 把 [savePosition] 存下的位置补回去（只补一次；没有存档就什么也不做） */
    suspend fun restorePosition(key: Any, state: LazyListState) {
        val p = savedPositions.remove(key) ?: return
        if (state.firstVisibleItemIndex != p.first || state.firstVisibleItemScrollOffset != p.second) {
            state.scrollToItem(p.first.coerceAtLeast(0), p.second)
        }
    }
}

/**
 * **进程级**的列表状态仓库（第十三轮）。
 *
 * 前面 [PageListStates] 解决的是「同一个 Tab 内部，二级页进出时子页面被销毁」的问题，
 * 但那个 map 是 `remember` 出来的：Tab 之间切页走的是 `Fragment.detach()/attach()`，
 * view 被销毁重建、整棵组合重来一遍，`remember` 里的东西全丢 → 用户看到的
 * 「工作页滑到一半，切个 tab 再回来就跳回最顶端，还闪一下」（2026-10-26 反馈）。
 *
 * 这里用**进程级**的实例缓存：同一个 key 永远拿回同一个 [LazyListState] 对象，
 * 视图重建后重新挂上去，滚动位置、正在进行的 fling 都还在。
 * 只存几个列表状态，量极小，不需要清理。
 */
private object StickyStates {
    val listStates = HashMap<String, LazyListState>()

    /**
     * 哪些列表已经完成过「首次落位」（每个进程每张表一次，2026-10-26 第十四轮）。
     * 首次落位 = 数据到位后把还没被用户动过的列表对齐到顶部；
     * 之后切 Tab 回来一律尊重恢复出来的位置，**不许再归顶**（§18.2 的教训）。
     */
    val landed = HashSet<String>()
}

/** 这张粘性列表是否已经完成首次落位（配合 [rememberStickyListState] 用） */
fun stickyListLanded(key: String): Boolean = StickyStates.landed.contains(key)

/** 标记「首次落位」已完成 */
fun markStickyListLanded(key: String) {
    StickyStates.landed.add(key)
}

/** 按 [key] 取一份**跨 Tab 切换存活**的列表滚动状态（工作 / 工作圈 / 我的 等一级列表用） */
@Composable
fun rememberStickyListState(key: String): LazyListState = remember(key) { stickyListStateFor(key) }

/**
 * 非 Composable 版的 [rememberStickyListState]。
 *
 * 2026-10-30：跳二级页前要在 Fragment 的方法里**同步**读一次滚动位置（那时列表还活着），
 * 用不了 `@Composable`，所以把取状态的逻辑抽出来。
 */
fun stickyListStateFor(key: String): LazyListState =
    StickyStates.listStates.getOrPut(key) { LazyListState() }

@Composable
fun rememberPageListStates(namespace: String = ""): PageListStates =
    remember(namespace) { PageListStates(namespace) }
