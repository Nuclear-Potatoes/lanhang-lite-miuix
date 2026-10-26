package com.lanhang.miuix.ui.miuix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全应用**唯一**的页面转场参数表。
 *
 * 2026-10-26 第十轮（用户：「把应用内所有涉及过渡动画的部分全部重写，类似的场景归为一段代码…
 * 二级页面从右侧向左无透明度淡入滑入，主界面向左移动 24% 并 18% 压暗，二级页面仅在动画过程中
 * 裁切屏幕大小圆角，二级菜单内的所有元素都要一起移动」）。
 *
 * 以前同一个「二级页推入」在不同地方有三套实现（`AnimatedContent` + slide、工作圈发表页的
 * 600ms 临界阻尼弹簧 + 25% 视差淡入、标签页的 View 级 translation），几何、时长、曲线各不相同，
 * 于是同一个动作在不同页面观感不一样，还经常出现「一半在动一半不动」。
 * 现在只留这一份常量 + 一个 [MiuiNavStack]，要改手感只改这里。
 *
 * 几何（与 Miuix `NavTransitions.MiuixDefault` 同一套深度转场语言）：
 *  · 前景页（推入的二级页）整幅从右侧滑到位，**不动透明度**（淡入会让纸张看起来是半透明的）
 *  · 背景页（一级页）向左让出 24% 屏宽，并压暗 18%
 *  · **不做圆角裁剪**（用户第十一轮：转场不要圆角；屏幕圆角是系统的事，页面不该自己裁）
 *  · 整页一起位移：位移加在页面的 `graphicsLayer` 上，页内所有元素天然同方向、同节奏
 */
object NavMotion {
    /** 转场时长（ms）：Miuix 的深度转场是 300ms 量级，比 280ms 略稳，比旧弹簧的 600~900ms 利落 */
    const val DURATION_MS = 300

    /** 统一曲线：标准缓入缓出（不要用弹簧，弹簧被返回键打断时会停在半路） */
    val Easing = FastOutSlowInEasing

    /** 上级页横向让位比例（屏宽 24%） */
    const val PARENT_TRAVEL = 0.24f

    /** 上级页压暗强度（18%） */
    const val PARENT_DIM = 0.18f

}

/**
 * 页面栈的**唯一**转场容器，替代各页面里手写的 `AnimatedContent`。
 *
 * 用法与 `AnimatedContent(targetState = …)` 等价：`key` 变了就播一次转场。
 *
 * 方向有两种给法，二选一：
 *  · [forward] 显式给（页面自己知道「这次是点进还是返回」，如「我的」的 `uiState.forward`）；
 *  · 给 [depthOf] 让容器**自己比较新旧 key 的深度**（页面栈只表达「栈里有哪几层」时用这个）。
 *    传 `null` 的 [forward] + 一个 depthOf 即可，容器内部记住上一次的 key 现算。
 *
 * 实现要点（都踩过坑）：
 *  · 方向只有两个来源：[forward] 显式传入，或 [depthOf] 比较新旧 key —— 内容侧不需要再算。
 *    第十一轮修通讯录：原来通讯录用 `path.size >= lastDrillSize`（SideEffect 里更新）判方向，
 *    只要状态在一次转场中间被重组合一次，`lastDrillSize` 就被提前写成新值，方向翻成「推入」，
 *    于是返回时看到的是进入的方向（用户：「通讯录返回动画方向反了」）。现在深度比较放进容器内部，
 *    用 `remember` 记住上一次真正播过的 key，不再依赖调用侧的可变状态。
 *  · `leaving` 只在动画期间存在，动画结束立刻置空，容器里永远最多两个页面。
 *  · 顺序是「先 `snapTo(0)` 再换 `key`」：反过来的话会有一帧按旧进度（=1）把新页画在最终位置，闪一下。
 *  · 背景页要自己铺底色（[pageBackground]），否则透明页会让下面那一页透上来。
 */
@Composable
fun MiuiNavStack(
    key: Any,
    forward: Boolean? = null,
    modifier: Modifier = Modifier,
    pageBackground: Color = MiuixTheme.colorScheme.background,
    /**
     * 只有 [forward] 为 null 时才用到：把 key 换算成「层级深度」，新 key 更深 = 推入。
     * 默认所有 key 同深度（= 视为推入），所以需要方向的页面必须显式给 [forward] 或 [depthOf]。
     */
    depthOf: (Any) -> Int = { 1 },
    content: @Composable (Any) -> Unit,
) {
    var shown by remember { mutableStateOf(key) }
    var leaving by remember { mutableStateOf<Any?>(null) }
    // 方向在「key 真正变化的那一次」定下来，之后这次转场全程不变
    var dir by remember { mutableStateOf(forward ?: true) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(key) {
        if (key == shown) return@LaunchedEffect
        dir = forward ?: (depthOf(key) > depthOf(shown))
        leaving = shown
        progress.snapTo(0f)
        shown = key
        progress.animateTo(1f, tween(NavMotion.DURATION_MS, easing = NavMotion.Easing))
        // 收尾：把退出的那一页从组合里摘掉
        leaving = null
    }
    val goingForward = dir

    // 前景 = 上面那一张纸（推入时的二级页 / 返回时正在退出的二级页）
    // 背景 = 下面那一张纸（一级页）
    val backgroundPage = if (goingForward) leaving else shown
    val foregroundPage = if (goingForward) shown else leaving

    // p 永远是 0→1。推入：父页 0→-24%、子页 +1W→0、压暗 0→18%。
    // 返回：完全镜像 —— 子页 0→+1W、父页 -24%→0、压暗 18%→0。
    // 两句话都写成显式分支，因为「镜像」用同一个 f 反算过一次，父页会永远停在 -24% 回不来。
    val p = progress.value
    val parentFraction = if (goingForward) {
        -NavMotion.PARENT_TRAVEL * p
    } else {
        -NavMotion.PARENT_TRAVEL * (1f - p)
    }
    val childFraction = if (goingForward) 1f - p else p
    val dimAlpha = NavMotion.PARENT_DIM * (if (goingForward) p else 1f - p)
    val animating = leaving != null && p < 1f

    Box(modifier.fillMaxSize()) {
        backgroundPage?.let { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = parentFraction * size.width }
            ) {
                Box(Modifier.fillMaxSize().background(pageBackground)) { content(page) }
            }
        }
        // 压暗层夹在两张纸中间：只压背景页，不影响前景页
        if (animating) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dimAlpha }
                    .background(Color.Black)
            )
        }
        foregroundPage?.let { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = childFraction * size.width
                    }
            ) {
                Box(Modifier.fillMaxSize().background(pageBackground)) { content(page) }
            }
        }
    }
}
