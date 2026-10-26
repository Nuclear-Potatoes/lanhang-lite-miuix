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
import androidx.compose.foundation.layout.Box
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
    )
    // 目标：0 = 大标题形态，1 = 紧凑形态
    val target = if (large) 0f else 1f
    // 初值 = 目标：首帧不播动画（「瞬时切换」这种档位靠初值就够，不需要额外枚举）
    val morph = remember { Animatable(target, 1f) }
    // 上一帧真正画出来的形态（0 = 大标题、1 = 紧凑）
    val lastCollapse = remember { mutableFloatStateOf(target) }
    LaunchedEffect(target) {
        // 第十二轮（用户反馈）：一级页已经滚到顶栏收起，再点进二级页时，
        // 目标形态虽然是「紧凑」，但 scroll 那一半（只有大标题形态才算）会瞬间归零，
        // 于是顶栏先弹回大标题、再缩回去 —— 用户看到的就是「闪一下、放大再缩小」。
        // 规则：**上一级已经收起的话就不再放大**，直接把进度钉在目标上，只让标题文字交叉淡化。
        if (target >= 0.5f && lastCollapse.floatValue >= 0.995f) {
            morph.snapTo(target)
        } else {
            morph.animateTo(target, tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
        }
    }
    // 上一帧真正画出来的档位（1 = 已收起）
    val latch = lastCollapse.floatValue
    // 已经收起过的页面再往里进：**这一帧就直接画成紧凑态**。
    // 第十二轮只写了 `LaunchedEffect { snapTo }`，不够 —— effect 要等这一帧画完才跑，
    // 而这一帧里 `morph` 还是上一页的 0、scroll 项又随页面切换归零，于是正好闪一帧大标题
    // （用户 2026-10-26：「标题依旧会突然放大一下再缩小」）。这里改成同帧判断。
    // 形态档（0 = 大标题 / 1 = 紧凑）：从已经收起的一级页往里进，**同一帧**就按目标画，
    // 不许等 effect，否则会闪一帧大标题（第十二/十三轮踩过两次）。
    val shape = if (target >= 0.5f && latch >= 0.995f) target else morph.value
    // 滚动档：只有「大标题形态且没有返回键」的一级页才吃列表滚动
    val scrollTerm = if (spec.large && !spec.hasBack) ShellScroll.progress else 0f
    // ★ 收放唯一入口（见文件下方 rememberBarCollapse 的注释）：
    //   全应用所有「顶栏收缩/扩张」的场景——切 Tab、一级→二级、二级→一级、Tab 内滚动——
    //   都只经过这一个函数，页面里不许再自己写任何收放动画。
    val collapse = rememberBarCollapse(shape = shape, scroll = scrollTerm)
    SideEffect { lastCollapse.floatValue = collapse }
    TopBar(
        spec = spec,
        collapse = collapse,
        onBack = state.backAction ?: {},
        onHome = state.homeAction ?: {},
        onAction = state.onAction ?: {},
    )
}

/**
 * ★ 顶栏「收缩 / 扩张」的唯一实现（全应用只有这一份，2026-10-26 第十四轮固化）。
 *
 * 为什么要收成一个函数：顶栏的收放有四个来源，过去分散在多处、各有各的时长和曲线，
 * 于是同一个动作在不同页面观感不一样（用户：「切 tab 时字体变了、顶栏范围没变」「效果割裂」）。
 * 现在全部汇到这里：**输入两个档位，输出一个 0..1 的收放度**，顶栏本体只认这一个数
 * （高度、字号、副标题、按键、标题居中量全部由它插值）。
 *
 * @param shape  形态档：0 = 大标题，1 = 紧凑条。由页面形态决定（`target`），自带 300ms tween。
 * @param scroll 滚动档：一级页往下滚时收起的程度（0..1）。手指滚动是逐帧变化的。
 *
 * 三条硬规则（都是踩坑换来的，别改回去）：
 * 1. **手指滚动即时**：同一页里 `scroll` 每帧变化 → 直接 `snapTo`，不许补动画，否则顶栏跟不上手。
 * 2. **换 Tab 补同拍 tween**：切 Tab 时档位会整档跳变（上一页 1 → 新页 0），硬切就是「闪一下」；
 *    这里按 `ShellScroll.activeTab` 的变化补一段与页面转场同时长、同曲线的 tween，
 *    于是顶栏的高度、字号、内容区起点是**同一个数字**在同一条曲线上动，绝不会「字体动了、范围没动」。
 * 3. **不要用「动画进行中」布尔标志位分流**：effect 一旦被抢跑取消，标志没复位，
 *    之后所有 `snapTo` 都被跳过，顶栏永久不收（第十三轮实测踩到）。
 *    一个 `LaunchedEffect(shape, scroll, env)` 自带取消语义，安全。
 */
@Composable
fun rememberBarCollapse(shape: Float, scroll: Float): Float {
    val goal = maxOf(shape, scroll)
    val env = ShellScroll.activeTab
    val anim = remember { Animatable(goal, 1f) }
    var envSeen by remember { mutableStateOf(env) }
    LaunchedEffect(shape, scroll, env) {
        val g = maxOf(shape, scroll)
        if (env != envSeen) {
            // 换 Tab：整档跳变 → 与页面转场同拍的 tween
            envSeen = env
            anim.animateTo(g, tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
        } else if (kotlin.math.abs(g - anim.value) > 0.002f) {
            // 同一页：手指滚动 / 形态档自身已带 tween → 立刻跟上
            anim.snapTo(g)
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
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(barHeight)) {
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
                    )
                }
            }
            if (spec.hasBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.common_back),
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
            AnimatedContent(
                targetState = spec.title,
                transitionSpec = {
                    fadeIn(tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing)) togetherWith
                        fadeOut(tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
                },
                label = "barTitle",
            ) { title ->
                Text(
                    text = title,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                )
            }
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
        // 右上角文字动作（如「发表」）
        val label = spec.actionText
        if (label != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(BAR_SMALL_HEIGHT)
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = label, onClick = onAction)
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
