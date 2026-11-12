package com.lanhang.miuix.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanhang.miuix.ui.miuix.MiuiNavStack
import com.lanhang.miuix.ui.miuix.NavMotion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

/**
 * 「添加课程」全屏页（2026-10-30 用户：「在课表的右上角添加一个加号键，作为添加课程的按钮，
 * 点击即进入，不需要二次确认，参照这个添加课程的页面做你的ui」）。
 *
 * 结构照参考图：顶部 ✕ / 标题 / ✓，下面按「名称与地点」「星期」「节次」「周次」分卡，
 * 每张卡片是一块浅底圆角块，字段行内是「标签 + 右对齐输入（空时显示必填/非必填占位）」。
 *
 * **不做课程颜色选择**（用户明确：「课程颜色不需要配置，按照你的取色逻辑保证不要与现有课程重复」）——
 * 颜色统一由 [CourseColors] 按整学期课程名均分 360° 色相，手动课名参与同一张表（见
 * `ScheduleRepository#allCachedCourseNames`），所以既不会跟教务课撞色，也不用手动挑。
 *
 * 页面底部带「已添加的课程」区：点行可载入编辑，右侧「删除」。
 */
/**
 * 添加页 / 编辑页的层级信号。
 *
 * 覆盖层是加在 ScheduleFragment 根布局上的普通 View，返回键由 Fragment 的
 * OnBackPressedCallback 接管，它看不到 Compose 里的 editing 状态；于是把
 * 「二级页开着吗、开着就先关它」这条规则注册到这里，让 Fragment 的返回回调先问一遍。
 */
object ManualCourseNav {
    var onBack: (() -> Boolean)? = null

    /**
     * 下一次打开添加页时要**直接进编辑页**的手动课 id（第五十七轮第 2 条）。
     *
     * 课表详情弹窗点「编辑」时先写这里，[ManualCoursePage] 首次组合读取并立刻清空。
     * 用它而不是给 `ManualCoursePage` 加参数：覆盖层是 `ComposeView.setContent { }` 里
     * 直接摆的，加参数就得把 id 一路提上去存成 Fragment 字段，反而更绕。
     */
    var pendingEditId: String? = null
}

/** 「添加课程」这一层的层级 key（[com.lanhang.miuix.ui.miuix.MiuiNavStack] 用）。 */
private object AddPageKey

/**
 * 「编辑课程」这一层的层级 key。
 *
 * 带 id 而不是直接带对象：栈容器会把旧 key 留到退场动画结束，用它去 store 里重新取，
 * 既能拿到最新的那份数据，也不会把「已被删除的课」渲染成一个空壳页。
 */
private data class EditPageKey(val id: String)

@Composable
fun ManualCoursePage(onClose: () -> Unit, onChanged: () -> Unit) {
    // 2026-11-01 第五十七轮（用户第 2 条「点击后进入编辑课程页面，注意动画的连贯性」）：
    // 从课程详情弹窗的「编辑」进来时，把编辑页当成**首页**渲染 ——
    // progress 初值直接是 1（animateFloatAsState 首次组合不发动画），
    // 于是只播外层「添加页整体滑入」这一层，不会再叠一次「编辑页从右侧滑入」。
    // 用户看到的是「弹窗一关、编辑页已经就位」，两层动画不打架。
    val initialEditing = remember {
        ManualCourseNav.pendingEditId?.let { id ->
            ManualCourseNav.pendingEditId = null
            ManualCourseStore.all().firstOrNull { it.id == id }
        }
    }
    var editing by remember { mutableStateOf<ManualCourse?>(initialEditing) }
    // formKey：新建 / 保存成功后整棵表单重建（State 归位）
    var formKey by remember { mutableIntStateOf(0) }
    // editKey：换一门课进编辑页时让编辑页重新初始化（否则 remember 会把上一门课的值留住）
    var editKey by remember { mutableIntStateOf(0) }
    // listTick：存储是普通 object，改完手动加一拍让「已添加的课程」重算
    var listTick by remember { mutableIntStateOf(0) }

    // 「编辑课程应视为添加课程的二级页面，返回时不应回到课表主页而应回到添加课程页」
    DisposableEffect(editing != null) {
        ManualCourseNav.onBack = {
            if (editing != null) {
                editing = null
                true
            } else {
                false
            }
        }
        onDispose { ManualCourseNav.onBack = null }
    }

    // 「将编辑课程设为添加课程的二级菜单，过渡动画照样设计」：
    // 第六十轮改成直接用全 App 唯一的转场容器 [MiuiNavStack]。
    // 手写版有三个毛病（用户：「返回时的过渡动画也不正常，参考其他页面的返回动画」）：
    //  · `if (target != null)` 一置空编辑页就立刻退出组合，返回时它**瞬间消失**，
    //    只剩背景层自己滑回来 —— 看起来像「页面直接闪掉」而不是「滑出去」；
    //  · 方向写死（父页永远左移），返回时缺少镜像，观感与其它二级页不一致；
    //  · 压暗层用 `if (progress > 0f)` 判断，返回末帧会硬切。
    // MiuiNavStack 内部用 `leaving` 把旧页留在组合里直到动画跑完，父页位移/压暗
    // 也按 forward 收支镜像，与「我的」「工作圈」等页面完全同源。
    MiuiNavStack(
        key = editing?.let { EditPageKey(it.id) } ?: AddPageKey,
        depthOf = { if (it is EditPageKey) 1 else 0 },
    ) { page ->
        if (page is EditPageKey) {
            val target = ManualCourseStore.all().firstOrNull { it.id == page.id }
            if (target != null) {
                ManualCourseEditor(
                    initial = target,
                    listTick = listTick,
                    // 编辑页的 ✕ 也是「返回上一级」：回到添加页，而不是把整个覆盖层关掉，
                    // 否则用户从课表进来点编辑、再点 ✕ 就直接掉回课表，和二级页的直觉不符。
                    onClose = { editing = null },
                    onSaved = {
                        CourseColors.invalidate()
                        onChanged()
                        editing = null
                        listTick++
                        formKey++
                        editKey++
                    },
                    onEdit = { c ->
                        editing = c
                        editKey++
                    },
                    onDelete = { id ->
                        ManualCourseStore.remove(id)
                        CourseColors.invalidate()
                        onChanged()
                        listTick++
                        if (editing?.id == id) editing = null
                    },
                )
            }
        } else {
            key(formKey) {
                ManualCourseEditor(
                    initial = null,
                    listTick = listTick,
                    onClose = onClose,
                    onSaved = {
                        // 色相表要重建：课程名集合变了，「整学期均分 360°」的分配也就变了
                        CourseColors.invalidate()
                        onChanged()
                        editing = null
                        listTick++
                        formKey++
                    },
                    onEdit = { c ->
                        editing = c
                        editKey++
                    },
                    onDelete = { id ->
                        ManualCourseStore.remove(id)
                        CourseColors.invalidate()
                        onChanged()
                        listTick++
                        if (editing?.id == id) editing = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ManualCourseEditor(
    initial: ManualCourse?,
    listTick: Int,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    onEdit: (ManualCourse) -> Unit,
    onDelete: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme

    var id by remember { mutableStateOf(initial?.id ?: UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var room by remember { mutableStateOf(initial?.room.orEmpty()) }
    var teacher by remember { mutableStateOf(initial?.teacher.orEmpty()) }
    var day by remember { mutableIntStateOf(initial?.dayOfWeek ?: 0) }
    var pStart by remember { mutableIntStateOf(initial?.periodStart ?: 1) }
    var pEnd by remember { mutableIntStateOf(initial?.periodEnd ?: 1) }
    var weeks by remember { mutableStateOf((initial?.weeks ?: emptyList()).toSet()) }
    // 周次快捷档：`"all"` 全部 / `"1"` 单周 / `"2"` 双周 / `""` 一档都没选。
    // 单独用一个状态而不是复用 oddEven 的原因：oddEven 的 `""` 既表示「全部」又表示「没选」，
    // 于是「再点一次全部取消」永远判不出来（2026-10-30 第一次实现就踩了这个）。
    var weekMode by remember {
        mutableStateOf(
            when {
                initial == null -> ""
                initial.oddEven == "1" -> "1"
                initial.oddEven == "2" -> "2"
                initial.weeks.size == MAX_WEEK -> "all"
                else -> ""
            }
        )
    }
    var oddEven by remember { mutableStateOf(initial?.oddEven.orEmpty()) }
    var periodOpen by remember { mutableStateOf(false) }
    // 自定义时间形态下「时间滚轮」是否展开（与 periodOpen 互斥，各自独立）
    var timeOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    // ── 自定义上课时间（2026-10-30 用户第 3 条）──
    // 开关 + 两个「当天第几分钟」。默认取第 1 节的作息（08:00-08:45），点开底部滚轮精调。
    var customTime by remember { mutableStateOf(initial?.hasCustomTime == true) }
    var startMinute by remember { mutableIntStateOf(initial?.startMinute?.takeIf { it >= 0 } ?: (8 * 60)) }
    var endMinute by remember { mutableIntStateOf(initial?.endMinute?.takeIf { it >= 0 } ?: (8 * 60 + 45)) }

    val saved = remember(listTick) { ManualCourseStore.all() }
    // 周次允许一个都不选（三个快捷档全不选、网格也不点）：空集合按「每周都上」保存，
    // 见 ManualCourse.inWeek 的注释。所以保存门槛只剩「课程名称非空」。
    val canSave = name.isNotBlank()

    fun commit() {
        if (name.isBlank()) {
            hint = "先填课程名称"
            return
        }
        ManualCourseStore.upsert(
            ManualCourse(
                id = id,
                name = name.trim(),
                teacher = teacher.trim(),
                room = room.trim(),
                dayOfWeek = day,
                periodStart = minOf(pStart, pEnd),
                periodEnd = maxOf(pStart, pEnd),
                weeks = weeks.sorted(),
                oddEven = oddEven,
                startMinute = if (customTime) startMinute else -1,
                endMinute = if (customTime) endMinute else -1,
            )
        )
        onSaved()
    }

    // 最外层是 Box：底下的 Column 是页面本体，上面还能盖一层「选时间」的底部面板
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部栏：✕ / 标题 / ✓ ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
            }
            Text(
                text = if (initial == null) "添加课程" else "编辑课程",
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = canSave) { commit() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✓",
                    style = MiuixTheme.textStyles.title4,
                    color = if (canSave) scheme.primary else scheme.onSurfaceVariantSummary,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 名称 / 地点 / 教师 ───────────────────────────────────────
            SectionCard {
                FormRow("课程名称", name, "必填") { name = it; hint = null }
                Divider()
                FormRow("地点", room, "非必填") { room = it }
                Divider()
                FormRow("教师", teacher, "非必填") { teacher = it }
            }

            // ── 上课星期（右侧挂「自定义时间」开关）───────────────────────
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上课星期",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    // 用户原话是「在上课周次旁边添加一个自定义时间的功能」，但参考视频里这个开关
                    // 就在「上课星期」这一行右侧 —— 按视频放这里（周次区已经有 20 个格子和三档快捷态，
                    // 再塞一个开关会很挤）。
                    // 第六十轮：按参考图把胶囊开关换成「圆形选择框 + 文字」，语义仍是开关（点一下取反）。
                    RadioTag(
                        label = "自定义时间",
                        selected = customTime,
                        onClick = {
                            customTime = !customTime
                            if (customTime) hint = null
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DAY_LABELS.forEachIndexed { index, label ->
                        Chip(
                            text = label,
                            selected = day == index,
                            onClick = { day = index },
                            // 第七日：七个胶囊均分整行宽度、统一 44dp 高，左右与卡片其它行对齐 16dp。
                            // 原来没给 weight，宽度只跟着单个汉字走、横向只留 6dp 间隙，
                            // 七个胶囊挤成一小团还互相贴住（用户：「上课星期挤在一起了」）。
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        )
                    }
                }
                AnimatedContent(
                    targetState = customTime,
                    transitionSpec = {
                        (fadeIn(tween(NavMotion.DURATION_MS / 2)) +
                            slideInVertically(tween(NavMotion.DURATION_MS / 2)) { it / 4 })
                            .togetherWith(fadeOut(tween(NavMotion.DURATION_MS / 3)))
                    },
                    label = "periodRow",
                ) { isCustom ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 节次与时间同一套交互 —— 都在页面内展开/收起，不再弹对话框
                                if (isCustom) timeOpen = !timeOpen else periodOpen = !periodOpen
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isCustom) "上课时间" else "上课节次",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (isCustom) {
                                "${fmtMinute(startMinute)} - ${fmtMinute(endMinute)}"
                            } else if (pStart == pEnd) {
                                "第 $pStart 节"
                            } else {
                                "第 $pStart - $pEnd 节"
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = scheme.primary,
                        )
                        Text(
                            text = if ((isCustom && timeOpen) || (!isCustom && periodOpen)) "  ⌃" else "  ›",
                            style = MiuixTheme.textStyles.body2,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                }
                AnimatedVisibility(visible = !customTime && periodOpen) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        MiniLabel("起始节")
                        PeriodGrid(pStart) { p ->
                            pStart = p
                            if (pEnd < p) pEnd = p
                        }
                        MiniLabel("结束节")
                        PeriodGrid(pEnd) { p ->
                            pEnd = p
                            if (pStart > p) pStart = p
                        }
                    }
                }
                // 时间形态点开后，同样在这张卡片里原地长出两行滚轮（开始 / 结束），
                // 与节次形态的「起始节 / 结束节」一一对应，交互和视觉都是同一套。
                AnimatedVisibility(visible = customTime && timeOpen) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        MiniLabel("开始时间")
                        TimeWheelRow(startMinute) { m ->
                            startMinute = m
                            // 保证结束晚于开始至少 5 分钟，避免存进一条「零长度」的课
                            if (endMinute <= m) endMinute = (m + 45).coerceAtMost(23 * 60 + 59)
                        }
                        MiniLabel("结束时间")
                        TimeWheelRow(endMinute) { m ->
                            endMinute = m
                            if (startMinute >= m) startMinute = (m - 45).coerceAtLeast(0)
                        }
                    }
                }
            }

            // ── 上课周次 ─────────────────────────────────────────────────
            // 2026-10-30 用户第 3 条：「全部 单周 双周可以都不选，用户可以选择特殊周次上课；
            // 选择上课周次时，参考长按滑动快速多选的操作逻辑」。
            //
            // 长按滑动的做法：整个网格是一块 pointerInput 区域（不是每个格子各管各的）——
            // 长按起手的那一周决定这一趟是「加选」还是「取消」，之后按「起手周 ~ 当前周」
            // 的整段范围刷新选中集合，手指划过的格子一次性都变，这才是批量多选的手感。
            var weekGridSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            var dragAnchor by remember { mutableIntStateOf(-1) }
            var dragBase by remember { mutableStateOf(emptySet<Int>()) }
            var dragSelect by remember { mutableStateOf(true) }

            fun weekAt(x: Float, y: Float): Int? {
                val w = weekGridSize.width.toFloat()
                val h = weekGridSize.height.toFloat()
                if (w <= 0f || h <= 0f) return null
                val rows = (MAX_WEEK + 5) / 6
                val col = (x / (w / 6f)).toInt().coerceIn(0, 5)
                val row = (y / (h / rows)).toInt().coerceIn(0, rows - 1)
                val week = row * 6 + col + 1
                return if (week in 1..MAX_WEEK) week else null
            }

            // 第六十三轮第 2 条：基准集合与方向都改成显式参数。
            // 原来读的是 dragBase / dragSelect 这两个 state，而手势协程不会随它们重组，
            // 读到的是起手那一刻的快照；显式传参就没有这个时序问题。
            fun applyWeekRange(from: Int, to: Int, base: Set<Int>, select: Boolean) {
                val lo = minOf(from, to)
                val hi = maxOf(from, to)
                weeks = if (select) base + (lo..hi) else base - (lo..hi).toSet()
                oddEven = ""
                weekMode = ""
                hint = null
            }

            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上课周次",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    listOf("全部" to "all", "单周" to "1", "双周" to "2").forEach { (label, code) ->
                        RadioTag(
                            label = label,
                            selected = weekMode == code,
                            onClick = {
                                if (weekMode == code) {
                                    // 再点一次就取消。三档可以一个都不选（用户原话），
                                    // 取消时把这一档带出来的周次也撤掉 —— 「都不选」就是全空，
                                    // 之后由用户自己在网格里挑特殊周次。
                                    weekMode = ""
                                    weeks = emptySet()
                                    oddEven = ""
                                } else {
                                    weekMode = code
                                    oddEven = if (code == "1" || code == "2") code else ""
                                    weeks = when (code) {
                                        "1" -> (1..MAX_WEEK).filter { it % 2 == 1 }.toSet()
                                        "2" -> (1..MAX_WEEK).filter { it % 2 == 0 }.toSet()
                                        else -> (1..MAX_WEEK).toSet()
                                    }
                                }
                                hint = null
                            },
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 第六十三轮第 2 条（用户：「将 1 2 3 4 5 6 等复选框与底部背景的边距加大一些」）：
                        // 原来 20 个周次格子直接贴到卡片底边，左右也几乎顶到边缘，
                        // 这里统一留出呼吸空间（底部 16dp 比顶部 6dp 大，视觉重心才不会往下坠）。
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 第六十三轮第 2 条：长按 + 拖拽批量选周次。
                            //
                            // 手势挂在**整块网格**上，不是挂在每个 Chip 上 —— 一格一个 pointerInput 的话，
                            // 手指一旦跨过格子边界手势就断了，连续一段根本划不出来。
                            //
                            // 起手那一周决定这一趟是「加选」还是「取消」：原本没选 → 这一趟统一加；
                            // 原本已选 → 这一趟统一减。之后按「起手周 ~ 当前周」整段刷新，
                            // 手指划过的格子一次性都变，这才是批量多选的手感。
                            .onGloballyPositioned { weekGridSize = it.size }
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val start = weekAt(offset.x, offset.y)
                                        if (start == null) {
                                            dragAnchor = -1
                                        } else {
                                            // 起手瞬间读一次 weeks，把「基准集合 + 方向」定下来，
                                            // 之后这一趟手势里不再变。
                                            val base = weeks
                                            val add = start !in base
                                            dragAnchor = start
                                            dragBase = base
                                            dragSelect = add
                                            applyWeekRange(start, start, base, add)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        if (dragAnchor > 0) {
                                            val now = weekAt(change.position.x, change.position.y)
                                            if (now != null) applyWeekRange(dragAnchor, now, dragBase, dragSelect)
                                        }
                                    },
                                    onDragEnd = { dragAnchor = -1 },
                                    onDragCancel = { dragAnchor = -1 },
                                )
                            },
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            (1..MAX_WEEK).chunked(6).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    row.forEach { w ->
                                        Chip(
                                            text = "$w",
                                            selected = w in weeks,
                                            onClick = {
                                                weeks = if (w in weeks) weeks - w else weeks + w
                                                // 手动点过就不再是「全部/单周/双周」的快捷态
                                                oddEven = ""
                                                weekMode = ""
                                                hint = null
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }

            hint?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // ── 已添加的课程 ─────────────────────────────────────────────
            if (saved.isNotEmpty()) {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已添加的课程",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${saved.size} 门",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                    saved.forEach { c ->
                        Divider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEdit(c) }
                                .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    c.name,
                                    style = MiuixTheme.textStyles.body2,
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = buildString {
                                        append(DAY_LABELS.getOrElse(c.dayOfWeek) { "周一" })
                                        append(" · 第 ").append(c.periodStart)
                                        if (c.periodEnd != c.periodStart) append("-").append(c.periodEnd)
                                        append(" 节")
                                        // 第六十轮第 1 条之后上课周次默认为空（全不选），
                                        // 空周次不要再拼出孤零零的「周」字。
                                        if (c.weeks.isNotEmpty()) {
                                            append(" · ").append(compressWeeks(c.weeks)).append(" 周")
                                        }
                                        if (c.room.isNotBlank()) append(" · ").append(c.room)
                                    },
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                "删除",
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onDelete(c.id) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    }
}

// ── 组件 ──────────────────────────────────────────────────────────────────

private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerHigh),
    ) {
        content()
    }
}

@Composable
private fun Divider() {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(scheme.dividerLine),
    )
}

/** 「标签 + 右对齐输入」一行；空值时右侧显示灰色占位（必填/非必填），对齐参考图 */
@Composable
private fun FormRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = scheme.onSurface,
            modifier = Modifier.width(88.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body2.copy(
                color = scheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MiuixTheme.textStyles.body2,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun MiniLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** 1~12 节的选择格（6 列两行） */
@Composable
private fun PeriodGrid(selected: Int, onPick: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        (1..PeriodClock.count).chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { p ->
                    Chip(
                        text = "$p",
                        selected = selected == p,
                        onClick = { onPick(p) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else scheme.onSurface,
        )
    }
}

/**
 * 「圆形选择框 + 文字」的单选项（2026-11-03 第六十轮，用户：「选项旁边应有圆形选择框」）。
 *
 * 参考图里「自定义时间」「全部 / 单周 / 双周」都是这种形态：左边一个圆点、右边跟标签，
 * 整组横向排在标题行右侧 —— 比原来的胶囊 [Chip] 更安静，也不会把标题行挤满。
 * 未选中是浅灰实心圆，选中是主色圆 + 白色内点（不是描边环，深色主题下更容易看清）。
 *
 * 语义有两种用法，本页都用到：
 *  · 开关（只有一个，如「自定义时间」）—— 点一下取反；
 *  · 单选组（如三档周次）—— 点已选中的那一项就是「一档都不选」，由调用侧处理。
 */
@Composable
private fun RadioTag(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariantSummary,
        )
    }
}
/** 当天第几分钟 -> `HH:mm` */
private fun fmtMinute(minute: Int): String {
    val m = minute.coerceIn(0, 1439)
    return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
}

/**
 * 行内小开关（「自定义时间」用）。
 *
 * 不用 Miuix 的 `Switch`：它自带 48dp 的行高与外部留白，塞进「上课星期」标题行会把高度顶爆。
 * 这里只画 42×24 的胶囊 + 20dp 白色滑块，颜色全走页面令牌。
 */
@Composable
private fun MiniSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val knob by animateDpAsState(
        targetValue = if (checked) 19.dp else 3.dp,
        label = "miniSwitchKnob",
    )
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .background(if (checked) scheme.primary else scheme.surfaceContainerHighest)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knob)
                .size(19.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
    }
}

/**
 * 页面内的「时:分」滚轮行 —— 与 [PeriodGrid] 同一个层级：长在卡片里、随行一起展开收起。
 *
 * 「自定义时间也应在页面内调整，而不应该再弹出对话框」：
 * 原来那个「全屏 35% 遮罩 + 底部 20dp 圆角面板 + 取消/确定」的 TimeSheetDialog 整体删除，
 * 只留下滚轮本体，改成和节次网格同构的两行（开始时间 / 结束时间）。
 */
@Composable
private fun TimeWheelRow(minute: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 小时：00..23 共 24 格，一格一小时，滚到 23 之后接着是 00（无限循环）
        WheelColumn(24, minute / 60, Modifier.weight(1f)) { h -> onChange(h * 60 + (minute % 60)) }
        WheelText(":")
        // 分钟：「五分钟一格，5 10 15 20 以此类推」
        // → 只出 00/05/10/…/55 共 12 格，step 5；滚到 55 之后接着是 00
        WheelColumn(12, (minute % 60) / 5, Modifier.weight(1f), step = 5) { m ->
            onChange((minute / 60) * 60 + m)
        }
    }
}

@Composable
private fun WheelText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.title4,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

/**
 * 单列滚轮。
 *
 * 需求演进：
 *  · 「五分钟一格，5 10 15 20 以此类推」→ 分钟列 12 格、`step = 5`；
 *  · 「无限滚动，23 点与 0 点衔接，55 与 00 衔接」→ 复制 1000 圈、初始钉在正中；
 *  · 「上下的待选数字改为一个」→ 只露三行；
 *  · 「重点标号会到最上面或最下面的数字」→ 用滚动偏移算中心项，见下；
 *  · 「重点不够突出，可以再调大字号」→ 选中 34sp Bold，未选 16sp Normal；
 *  · 「数字之间的切换不连贯流畅，设计一个过渡动画」→ 按距离连续插值，见下。
 *
 * **高亮为什么会跳到最上面/最下面**：
 * 上一版用 `i == state.firstVisibleItemIndex` 判高亮。`firstVisibleItemIndex` 是「第一个可见项」，
 * 手指一拖、内容上移超过一整行，它就跳到下一项 —— 但视觉上「中间那一行」是**按滚动偏移量**
 * 精确落在视口中心的那一项，两者不同步。于是拖动过程中高亮会突然跳到最上面那一行，
 * 快速滑动时甚至完全跑出可见区（看起来「没有高亮了」）。
 *
 * 正确算法：中心项索引 = `首项索引 + round(滚动偏移 / 行高)`。
 * 用 `derivedStateOf` 包起来，只在「高亮切换的那一帧」触发重组。
 */
@Composable
private fun WheelColumn(
    count: Int,
    initial: Int,
    modifier: Modifier = Modifier,
    step: Int = 1,
    onChanged: (Int) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val itemH = 46.dp
    val itemPx = with(LocalDensity.current) { itemH.toPx() }.coerceAtLeast(1f)
    val loop = 1000
    val total = count * loop
    val base = count * (loop / 2)
    val safeInitial = initial.coerceIn(0, (count - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = base + safeInitial)
    // 视觉中心那一项：跟着滚动偏移走，不是跟着「第一个可见项」走
    val centerHeld = remember(itemPx) {
        derivedStateOf {
            state.firstVisibleItemIndex +
                ((state.firstVisibleItemScrollOffset + itemPx / 2f) / itemPx).toInt()
        }
    }
    val centerIdx by centerHeld

    // 2026-11-01 第五十七轮（用户第 4 条「右上角的时间与下面的时间不同步」）：
    // 这里踩的是 Compose 的经典坑 —— `LaunchedEffect(state, count, step)` 的 key 不含 onChanged，
    // 协程里持有的是**首次组合时**那个 lambda，它捕获的 `minute` 永远停在初始值。
    // 于是滚分钟时 `(minute / 60) * 60 + m` 用的是旧的小时：23:55 往下滚一格，
    // 小时变 00 之后分钟仍按旧 hour 拼出「00:55」而不是「00:00」——凌晨交界处最刺眼。
    // rememberUpdatedState 保证回调永远是最新闭包。
    val latestOnChanged by rememberUpdatedState(onChanged)
    LaunchedEffect(state, count, step) {
        snapshotFlow { centerHeld.value }
            .collect { center ->
                val idx = (((center - base) % count) + count) % count
                latestOnChanged(idx * step)
            }
    }

    // 2026-11-01 第五十七轮（同一条）：外部值被程序改动时（开始时间越过结束时间后
    // 结束时间被自动推后 45 分钟、或从「已添加的课程」点进编辑页换了另一门课），
    // 滚轮必须跟着滚过去，否则出现「右上角已经写着 23:59、滚轮还停在 8:45」。
    // 只在「滚轮实际值 ≠ 外部值」时滚；用户正在拖动（isScrollInProgress）时不抢。
    LaunchedEffect(safeInitial, count, step) {
        if (state.isScrollInProgress) return@LaunchedEffect
        val curMod = (((state.firstVisibleItemIndex - base) % count) + count) % count
        if (curMod != safeInitial) state.animateScrollToItem(base + safeInitial)
    }

    Box(modifier = modifier.height(itemH * 3), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemH),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        ) {
            items(total) { i ->
                // 2026-11-01 第五十七轮（用户第 3 条「数字之间的切换不连贯流畅，设计一个过渡动画」）：
                // 原来用 `i == centerIdx` 硬切 16sp↔34sp，滚动时数字是"跳"变大小的。
                // 改成按「这一项中心离视口中心有多远」算一个 0..1 的连续权重，
                // 字号 / 字重 / 颜色全部按权重插值，滚动过程中数字连续放大缩小。
                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
                val frac = if (info == null) {
                    if (i == centerIdx) 1f else 0f
                } else {
                    val vp = state.layoutInfo
                    val vpCenter = (vp.viewportStartOffset + vp.viewportEndOffset) / 2f
                    val itemCenter = info.offset + info.size / 2f
                    (1f - kotlin.math.abs(itemCenter - vpCenter) / itemPx).coerceIn(0f, 1f)
                }
                // smoothstep 缓动：边缘更软、中间更实，不会出现"贴边还在大字"
                val k = frac * frac * (3f - 2f * frac)
                Box(
                    modifier = Modifier.fillMaxWidth().height(itemH),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%02d", (i % count) * step),
                        style = MiuixTheme.textStyles.body2.copy(
                            fontSize = (16f + 18f * k).sp,
                            fontWeight = if (k > 0.5f) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = lerp(scheme.onSurfaceVariantSummary, scheme.onSurface, k),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
