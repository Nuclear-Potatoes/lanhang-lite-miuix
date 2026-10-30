package com.lanhang.miuix.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
    var oddEven by remember { mutableStateOf(initial?.oddEven.orEmpty()) }
    var periodOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    val saved = remember(listTick) { ManualCourseStore.all() }
    val canSave = name.isNotBlank() && weeks.isNotEmpty()

    fun commit() {
        if (name.isBlank()) {
            hint = "先填课程名称"
            return
        }
        if (weeks.isEmpty()) {
            hint = "至少选一个上课周次"
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
            )
        )
        onSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
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

            // ── 上课星期 ─────────────────────────────────────────────────
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

            // ── 上课节次 ─────────────────────────────────────────────────
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { periodOpen = !periodOpen }
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
                        text = if (pStart == pEnd) "第 $pStart 节" else "第 $pStart - $pEnd 节",
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.primary,
                    )
                    Text(
                        text = if (periodOpen) "  ⌃" else "  ›",
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
                if (periodOpen) {
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
                    listOf("全部" to "", "单周" to "1", "双周" to "2").forEach { (label, code) ->
                        Chip(
                            text = label,
                            selected = oddEven == code,
                            onClick = {
                                oddEven = code
                                weeks = when (code) {
                                    "1" -> (1..MAX_WEEK).filter { it % 2 == 1 }.toSet()
                                    "2" -> (1..MAX_WEEK).filter { it % 2 == 0 }.toSet()
                                    else -> (1..MAX_WEEK).toSet()
                                }
                            },
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
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
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
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
