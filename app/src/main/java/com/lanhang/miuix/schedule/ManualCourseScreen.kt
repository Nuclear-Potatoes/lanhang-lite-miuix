package com.lanhang.miuix.schedule

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
@Composable
fun ManualCoursePage(onClose: () -> Unit, onChanged: () -> Unit) {
    var editing by remember { mutableStateOf<ManualCourse?>(null) }
    // formKey：新建/切换编辑对象/保存成功后整棵表单重建（State 归位）
    var formKey by remember { mutableIntStateOf(0) }
    // listTick：存储是普通 object，改完手动加一拍让「已添加的课程」重算
    var listTick by remember { mutableIntStateOf(0) }

    key(formKey) {
        ManualCourseEditor(
            initial = editing,
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
                formKey++
            },
            onDelete = { id ->
                ManualCourseStore.remove(id)
                CourseColors.invalidate()
                onChanged()
                listTick++
                if (editing?.id == id) {
                    editing = null
                    formKey++
                }
            },
        )
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
    var weeks by remember { mutableStateOf((initial?.weeks ?: (1..MAX_WEEK).toList()).toSet()) }
    // 周次快捷档：`"all"` 全部 / `"1"` 单周 / `"2"` 双周 / `""` 一档都没选。
    // 单独用一个状态而不是复用 oddEven 的原因：oddEven 的 `""` 既表示「全部」又表示「没选」，
    // 于是「再点一次全部取消」永远判不出来（2026-10-30 第一次实现就踩了这个）。
    var weekMode by remember {
        mutableStateOf(
            when {
                initial == null -> "all"
                initial.oddEven == "1" -> "1"
                initial.oddEven == "2" -> "2"
                initial.weeks.size == MAX_WEEK -> "all"
                else -> ""
            }
        )
    }
    var oddEven by remember { mutableStateOf(initial?.oddEven.orEmpty()) }
    var periodOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    // ── 自定义上课时间（2026-10-30 用户第 3 条）──
    // 开关 + 两个「当天第几分钟」。默认取第 1 节的作息（08:00-08:45），点开底部滚轮精调。
    var customTime by remember { mutableStateOf(initial?.hasCustomTime == true) }
    var startMinute by remember { mutableIntStateOf(initial?.startMinute?.takeIf { it >= 0 } ?: (8 * 60)) }
    var endMinute by remember { mutableIntStateOf(initial?.endMinute?.takeIf { it >= 0 } ?: (8 * 60 + 45)) }
    var timeSheetOpen by remember { mutableStateOf(false) }

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
                    Text(
                        text = "自定义时间",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.width(8.dp))
                    MiniSwitch(checked = customTime) {
                        customTime = it
                        if (it) hint = null
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DAY_LABELS.forEachIndexed { index, label ->
                        Chip(
                            text = label,
                            selected = day == index,
                            onClick = { day = index },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ── 上课节次 / 上课时间 ──────────────────────────────────────
            // 开了「自定义时间」后这一行就变成时间入口（点开是底部滚轮），节次网格仍然显示，
            // 因为网格上占几行仍然由节次决定（时间只是这节课「其实几点上」）。
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (customTime) timeSheetOpen = true else periodOpen = !periodOpen }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上课节次",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (customTime) {
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
                        text = if (!customTime && periodOpen) "  ⌃" else "  ›",
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
                if (periodOpen || customTime) {
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

            fun applyWeekRange(from: Int, to: Int) {
                val lo = minOf(from, to)
                val hi = maxOf(from, to)
                weeks = if (dragSelect) dragBase + (lo..hi) else dragBase - (lo..hi).toSet()
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
                        Chip(
                            text = label,
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
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .onGloballyPositioned { weekGridSize = it.size }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val w = weekAt(offset.x, offset.y)
                                    if (w != null) {
                                        dragAnchor = w
                                        dragBase = weeks
                                        dragSelect = w !in weeks
                                        applyWeekRange(w, w)
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val anchor = dragAnchor
                                    val w = weekAt(change.position.x, change.position.y)
                                    if (anchor > 0 && w != null) applyWeekRange(anchor, w)
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
                                        append(" 节 · ").append(compressWeeks(c.weeks)).append(" 周")
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
        if (timeSheetOpen) {
            TimeSheetDialog(
                startMinute = startMinute,
                endMinute = endMinute,
                onDismiss = { timeSheetOpen = false },
                onConfirm = { s, e ->
                    startMinute = s
                    endMinute = e
                    customTime = true
                    timeSheetOpen = false
                },
            )
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
 * 「选择上课时间」底部面板：`时:分 — 时:分` 四列滚轮。
 *
 * 参考视频四的交互：底部弹起、四列滚轮联动、左右滑到底自然回弹、取消/确定两个动作。
 * 滚轮是 `LazyColumn` + `rememberSnapFlingBehavior`（松手自动吸附到整行），
 * 上下各留两行 `contentPadding`，于是「首项 = 中间那一行」——
 * 选中值直接取 `firstVisibleItemIndex`，不需要再算偏移。
 */
@Composable
private fun TimeSheetDialog(
    startMinute: Int,
    endMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var sh by remember { mutableIntStateOf((startMinute / 60).coerceIn(0, 23)) }
    var sm by remember { mutableIntStateOf((startMinute % 60).coerceIn(0, 59)) }
    var eh by remember { mutableIntStateOf((endMinute / 60).coerceIn(0, 23)) }
    var em by remember { mutableIntStateOf((endMinute % 60).coerceIn(0, 59)) }

    val noRipple = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(indication = null, interactionSource = noRipple) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(scheme.surfaceContainerHigh)
                // 吃掉点击，避免点面板空白处把面板关掉
                .clickable(indication = null, interactionSource = noRipple) {}
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "取消",
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    text = "上课时间",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "确定",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onConfirm(sh * 60 + sm, eh * 60 + em) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelColumn(24, sh, Modifier.weight(1f)) { sh = it }
                WheelText(":")
                WheelColumn(60, sm, Modifier.weight(1f)) { sm = it }
                WheelText("—")
                WheelColumn(24, eh, Modifier.weight(1f)) { eh = it }
                WheelText(":")
                WheelColumn(60, em, Modifier.weight(1f)) { em = it }
            }
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

/** 单列滚轮：可见 5 行，松手吸附；中间那一行加粗高亮 */
@Composable
private fun WheelColumn(
    count: Int,
    initial: Int,
    modifier: Modifier = Modifier,
    onChanged: (Int) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val itemH = 36.dp
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initial.coerceIn(0, (count - 1).coerceAtLeast(0))
    )
    Box(modifier = modifier.height(itemH * 5), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemH * 2),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        ) {
            items(count) { i ->
                val center = state.firstVisibleItemIndex
                Box(
                    modifier = Modifier.fillMaxWidth().height(itemH),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%02d", i),
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = if (i == center) FontWeight.Medium else FontWeight.Normal,
                        color = if (i == center) scheme.onSurface else scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .collect { onChanged(it.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    }
}
