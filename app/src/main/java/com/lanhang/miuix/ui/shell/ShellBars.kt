package com.lanhang.miuix.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.LanhangIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Weeks
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏的**要显示什么**（值相等 = 不用重画）。
 *
 * 这里刻意把标题/副标题/左右按钮都收进一个不可变快照：文字交叉淡化时新旧两份会同时在场，
 * 退场那一份如果还去读 [ShellState]，会在半路突然变成新标题。
 */
private data class TopBarSpec(
    val title: String,
    val subtitle: String,
    val large: Boolean,
    val hasBack: Boolean,
    val hasHome: Boolean,
    val actionText: String?,
    val actionIcon: androidx.compose.ui.graphics.vector.ImageVector?,
)

/** 大标题栏高度（含副标题与上下留白） */
private val BAR_LARGE_HEIGHT = 96.dp

/** 收起后的小标题栏高度（与原来 Miuix SmallTopAppBar 的视觉高度对齐） */
private val BAR_SMALL_HEIGHT = 56.dp
private val TITLE_LARGE_SP = 32.sp
private val TITLE_SMALL_SP = 18.sp
private val TITLE_START_PADDING = 20.dp
private val TITLE_TOP_PADDING = 12.dp
private val SUBTITLE_SP = 13.sp
private val SUBTITLE_HEIGHT = 18.dp

/**
 * 顶栏动画的**唯一**规范（2026-10-26 第十一轮统一）。
 *
 * 用户要求：「所有顶栏相关动画也统一成一份代码，参考「我的」页面进入和退出二级菜单时的效果，
 * 以后任何页面任何场景都复用这一段代码，以便未来统一管理」。
 *
 * 所以顶栏只剩**一种**行为，不再有「瞬时 / 收放 / 淡切」三个档位，也不再由调用方挑：
 *
 *  · 高 96dp ↔ 56dp、字号 32sp ↔ 18sp、副标题与左右键透明度、标题横向居中量，
 *    全部由**同一个 0..1 进度**插值 —— 文字缩放和顶栏收放永远是同一个数算出来的，不可能不同步。
 *  · 进度用 [NavMotion.DURATION_MS] + [NavMotion.Easing]，与页面转场同拍：
 *    二级页推进/退回、切 Tab、切课表，顶栏走的都是同一条 tween。
 *  · 标题文字用同一时长交叉淡化；标题变了但形态没变（异步拿到姓名/群名）时，
 *    就只剩这段淡化 —— 不需要单独一条「只淡切」的分支。
 *  · 首次组合时 `Animatable` 的初值就是目标值，因此冷启动不会凭空播一段动画。
 */
object BarAnimation {
    /** 与页面转场同一个时长 */
    const val DURATION_MS = com.lanhang.miuix.ui.miuix.NavMotion.DURATION_MS

    /** 与页面转场同一条曲线 */
    val Easing = com.lanhang.miuix.ui.miuix.NavMotion.Easing
}

/**
 * Miuix 顶栏。
 *
 * **2026-10-26 第九轮重写**（用户三条一起反馈：方向反了、文字和高度不同步、切 Tab 别伸缩）：
 *
 *  · 整条顶栏**只由一个 0..1 的进度 collapse 画**：高度、字号、副标题透明度、左侧按键透明度、
 *    标题横向居中位移全部按它插值。所以在任何时刻，「文字缩小」和「顶栏收缩」都是同一个数算出来的，
 *    不可能不同步（第 4 条）。
 *  · 顶栏**不再做横向位移**：以前推入/返回会把整条顶栏横移，那份位移和页面自己的 forward
 *    是两条独立状态，抢跑一次就出现「顶栏向右、内容向左」（第 2 条，偶发）。现在顶栏只做收放，
 *    方向对不对全交给内容侧那一个 miuixPushTransform。
 *  · 第十一轮（2026-10-26）再往前一步：**所有**场景（推入/退回二级页、切 Tab、切课表）
 *    都播同一段收放，档位枚举已删除，参数只剩 [BarAnimation]。
 *
 * @param large 是否是「主 Tab 大标题」形态（我的 / 工作 / 工作圈）；课表与二级页是紧凑形态。
 */
@Composable
fun LanhangTopBar(state: ShellState, large: Boolean) {
    val spec = TopBarSpec(
        title = state.title,
        subtitle = state.subtitle,
        large = large,
        hasBack = state.backAction != null,
        hasHome = state.homeAction != null,
        actionText = state.actionText,
        actionIcon = state.actionIcon,
    )
    // 目标：0 = 大标题形态，1 = 紧凑形态
    val target = if (large) 0f else 1f
    // 滚动档：只有「大标题形态且没有返回键」的一级页才吃列表滚动
    val scrollTerm = if (spec.large && !spec.hasBack) ShellScroll.progress else 0f
    // ★ 全应用唯一的顶栏收放实现（第四十五轮重写，见 rememberBarCollapse）
    val collapse = rememberBarCollapse(target = target, scroll = scrollTerm)
    TopBar(
        spec = spec,
        collapse = collapse,
        onBack = state.backAction ?: {},
        onHome = state.homeAction ?: {},
        onAction = state.onAction ?: {},
    )
}

/**
 * ★ 顶栏「收缩 / 扩张」的唯一实现（全应用只有这一份）。
 *
 * **2026-10-28 第四十五轮重写**。用户两条反馈：
 * 「浅色下顶栏处在中间态时进二级页，顶栏会先弹到完全展开再收缩」、
 * 「深色下顶栏状态改变依旧有严重拖影，参照浅色逻辑重新设计所有顶栏动画，统一成一个实现」。
 *
 * 旧实现有两份 `Animatable`（形态档 `morph` + 收放档），再取 `max(shape, scroll)`
 * 拼出最终值，于是**进入二级页那一帧**：scroll 项瞬间归零、`morph` 还停在上一页的 0，
 * 拼出来的值就是 0（完全展开）—— 无论上一页滚到哪里，都必须先弹回 96dp 再重收一遍。
 * 这就是「中间态进二级页先放大再缩小」的物理来源。
 *
 * 现在只有**一个** `Animatable`，它直接就是画出来的收放度：
 *  · 形态档（进/退二级页、切课表）变化 → 从**当前值** tween 到目标，绝不回 0 重来；
 *  · 手指滚动 → 逐帧 `snapTo`，跟上手指（老规则 1，别改）；
 *  · 换 Tab → 整档跳变，补一段与页面转场同拍的 tween（老规则 2，别改）。
 *
 * @param target 形态档：0 = 大标题，1 = 紧凑条
 * @param scroll 滚动档：一级页往下滚时收起的程度（0..1），手指挥动时逐帧变化
 */
@Composable
fun rememberBarCollapse(target: Float, scroll: Float): Float {
    val anim = remember { Animatable(target, 1f) }
    var seenTarget by remember { mutableFloatStateOf(target) }
    var seenEnv by remember { mutableIntStateOf(ShellScroll.activeTab) }
    LaunchedEffect(target, scroll) {
        val desired = maxOf(target, scroll)
        val env = ShellScroll.activeTab
        when {
            env != seenEnv -> {
                // 换 Tab：整档跳变 → 与页面转场同拍的 tween
                seenEnv = env
                seenTarget = target
                if (kotlin.math.abs(anim.value - desired) > 0.002f) {
                    anim.animateTo(desired, tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
                }
            }
            target != seenTarget -> {
                // 形态档变了：从**当前画出来的收放度**接着走（第四十五轮的核心改动）
                seenTarget = target
                if (kotlin.math.abs(anim.value - desired) < 0.002f) {
                    anim.snapTo(desired)
                } else {
                    anim.animateTo(desired, tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
                }
            }
            kotlin.math.abs(anim.value - desired) > 0.002f -> {
                // 同一页的手指滚动：立刻跟上，不许补动画（否则顶栏跟不上手）
                anim.snapTo(desired)
            }
        }
    }
    return anim.value
}

/**
 * 顶栏本体：一个自绘的 Box，高度 = lerp(96dp, 56dp, collapse)，
 * 标题字号 = lerp(32sp, 18sp, collapse)，所有元素都由 [collapse] 驱动。
 */
@Composable
private fun TopBar(
    spec: TopBarSpec,
    collapse: Float,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onAction: () -> Unit,
) {
    val density = LocalDensity.current
    var blockSize by remember { mutableStateOf(IntSize.Zero) }
    val barHeight = lerp(BAR_LARGE_HEIGHT, BAR_SMALL_HEIGHT, collapse)
    val titleSize = lerp(TITLE_LARGE_SP, TITLE_SMALL_SP, collapse)
    // 上面留出状态栏（自绘顶栏不会自己加，否则大标题会压到状态栏时钟上）
    // clip：过渡期间这个 Box 比 56dp 还矮，子节点（56dp 高的按键行、按 96dp 算的标题位移）
    // 会溢出边界；不裁的话溢出的像素会在深色底上留下一条白影 —— 即用户说的「拖影」。
    // background：Compose 每帧把整块顶栏重画一遍，不依赖外层 View 的背景（那是按需失效的）。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(barHeight)
            .background(MiuixTheme.colorScheme.background)
            .graphicsLayer { clip = true }
    ) {
        val totalW = with(density) { maxWidth.toPx() }
        // 左侧按键（首页 / 返回）：只在紧凑形态出现，跟着进度一起淡入淡出
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .height(BAR_SMALL_HEIGHT)
                .graphicsLayer { alpha = collapse },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (spec.hasHome) {
                IconButton(onClick = onHome) {
                    Icon(
                        imageVector = LanhangIcons.Home,
                        contentDescription = stringResource(R.string.common_home),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
            if (spec.hasBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        // 标题块：大标题贴左下单排，紧凑态横向居中、垂直居中
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = TITLE_START_PADDING)
                .onSizeChanged { blockSize = it }
                .graphicsLayer {
                    if (blockSize.height > 0) {
                        val padStart = TITLE_START_PADDING.toPx()
                        val yLarge = BAR_LARGE_HEIGHT.toPx() - blockSize.height - TITLE_TOP_PADDING.toPx()
                        val ySmall = (BAR_SMALL_HEIGHT.toPx() - blockSize.height) / 2f
                        translationX = lerp(0f, totalW / 2f - padStart - blockSize.width / 2f, collapse)
                        translationY = lerp(yLarge, ySmall, collapse)
                    }
                },
        ) {
            // 2026-10-28 第三十二轮（用户：「深色下工作圈看图返回 → 桌面 → 小组件进来，
            // 顶栏还是叠加在一起，而且所有顶栏都显示异常」）：
            // 这里原来是 AnimatedContent。标题被连续改写时（课表 → 工作圈 → 课表），
            // 它会同时保留「出场」和「入场」两份内容，两份都是同一个坐标上的 Text ——
            // 动画一旦被打断就两份同时可见，屏幕上就是「课表」和「工作圈」叠成一团。
            // 现在**组合里只存在一个 Text**：字符串直接换代，只对这一个节点的字色做一次淡入。
            // 不再有第二份标题，叠加在结构上就不可能发生（深浅色都一样）。
            // 2026-10-28 第三十三轮（用户：「深色下从小组件进来课表会闪动一下」）：
            // 上面这次淡入在**首次组合**时也会跑一遍 —— 进页面第一帧标题只有 25% 不透明度，
            // 然后 220ms 亮回来，看起来就是顶栏「闪了一下」。首帧直接给满值，
            // 只有标题真的被改写（切 Tab / 进二级页）时才做淡入。
            val titleFade = remember { Animatable(1f) }
            var titleSeen by remember { mutableStateOf(false) }
            LaunchedEffect(spec.title) {
                if (!titleSeen) {
                    titleSeen = true
                    titleFade.snapTo(1f)
                } else {
                    titleFade.snapTo(0.25f)
                    titleFade.animateTo(1f, tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
                }
            }
            Text(
                text = spec.title,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = titleFade.value),
                maxLines = 1,
                softWrap = false,
            )
            if (spec.subtitle.isNotBlank()) {
                Spacer(Modifier.height(lerp(4.dp, 0.dp, collapse)))
                Box(
                    modifier = Modifier
                        .height(lerp(SUBTITLE_HEIGHT, 0.dp, collapse))
                        .alpha(1f - collapse),
                ) {
                    Text(
                        text = spec.subtitle,
                        fontSize = SUBTITLE_SP,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        // 右上角动作：图标（工作圈的搜索）或文字（二级页的「发表」），二者互斥
        val icon = spec.actionIcon
        val label = spec.actionText
        if (icon != null || label != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(BAR_SMALL_HEIGHT)
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    IconButton(onClick = onAction) {
                        Icon(
                            imageVector = icon,
                            contentDescription = stringResource(R.string.common_search),
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                } else if (label != null) {
                    TextButton(text = label, onClick = onAction)
                }
            }
        }
    }
}

/**
 * Miuix 底栏：`NavigationBar` + 四个 `NavigationBarItem`。
 *
 * 图标取自 miuix-icons（Weeks / Notes / Community / Settings），
 * 选中态由 Miuix 自己处理（加粗 + 主题色 + 透明度动画）。
 */
/** 双击判定窗口：两次点击间隔小于这个值就算「双击」（Miuix 底栏没有自己的双击回调） */
private const val DOUBLE_TAP_MS = 320L

@Composable
fun LanhangBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    /** 双击某个 Tab：切到该页 + 把页面滚回顶部（由 MainActivity 派发） */
    onScrollTop: (Int) -> Unit = {},
) {
    val items = bottomBarItems()
    // 自己记「上一次点的是哪个 Tab、什么时候点的」来合成双击：NavigationBarItem 只给单击
    var lastTapIndex by remember { mutableIntStateOf(-1) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = {
                    val now = android.os.SystemClock.uptimeMillis()
                    val isDouble = index == lastTapIndex && now - lastTapAt <= DOUBLE_TAP_MS
                    lastTapIndex = index
                    // 双击后把时间清零，避免「第三下」被算成第二组双击
                    lastTapAt = if (isDouble) 0L else now
                    if (isDouble) onScrollTop(index) else onSelect(index)
                },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}

private data class BottomBarItem(val icon: ImageVector, val label: String)

@Composable
private fun bottomBarItems(): List<BottomBarItem> = listOf(
    BottomBarItem(MiuixIcons.Weeks, stringResource(R.string.tab_schedule)),
    BottomBarItem(MiuixIcons.Tasks, stringResource(R.string.tab_work)),
    BottomBarItem(MiuixIcons.Community, stringResource(R.string.tab_moments)),
    BottomBarItem(MiuixIcons.ContactsCircle, stringResource(R.string.tab_mine)),
)

/** 供 `RowScope` 内直接使用（预留：某些页面需要在导航栏里塞额外按钮）。 */
@Composable
fun RowScope.LanhangNavigationSpacer() = Unit
