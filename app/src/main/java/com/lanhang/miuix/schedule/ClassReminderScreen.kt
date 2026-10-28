package com.lanhang.miuix.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.moments.FilterDivider
import com.lanhang.miuix.moments.FilterSectionLabel
import com.lanhang.miuix.ui.miuix.pressableRow
import com.lanhang.miuix.ui.miuix.showMiuixPicker
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置 → 课表 → 上课提醒。
 *
 * 第三十四轮（用户：「ui 交互设计太不美观…不要把所有选项都展示在第一页面，参考 nexio 课程表的处理方式」）：
 * 第一页只留**开关 + 当前值摘要**，具体档位全部进二级页（[Page]）。
 * 这就是 Nexio 的层次：一眼能看到「开没开、现在是多少」，想改才点进去。
 * 之前把 8 档提前量 + 4 档时机 + 5 档时间 + 3 档勿扰全摊在一页，滚三四屏才到底，确实难看。
 */
@Composable
fun ClassReminderScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current

    /**
     * 第三十五轮（用户：「上课提醒中，涉及到选择选项的情况，都参照成绩中学期选择中的对话框设计，
     * 将二级页面改为对话框形式」）：原来点值行是**整页替换**成二级页，现在统一弹 Miuix 单选对话框
     * （`showMiuixPicker`，和成绩页「学期」、课表「跳转周次」同一个组件）。
     */
    fun pick(
        title: String,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        showMiuixPicker(
            context = context,
            title = title,
            items = labels,
            selectedIndex = selected,
            cancelText = context.getString(R.string.common_cancel),
            onPick = { index -> onPick(index) },
        )
    }

    // 选项文案在组合作用域里算好：对话框只能在普通（非 @Composable）lambda 里弹出
    val advanceOptions = ClassReminderStore.ADVANCE_OPTIONS
    val advanceLabels = advanceOptions.map { minutes ->
        if (minutes <= 0) stringResource(R.string.class_reminder_advance_zero)
        else stringResource(R.string.class_reminder_advance_value, minutes)
    }
    val beforeEndOptions = ClassReminderStore.BEFORE_END_OPTIONS
    val timingLabels = listOf(stringResource(R.string.class_reminder_timing_full)) +
        beforeEndOptions.map { stringResource(R.string.class_reminder_timing_before_end, it) }
    val nextDayHours = ClassReminderStore.NEXT_DAY_HOURS
    val nextDayLabels = nextDayHours.map {
        stringResource(R.string.class_reminder_next_day_time_value, it)
    }
    val dndOptions = listOf(
        ClassReminderStore.DndLevel.SILENT to R.string.class_reminder_dnd_silent,
        ClassReminderStore.DndLevel.PRIORITY to R.string.class_reminder_dnd_priority,
        ClassReminderStore.DndLevel.TOTAL to R.string.class_reminder_dnd_total,
    )
    val dndLabels = dndOptions.map { stringResource(it.second) }
    val islandLeftOptions = listOf(
        "course" to R.string.class_reminder_island_left_course,
        "room" to R.string.class_reminder_island_left_room,
    )
    val islandLeftLabels = islandLeftOptions.map { stringResource(it.second) }
    val islandRightOptions = listOf(
        "remain" to R.string.class_reminder_island_right_remain,
        "none" to R.string.class_reminder_island_right_none,
    )
    val islandRightLabels = islandRightOptions.map { stringResource(it.second) }
    val thumbOptions = listOf(
        ClassReminderStore.ThumbScope.ORIGINAL to R.string.class_reminder_thumb_original,
        ClassReminderStore.ThumbScope.ROOM to R.string.class_reminder_thumb_room,
        ClassReminderStore.ThumbScope.COURSE to R.string.class_reminder_thumb_course,
        ClassReminderStore.ThumbScope.BOTH to R.string.class_reminder_thumb_both,
    )
    val thumbLabels = thumbOptions.map { stringResource(it.second) }

    // 所有设置都提到这一层：二级页改完回第一页，第一页的摘要立刻是新值
    var enabled by remember { mutableStateOf(ClassReminderStore.enabled) }
    var advance by remember { mutableIntStateOf(ClassReminderStore.advanceMinutes) }
    var inClassOn by remember { mutableStateOf(ClassReminderStore.inClassEnabled) }
    var inClassFull by remember { mutableStateOf(ClassReminderStore.inClassFull) }
    var beforeEnd by remember { mutableIntStateOf(ClassReminderStore.inClassBeforeEndMinutes) }
    var nextDayOn by remember { mutableStateOf(ClassReminderStore.nextDayEnabled) }
    var nextDayHour by remember { mutableIntStateOf(ClassReminderStore.nextDayHour) }
    var autoDndOn by remember { mutableStateOf(ClassReminderStore.autoDnd) }
    var dndLevel by remember { mutableStateOf(ClassReminderStore.dndLevel) }
    var islandOn by remember { mutableStateOf(ClassReminderStore.islandEnabled) }
    var islandLeft by remember { mutableStateOf(ClassReminderStore.islandLeft) }
    var inClassRight by remember { mutableStateOf(ClassReminderStore.inClassRight) }
    var aod by remember { mutableStateOf(ClassReminderStore.islandAod) }
    var thumb by remember { mutableStateOf(ClassReminderStore.thumbScope) }

    val islandSupported = remember { ClassReminderIsland.isSupported() }
    val canNotify = remember(enabled) { ClassReminderNotify.canNotify(context) }
    val dndOk = remember(Unit) { ClassDnd.canControl(context) }
    val batteryOk = remember(Unit) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    val exactOk = remember(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true
    }
    // 开关关着也照常算一遍：这一行是「打开后会收到什么」的预览
    val next = remember(enabled, advance) {
        ClassReminderPlan.next(System.currentTimeMillis(), advance)
    }
    val timeFormat = remember { DateTimeFormatter.ofPattern("M月d日 E HH:mm") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        item("switch") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_main))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchLine(
                    title = stringResource(R.string.class_reminder_title),
                    summary = ClassReminderStore.summary(context),
                    checked = enabled,
                    onChange = { on ->
                        enabled = on
                        ClassReminderStore.enabled = on
                        ClassReminder.rearm(context)
                    },
                )
            }
        }

        if (enabled && !canNotify) {
            item("notifyWarning") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.class_reminder_no_permission),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        TextButton(
                            text = stringResource(R.string.class_reminder_open_settings),
                            onClick = { context.openNotificationSettings() },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // ---------------- 提醒：只留一行摘要，档位进二级页 ----------------
        item("remindLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_advance))
        }
        item("remind") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ValueRow(
                    title = stringResource(R.string.class_reminder_advance_title),
                    value = advanceLabelText(advance),
                    onClick = {
                        pick(
                            title = context.getString(R.string.class_reminder_advance_title),
                            labels = advanceLabels,
                            selected = advanceOptions.indexOf(advance),
                        ) { index ->
                            val minutes = advanceOptions.getOrNull(index) ?: return@pick
                            advance = minutes
                            ClassReminderStore.advanceMinutes = minutes
                            ClassReminder.rearm(context)
                        }
                    },
                )
            }
        }

        item("nextLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_next))
        }
        item("next") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                    if (next == null) {
                        Text(
                            text = stringResource(R.string.class_reminder_next_none),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        val at = Instant.ofEpochMilli(next.startAt).atZone(ZoneId.systemDefault())
                        Text(
                            text = next.courses.joinToString(" / ") { it.name },
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.class_reminder_next_value,
                                timeFormat.format(at),
                                next.period,
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        // ---------------- 课中提醒 ----------------
        item("inClassLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_in_class))
        }
        item("inClass") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchLine(
                    title = stringResource(R.string.class_reminder_in_class),
                    summary = stringResource(R.string.class_reminder_in_class_summary),
                    checked = inClassOn,
                    onChange = { on ->
                        inClassOn = on
                        ClassReminderStore.inClassEnabled = on
                        ClassReminder.rearm(context)
                    },
                )
                if (inClassOn) {
                    FilterDivider()
                    ValueRow(
                        title = stringResource(R.string.class_reminder_timing),
                        value = timingLabelText(inClassFull, beforeEnd),
                        onClick = {
                        pick(
                            title = context.getString(R.string.class_reminder_timing),
                            labels = timingLabels,
                            selected = if (inClassFull) 0
                            else 1 + beforeEndOptions.indexOf(beforeEnd),
                        ) { index ->
                            if (index == 0) {
                                inClassFull = true
                                ClassReminderStore.inClassFull = true
                            } else {
                                val minutes = beforeEndOptions.getOrNull(index - 1) ?: return@pick
                                inClassFull = false
                                beforeEnd = minutes
                                ClassReminderStore.inClassFull = false
                                ClassReminderStore.inClassBeforeEndMinutes = minutes
                            }
                            ClassReminder.rearm(context)
                        }
                    },
                    )
                }
            }
        }

        // ---------------- 次日课程提醒 ----------------
        item("nextDayLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_next_day))
        }
        item("nextDay") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchLine(
                    title = stringResource(R.string.class_reminder_next_day),
                    summary = stringResource(R.string.class_reminder_next_day_summary),
                    checked = nextDayOn,
                    onChange = { on ->
                        nextDayOn = on
                        ClassReminderStore.nextDayEnabled = on
                        ClassReminder.rearm(context)
                        if (on) ClassReminder.showNextDayNow(context)
                    },
                )
                if (nextDayOn) {
                    FilterDivider()
                    ValueRow(
                        title = stringResource(R.string.class_reminder_next_day_time),
                        value = stringResource(
                            R.string.class_reminder_next_day_time_value,
                            nextDayHour,
                        ),
                        onClick = {
                            pick(
                                title = context.getString(R.string.class_reminder_next_day_time),
                                labels = nextDayLabels,
                                selected = nextDayHours.indexOf(nextDayHour),
                            ) { index ->
                                val hour = nextDayHours.getOrNull(index) ?: return@pick
                                nextDayHour = hour
                                ClassReminderStore.nextDayHour = hour
                                ClassReminder.rearm(context)
                            }
                        },
                    )
                }
            }
        }

        // ---------------- 上课勿扰 ----------------
        item("dndLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_dnd))
        }
        item("dnd") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchLine(
                    title = stringResource(R.string.class_reminder_dnd),
                    summary = if (dndOk) {
                        stringResource(R.string.class_reminder_dnd_summary)
                    } else {
                        stringResource(R.string.class_reminder_dnd_need_permission)
                    },
                    checked = autoDndOn && dndOk,
                    enabled = dndOk,
                    onChange = { on ->
                        autoDndOn = on
                        ClassReminderStore.autoDnd = on
                        ClassReminder.rearm(context)
                    },
                )
                if (!dndOk) {
                    FilterDivider()
                    TextButton(
                        text = stringResource(R.string.class_reminder_dnd_open),
                        onClick = { ClassDnd.openSettings(context) },
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                } else if (autoDndOn) {
                    FilterDivider()
                    ValueRow(
                        title = stringResource(R.string.class_reminder_dnd_level),
                        value = dndLabelText(dndLevel),
                        onClick = {
                            pick(
                                title = context.getString(R.string.class_reminder_dnd_level),
                                labels = dndLabels,
                                selected = dndOptions.indexOfFirst { it.first == dndLevel },
                            ) { index ->
                                val level = dndOptions.getOrNull(index)?.first ?: return@pick
                                dndLevel = level
                                ClassReminderStore.dndLevel = level
                            }
                        },
                    )
                }
            }
        }

        // ---------------- 超级岛 ----------------
        if (islandSupported) {
            item("islandLabel") {
                FilterSectionLabel(stringResource(R.string.class_reminder_section_island))
            }
            item("island") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchLine(
                        title = stringResource(R.string.class_reminder_island),
                        summary = stringResource(R.string.class_reminder_island_summary),
                        checked = islandOn,
                        onChange = { on ->
                            islandOn = on
                            ClassReminderStore.islandEnabled = on
                            if (!on) ClassReminderIsland.cancelAll(context)
                        },
                    )
                    if (islandOn) {
                        FilterDivider()
                        ValueRow(
                            title = stringResource(R.string.class_reminder_island_left),
                            value = islandLeftLabelText(islandLeft),
                            onClick = {
                                pick(
                                    title = context.getString(R.string.class_reminder_island_left),
                                    labels = islandLeftLabels,
                                    selected = islandLeftOptions.indexOfFirst { it.first == islandLeft },
                                ) { index ->
                                    val key = islandLeftOptions.getOrNull(index)?.first ?: return@pick
                                    islandLeft = key
                                    ClassReminderStore.islandLeft = key
                                }
                            },
                        )
                        FilterDivider()
                        ValueRow(
                            title = stringResource(R.string.class_reminder_island_in_class_right),
                            value = inClassRightLabelText(inClassRight),
                            onClick = {
                                pick(
                                    title = context.getString(R.string.class_reminder_island_in_class_right),
                                    labels = islandRightLabels,
                                    selected = islandRightOptions.indexOfFirst { it.first == inClassRight },
                                ) { index ->
                                    val key = islandRightOptions.getOrNull(index)?.first ?: return@pick
                                    inClassRight = key
                                    ClassReminderStore.inClassRight = key
                                }
                            },
                        )
                        FilterDivider()
                        ValueRow(
                            title = stringResource(R.string.class_reminder_thumb),
                            value = thumbLabelText(thumb),
                            onClick = {
                                pick(
                                    title = context.getString(R.string.class_reminder_thumb),
                                    labels = thumbLabels,
                                    selected = thumbOptions.indexOfFirst { it.first == thumb },
                                ) { index ->
                                    val scope = thumbOptions.getOrNull(index)?.first ?: return@pick
                                    thumb = scope
                                    ClassReminderStore.thumbScope = scope
                                }
                            },
                        )
                        FilterDivider()
                        SwitchLine(
                            title = stringResource(R.string.class_reminder_island_aod),
                            summary = stringResource(R.string.class_reminder_island_aod_summary),
                            checked = aod,
                            onChange = { on ->
                                aod = on
                                ClassReminderStore.islandAod = on
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 权限 ----------------
        item("permLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_permission))
        }
        item("perm") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                PermRow(
                    title = stringResource(R.string.class_reminder_perm_notify),
                    granted = canNotify,
                    onClick = { context.openNotificationSettings() },
                )
                FilterDivider()
                PermRow(
                    title = stringResource(R.string.class_reminder_perm_exact),
                    granted = exactOk,
                    onClick = { context.openExactAlarmSettings() },
                )
                FilterDivider()
                PermRow(
                    title = stringResource(R.string.class_reminder_perm_dnd),
                    granted = dndOk,
                    onClick = { ClassDnd.openSettings(context) },
                )
                FilterDivider()
                PermRow(
                    title = stringResource(R.string.class_reminder_perm_battery),
                    granted = batteryOk,
                    onClick = { context.openBatterySettings() },
                )
            }
        }

        item("test") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = if (exactOk) {
                            stringResource(R.string.class_reminder_test_hint)
                        } else {
                            stringResource(R.string.class_reminder_no_exact)
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!exactOk) {
                            TextButton(
                                text = stringResource(R.string.class_reminder_open_settings),
                                onClick = { context.openExactAlarmSettings() },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.class_reminder_test),
                            onClick = { ClassReminder.scheduleTest(context) },
                        )
                    }
                }
            }
        }
    }
}

/** 值行：左边名字，右边当前值 + 箭头，点开是单选对话框 */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "  \u203A",
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 一行「标题 + 说明 + 开关」（Card 内部用，自己不带卡片背景） */
@Composable
private fun SwitchLine(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (summary != null && summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** 单选项一行（选中用主题色标出） */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 权限一行：左边名字，右边「已授权 / 未授权」，点一下去对应系统页 */
@Composable
private fun PermRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (granted) R.string.class_reminder_perm_granted else R.string.class_reminder_perm_missing
            ),
            style = MiuixTheme.textStyles.footnote1,
            color = if (granted) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun advanceLabelText(minutes: Int): String =
    if (minutes <= 0) stringResource(R.string.class_reminder_advance_zero)
    else stringResource(R.string.class_reminder_advance_value, minutes)

@Composable
private fun timingLabelText(full: Boolean, beforeEndMinutes: Int): String =
    if (full) stringResource(R.string.class_reminder_timing_full)
    else stringResource(R.string.class_reminder_timing_before_end, beforeEndMinutes)

@Composable
private fun dndLabelText(level: ClassReminderStore.DndLevel): String = stringResource(
    when (level) {
        ClassReminderStore.DndLevel.SILENT -> R.string.class_reminder_dnd_silent
        ClassReminderStore.DndLevel.PRIORITY -> R.string.class_reminder_dnd_priority
        ClassReminderStore.DndLevel.TOTAL -> R.string.class_reminder_dnd_total
    }
)

@Composable
private fun islandLeftLabelText(key: String): String = stringResource(
    if (key == "room") R.string.class_reminder_island_left_room
    else R.string.class_reminder_island_left_course
)

@Composable
private fun inClassRightLabelText(key: String): String = stringResource(
    if (key == "none") R.string.class_reminder_island_right_none
    else R.string.class_reminder_island_right_remain
)

@Composable
private fun thumbLabelText(scope: ClassReminderStore.ThumbScope): String = stringResource(
    when (scope) {
        ClassReminderStore.ThumbScope.ORIGINAL -> R.string.class_reminder_thumb_original
        ClassReminderStore.ThumbScope.ROOM -> R.string.class_reminder_thumb_room
        ClassReminderStore.ThumbScope.COURSE -> R.string.class_reminder_thumb_course
        ClassReminderStore.ThumbScope.BOTH -> R.string.class_reminder_thumb_both
    }
)

/** 通知权限设置页 */
private fun Context.openNotificationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** 「闹钟和提醒」授权页（只有 Android 12+ 才有这个入口） */
private fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 电池优化设置页（提醒能不能准时，最后一步靠它） */
private fun Context.openBatterySettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
