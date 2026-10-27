package com.lanhang.miuix.ui.miuix

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 风格对话框工具（2026-10-24 用户反馈「对话框没有改成 miuix 格式」后新增）。
 *
 * 为什么不用 Miuix 的 `OverlayDialog`：它必须挂在 Miuix 的 Scaffold 组合里，而本项目的
 * 页面宿主是 Fragment / Activity（ComposeView 各自独立），拿不到同一个 Scaffold。
 * 所以这里用「系统 Dialog 窗口 + 内部 Compose 渲染 Miuix 卡片」的方式：
 * 系统窗口负责遮罩与居中，卡片本身完全由 Miuix 组件画（`Card` + `Text` + `TextButton`），
 * 视觉上就是 HyperOS 那套圆角弹窗。
 */

/** 一条对话框的数据描述：标题 / 正文 / 列表 / 键值行 / 按钮 */
class MiuixDialogSpec(
    val title: String? = null,
    val summary: String? = null,
    /** 单选列表（周次、学期、语言…）；点中即回调并关闭 */
    val items: List<String> = emptyList(),
    val selectedIndex: Int = -1,
    val onItemClick: ((Int) -> Unit)? = null,
    /** 键值行（课程详情用） */
    val rows: List<Pair<String, String>> = emptyList(),
    /** 按钮：标签 + 动作（点完自动关闭） */
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
    /** 列表最大高度占屏幕比例，超出滚动 */
    val listMaxHeightFraction: Float = 0.46f,
    /** summary 太长时（诊断报告）允许内部滚动 */
    val scrollableSummary: Boolean = false,
    /** 是否可点外部/返回关闭 */
    val cancelable: Boolean = true,
)

@Composable
private fun DialogCardBody(spec: MiuixDialogSpec, dismiss: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 26.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (spec.title != null || spec.summary != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 22.dp,
                            bottom = if (spec.items.isEmpty() && spec.rows.isEmpty()) 20.dp else 12.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    spec.title?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    spec.summary?.let { body ->
                        if (spec.title != null) Spacer(Modifier.height(4.dp))
                        val bodyModifier = if (spec.scrollableSummary) {
                            Modifier
                                .heightIn(max = screenHeightDp * 0.5f)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                        Text(
                            text = body,
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = bodyModifier,
                        )
                    }
                }
            }

            if (spec.rows.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    spec.rows.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.body2,
                                color = scheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = value,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }

            if (spec.items.isNotEmpty()) {
                // 2026-10-25 用户要求「所有对话框都参照成绩年份切换」：
                // 选中项必须一打开就在可视范围内（原来总是从第 1 项开始，长列表要自己找）。
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (spec.selectedIndex - 1).coerceAtLeast(0),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeightDp * spec.listMaxHeightFraction),
                ) {
                    itemsIndexed(spec.items) { index, label ->
                        val selected = index == spec.selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 参考图：选中项是「整行淡蓝色带」，不使用分割线
                                .background(
                                    if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent
                                )
                                .clickable {
                                    dismiss()
                                    spec.onItemClick?.invoke(index)
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.body1,
                                color = if (selected) scheme.primary else scheme.onSurface,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                            Spacer(Modifier.weight(1f))
                            if (selected) {
                                Text(
                                    text = "\u2713",
                                    style = MiuixTheme.textStyles.body1,
                                    color = scheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (spec.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    spec.actions.forEachIndexed { index, action ->
                        // 多个按钮时，最后一个当主操作（主色填充），其余为浅灰次要按钮
                        val isPrimary = spec.actions.size > 1 && index == spec.actions.lastIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                .background(
                                    if (isPrimary) scheme.primary
                                    else scheme.onSurfaceVariantSummary.copy(alpha = 0.10f)
                                )
                                .clickable {
                                    dismiss()
                                    action.second()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = action.first,
                                style = MiuixTheme.textStyles.body1,
                                color = if (isPrimary) Color.White else scheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * Dialog 自己的 window 没有 view-tree owner，ComposeView 一挂上去就会抛
 * `IllegalStateException: ViewTreeLifecycleOwner not found from DecorView@...`
 * （2026-10-24 用户实测：所有该弹对话框的地方点击后必崩）。
 * 这里把宿主 Activity（ComponentActivity 同时是 LifecycleOwner / ViewModelStoreOwner /
 * SavedStateRegistryOwner）装到 ComposeView 上，跟 `DisposeOnDetachedFromWindow` 配合，
 * 对话框关掉时组合照样被丢弃，不会泄漏。
 */
/**
 * 前台 Activity 的登记处（2026-10-27 新增）。
 *
 * 为什么需要：所有对话框都是「系统 Dialog 窗口 + 内部 Compose」，而 `Dialog(context)` 的窗口
 * token 来自 context 所属的 Activity。只要调用方传了 `AppContext.instance`（Application），
 * token 就是 null，`dialog.show()` 立刻抛
 * `WindowManager$BadTokenException: Unable to add window -- token null is not valid`。
 * 用户实测：「设置 → 关于 → 诊断与日志」点了必崩。
 *
 * 这里由 [App] 注册的 ActivityLifecycleCallbacks 维护「当前 resumed 的 Activity」，
 * 对话框工厂在调用方给错 context 时用它兜底 —— 单点兜底，任何页面以后再传错也不会崩。
 * 用弱引用，避免泄漏已销毁的 Activity。
 */
object DialogHost {
    @Volatile
    private var ref: java.lang.ref.WeakReference<ComponentActivity>? = null

    fun onResumed(activity: ComponentActivity) {
        ref = java.lang.ref.WeakReference(activity)
    }

    fun onPaused(activity: ComponentActivity) {
        if (ref?.get() === activity) ref = null
    }

    fun current(): ComponentActivity? = ref?.get()
}

/** 对话框宿主：优先从传入 context 里找 Activity，找不到就用 [DialogHost] 登记的前台 Activity */
private fun resolveDialogHost(context: Context): ComponentActivity? =
    context.findComponentActivity() ?: DialogHost.current()

private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return c as? ComponentActivity
}

private fun ComposeView.installViewTreeOwners(context: Context) {
    val owner = context.findComponentActivity() ?: return
    setViewTreeLifecycleOwner(owner)
    setViewTreeViewModelStoreOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
}


/** 面板左右各留的边距（与窗口宽度 screen-24dp + 6dp 内边距对齐） */
private val CARD_SIDE_MARGIN = 18.dp

/**
 * 底部安全距离（自适应，不写死数值）。
 *
 * 2026-10-25 用户反馈：「我的手机圆角较大，对话框距离底部过近会导致圆角显示不全」，
 * 并要求做成自适应。这里取两者的较大值：
 *  · 系统栏 / 手势条 / 刘海等系统安全区（[WindowInsets.safeDrawing]）；
 *  · 屏幕物理圆角半径（API 31+ `WindowInsets.getRoundedCorner()`，左右下角取大者）。
 * 再额外留 [extra] 的呼吸间距，任何机型/任何导航方式都自动适配。
 */
@Composable
fun rememberBottomSafeInsetDp(extra: Dp = 6.dp): Dp {
    val density = LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    var cornerPx by remember { mutableStateOf(0) }
    DisposableEffect(view) {
        // 屏幕物理圆角只能从 View 的 WindowInsets 读（WindowInsetsCompat 没有这个 API）
        val readCorners = {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val wi = view.rootWindowInsets
                cornerPx = if (wi == null) 0 else maxOf(
                    wi.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
                    wi.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
                )
            }
        }
        view.post { readCorners() }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            view.post { readCorners() }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
        onDispose { androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, null) }
    }
    // 2026-10-25 用户再次明确：**不要抬到 Tab 栏之上**，只需保证面板底部两个圆角
    // 不被屏幕自身的圆角切掉。所以不用系统栏 inset（那是给导航条让位的），
    // 而是按几何关系算：面板左右各留 [CARD_SIDE_MARGIN]，圆角半径 R 的屏幕，
    // 在距边距 m 处，安全高度 = R - sqrt(R² - (R - m)²)。
    val marginPx = with(density) { CARD_SIDE_MARGIN.toPx() }
    val r = cornerPx.toDouble()
    val m = marginPx.toDouble()
    val neededPx = if (r > m) {
        (r - kotlin.math.sqrt((r * r - (r - m) * (r - m)).coerceAtLeast(0.0))).toFloat()
    } else {
        0f
    }
    return with(density) { (neededPx + extra.toPx()).toDp() }
}


/**
 * 输入型对话框（回复评论等）：和工作圈/学年选择完全同一套窗口、遮罩、进出场动画。
 *
 * 2026-10-25 用户反馈：①工作圈回复框的遮罩只盖住顶栏下方，顶栏没被压暗；
 * ②也没有进出场动画。根因是它原来是页面内浮层，不是真正的 Dialog 窗口。
 * 现在统一走这里的 Dialog 实现（全屏窗口 → 遮罩覆盖整个屏幕，含顶栏）。
 */
fun showMiuixInput(
    context: Context,
    title: String?,
    placeholder: String?,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
) {
    // 宿主必须是 Activity（拿不到就用 DialogHost 登记的前台 Activity，见文件头部说明）
    val host = resolveDialogHost(context)
    val dialog = Dialog(host ?: context)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    var text = ""
    val view = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        installViewTreeOwners(host ?: context)
        setContent {
            LanhangMiuixTheme(darkTheme = (host ?: context).isNightMode()) {
                var visible by remember { mutableStateOf(false) }
                val dimHolder = remember { floatArrayOf(0f) }
                var dimAnim by remember { mutableStateOf<android.animation.ValueAnimator?>(null) }
                // 对话框被销毁（切主题 recreate、页面回收）时立刻停掉遮罩动画
                DisposableEffect(Unit) { onDispose { dimAnim?.cancel() } }
                fun animateDim(target: Float, ms: Long, onEnd: (() -> Unit)? = null) {
                    if (!dialog.isShowing) { onEnd?.invoke(); return }
                    val anim = android.animation.ValueAnimator.ofFloat(dimHolder[0], target)
                    anim.duration = ms
                    anim.interpolator = android.view.animation.LinearInterpolator()
                    anim.addUpdateListener {
                        dimHolder[0] = it.animatedValue as Float
                        // 2026-10-25 修「设置里把外观切成亮色/暗色会崩溃」：
                        // 崩溃栈是 Window.setDimAmount → IllegalArgumentException
                        // "View ... not attached to window manager"。切主题会走 recreate()，
                        // 对话框的 DecorView 已经脱离窗口管理器，而遮罩动画还在逐帧改 Window。
                        // 现在每帧先确认窗口还挂着，并且兜住竞态里的异常。
                        val win = dialog.window
                        if (dialog.isShowing && win != null && win.decorView.isAttachedToWindow) {
                            runCatching { win.setDimAmount(dimHolder[0]) }
                        }
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            onEnd?.invoke()
                        }
                    })
                    dimAnim = anim
                    anim.start()
                }
                LaunchedEffect(Unit) {
                    visible = true
                    animateDim(DIM_AMOUNT, DIALOG_ENTER_MS.toLong())
                }
                val dismissAnimated: () -> Unit = {
                    if (visible) {
                        visible = false
                        animateDim(0f, DIALOG_EXIT_MS.toLong()) { runCatching { dialog.dismiss() } }
                    }
                }
                BackHandler(enabled = true) { dismissAnimated() }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .pointerInput(Unit) { detectTapGestures(onTap = { dismissAnimated() }) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp)
                            .padding(bottom = rememberBottomSafeInsetDp())
                            .pointerInput(Unit) { detectTapGestures { } },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        AnimatedVisibility(
                            visible = visible,
                            enter = slideInVertically(
                                animationSpec = tween(DIALOG_ENTER_MS, easing = FastOutSlowInEasing),
                                initialOffsetY = { it },
                            ) + fadeIn(tween(DIALOG_ENTER_MS)),
                            exit = slideOutVertically(
                                animationSpec = tween(DIALOG_EXIT_MS, easing = FastOutLinearInEasing),
                                targetOffsetY = { it },
                            ) + fadeOut(tween(DIALOG_EXIT_MS)),
                        ) {
                            val scheme = MiuixTheme.colorScheme
                            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 26.dp) {
                                Column(Modifier.fillMaxWidth()) {
                                    if (title != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 14.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = title,
                                                style = MiuixTheme.textStyles.title4,
                                                fontWeight = FontWeight.SemiBold,
                                                color = scheme.onSurface,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                    TextField(
                                        value = text,
                                        onValueChange = { text = it },
                                        label = placeholder.orEmpty(),
                                        minLines = 1,
                                        maxLines = 4,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                                .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.10f))
                                                .clickable { dismissAnimated() },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = dismissText,
                                                style = MiuixTheme.textStyles.body1,
                                                color = scheme.onSurface,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                                .background(scheme.primary)
                                                .clickable {
                                                    val body = text.trim()
                                                    if (body.isNotEmpty()) onConfirm(body)
                                                    dismissAnimated()
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = confirmText,
                                                style = MiuixTheme.textStyles.body1,
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    dialog.setContentView(view)
    dialog.setCanceledOnTouchOutside(false)
    dialog.setCancelable(true)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0f)
        setWindowAnimations(0)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    dialog.setOnShowListener {
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    // 没有 Activity 宿主时绝不 show()：宁可这一个弹窗不出现，也不能让整个应用崩掉
    if (host != null) {
        dialog.show()
    } else {
        android.util.Log.e("MiuixDialog", "no activity host, skip show")
    }
}

/** 弹出 Miuix 风格对话框；返回 Dialog 便于调用方自行 dismiss */
fun showMiuixDialog(context: Context, spec: MiuixDialogSpec): Dialog {
    // 宿主必须是 Activity（拿不到就用 DialogHost 登记的前台 Activity，见文件头部说明）
    val host = resolveDialogHost(context)
    val dialog = Dialog(host ?: context)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val view = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        installViewTreeOwners(host ?: context)
        setContent {
            LanhangMiuixTheme(darkTheme = (host ?: context).isNightMode()) {
                // 2026-10-25 用户反馈「对话框动画非常生硬」：之前卡片是「啪」一下出现。
                // 现在照 Miuix 面板的观感做：卡片从下方 1/6 高度弹上来 + 缩放 0.94→1 + 渐显，
                // 遮罩同时 0 →0.42 渐深；退场反向并延后真正 dismiss，避免闪断。
                var visible by remember { mutableStateOf(false) }
                // 遮罩自己做 0 → 0.45 → 0 的动画：退出时遮罩和卡片一起淡出，
                // 否则 dismiss 那一瞬遮罩「啪」地消失，就是用户看到的闪一下。
                val dimHolder = remember { floatArrayOf(0f) }
                var dimAnim by remember { mutableStateOf<android.animation.ValueAnimator?>(null) }
                // 对话框被销毁（切主题 recreate、页面回收）时立刻停掉遮罩动画
                DisposableEffect(Unit) { onDispose { dimAnim?.cancel() } }
                fun animateDim(target: Float, ms: Long, onEnd: (() -> Unit)? = null) {
                    if (!dialog.isShowing) { onEnd?.invoke(); return }
                    val anim = android.animation.ValueAnimator.ofFloat(dimHolder[0], target)
                    anim.duration = ms
                    anim.interpolator = android.view.animation.LinearInterpolator()
                    anim.addUpdateListener {
                        dimHolder[0] = it.animatedValue as Float
                        // 2026-10-25 修「设置里把外观切成亮色/暗色会崩溃」：
                        // 崩溃栈是 Window.setDimAmount → IllegalArgumentException
                        // "View ... not attached to window manager"。切主题会走 recreate()，
                        // 对话框的 DecorView 已经脱离窗口管理器，而遮罩动画还在逐帧改 Window。
                        // 现在每帧先确认窗口还挂着，并且兜住竞态里的异常。
                        val win = dialog.window
                        if (dialog.isShowing && win != null && win.decorView.isAttachedToWindow) {
                            runCatching { win.setDimAmount(dimHolder[0]) }
                        }
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            onEnd?.invoke()
                        }
                    })
                    dimAnim = anim
                    anim.start()
                }
                LaunchedEffect(Unit) {
                    visible = true
                    animateDim(DIM_AMOUNT, DIALOG_ENTER_MS.toLong())
                }
                val dismissAnimated: () -> Unit = {
                    if (visible) {
                        visible = false
                        animateDim(0f, DIALOG_EXIT_MS.toLong()) { runCatching { dialog.dismiss() } }
                    }
                }
                BackHandler(enabled = spec.cancelable) { dismissAnimated() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 点「对话框以外」= 取消，走同一套原地缩回动画（用户 2026-10-25 要求）
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { dismissAnimated() })
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                  Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        // 自适应底部安全距离（系统栏 / 手势条 / 屏幕圆角半径取较大者）
                        .padding(bottom = rememberBottomSafeInsetDp())
                        // 面板本体的点击要吞掉，否则点标题也会关掉
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(
                        visible = visible,
                        // 参考录屏：面板从屏幕底部整幅上滑 + 渐显（无缩放）；退出下滑约半幅 + 渐隐
                        enter = slideInVertically(
                            animationSpec = tween(DIALOG_ENTER_MS, easing = FastOutSlowInEasing),
                            initialOffsetY = { it },
                        ) + fadeIn(tween(DIALOG_ENTER_MS)),
                        // 退场 = 进场的严格反向：整幅向下滑出屏幕 + 渐隐（不做任何缩放）
                        exit = slideOutVertically(
                            animationSpec = tween(DIALOG_EXIT_MS, easing = FastOutLinearInEasing),
                            targetOffsetY = { it },
                        ) + fadeOut(tween(DIALOG_EXIT_MS)),
                    ) {
                        DialogCardBody(spec = spec, dismiss = dismissAnimated)
                    }
                  }
                }
            }
        }
    }
    dialog.setContentView(view)
    // 外面点击由我们自己的全屏层处理（走回缩动画），交给系统会「直接消失」
    dialog.setCanceledOnTouchOutside(false)
    dialog.setCancelable(spec.cancelable)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        // 2026-10-25 用户反馈：对话框「明显高于底栏」不自然。
        // 贴屏幕真底（允许盖住底部 Tab，用户 2026-10-25 明确要求）
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.42f)
        // 关掉系统窗口级动画：否则 dismiss 时窗口整体还会自己淡出一次，
        // 和我们的「原地缩回」叠在一起，看起来就是「淡出」（用户 2026-10-25 反馈）
        setWindowAnimations(0)
    }
    dialog.setOnShowListener {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels - (dm.density * 24f).toInt()
        val bottomOffset = 0   // 真贴底
        dialog.window?.let { win ->
            win.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            win.attributes = win.attributes.also { it.y = bottomOffset }
        }
    }
    // 没有 Activity 宿主时绝不 show()：宁可这一个弹窗不出现，也不能让整个应用崩掉
    if (host != null) {
        dialog.show()
    } else {
        android.util.Log.e("MiuixDialog", "no activity host, skip show")
    }
    return dialog
}

/** 纯提示（只有一个「知道了」） */
fun showMiuixInfo(context: Context, title: String?, message: String?, confirm: String) {
    showMiuixDialog(
        context,
        MiuixDialogSpec(
            title = title,
            summary = message,
            actions = listOf(confirm to {}),
        ),
    )
}

/** 二次确认 */
fun showMiuixConfirm(
    context: Context,
    title: String?,
    message: String?,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
) {
    showMiuixDialog(
        context,
        MiuixDialogSpec(
            title = title,
            summary = message,
            actions = listOf(dismissText to {}, confirmText to onConfirm),
        ),
    )
}

/** 单选列表 */
fun showMiuixPicker(
    context: Context,
    title: String?,
    items: List<String>,
    selectedIndex: Int,
    cancelText: String,
    onPick: (Int) -> Unit,
) {
    showMiuixDialog(
        context,
        MiuixDialogSpec(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onItemClick = onPick,
            actions = listOf(cancelText to {}),
        ),
    )
}

/** 进度对话框句柄：可随时改文案，`dialog` 用于外部关闭 */
class MiuixProgressDialog internal constructor(
    val dialog: Dialog,
    private val textState: androidx.compose.runtime.MutableState<String>,
) {
    fun setText(value: String) {
        textState.value = value
    }
}

/**
 * 进度对话框（导出课表时用）：Miuix 卡片 + 环形进度 + 一行动态文案 + 取消按钮。
 * 文案通过 [MiuixProgressDialog.setText] 更新，不需要重建 Dialog。
 */
fun showMiuixProgress(
    context: Context,
    title: String?,
    initialText: String,
    cancelText: String,
    onCancel: () -> Unit,
): MiuixProgressDialog {
    // 宿主必须是 Activity（拿不到就用 DialogHost 登记的前台 Activity，见文件头部说明）
    val host = resolveDialogHost(context)
    val dialog = Dialog(host ?: context)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val textState = androidx.compose.runtime.mutableStateOf(initialText)
    val view = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        installViewTreeOwners(host ?: context)
        setContent {
            LanhangMiuixTheme(darkTheme = (host ?: context).isNightMode()) {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 26.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            title?.let {
                                Text(
                                    text = it,
                                    style = MiuixTheme.textStyles.title4,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(14.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InfiniteProgressIndicator(
                                    size = 20.dp,
                                    strokeWidth = 2.5.dp,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = textState.value,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                text = cancelText,
                                onClick = {
                                    dialog.dismiss()
                                    onCancel()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
    dialog.setContentView(view)
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        // 2026-10-25 用户反馈：对话框「明显高于底栏」不自然。
        // 贴屏幕真底（允许盖住底部 Tab）
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.42f)
        // 关掉系统窗口级动画：否则 dismiss 时窗口整体还会自己淡出一次，
        // 和我们的「原地缩回」叠在一起，看起来就是「淡出」（用户 2026-10-25 反馈）
        setWindowAnimations(0)
    }
    dialog.setOnShowListener {
        val w = (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
        val bottomOffset = 0   // 真贴底
        dialog.window?.let { win ->
            win.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.attributes = win.attributes.also { it.y = bottomOffset }
        }
    }
    // 没有 Activity 宿主时绝不 show()：宁可这一个弹窗不出现，也不能让整个应用崩掉
    if (host != null) {
        dialog.show()
    } else {
        android.util.Log.e("MiuixDialog", "no activity host, skip show")
    }
    return MiuixProgressDialog(dialog, textState)
}

/** 对话框遮罩最深值 */
private const val DIM_AMOUNT = 0.42f

/** 进出场时长（毫秒） */
private const val DIALOG_ENTER_MS = 260

/** 退场时长：走完才真正 dismiss，保证遮罩也淡完 */
private const val DIALOG_EXIT_MS = 150
