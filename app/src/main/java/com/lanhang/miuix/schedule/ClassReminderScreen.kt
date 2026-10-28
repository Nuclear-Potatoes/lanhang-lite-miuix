package com.lanhang.miuix.schedule

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.moments.FilterDivider
import com.lanhang.miuix.moments.FilterSectionLabel
import com.lanhang.miuix.ui.miuix.pressableRow
import android.app.NotificationManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置 → 课表 → 上课提醒（第三十一轮新增，移植上游的「上课提醒」）。
 *
 * 页面只做三件事：总开关、提前量、下一次提醒的预览。真正的排定全在 [ClassReminder]，
 * 这里每次改动后都调一次 `rearm` —— 让用户改完立刻生效，而不是「下次打开 App 才生效」。
 *
 * 两条权限提示分开写：
 *  - **通知权限**没有 = 提醒根本不会出现（严重，主色提示 + 一键去系统设置）；
 *  - **精确闹钟**没有 = 只会晚几分钟（次要，只在 Android 12+ 且没授权时提示）。
 *    不主动弹系统授权页（`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 会把用户甩出应用），
 *    只给一个入口，让用户自己决定。
 */
@Composable
fun ClassReminderScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ClassReminderStore.enabled) }
    var advance by remember { mutableIntStateOf(ClassReminderStore.advanceMinutes) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 超级岛（HyperOS 焦点通知）：本机不支持就置灰，并说明会用普通通知
    val islandSupported = remember { ClassReminderIsland.isSupported() }
    var islandOn by remember { mutableStateOf(ClassReminderStore.islandEnabled) }

    val canNotify = remember(enabled) { ClassReminderNotify.canNotify(context) }
    // 开关关着也照常算一遍：这一行是「打开后会收到什么」的预览。
    // 之前返回 null，页面就显示「最近 7 天没有课」—— 明明有课却说过没有，是明确的错误信息。
    val next = remember(enabled, advance) {
        ClassReminderPlan.next(System.currentTimeMillis(), advance)
    }
    val dndOk = remember { ClassDnd.canControl(context) }
    val batteryOk = remember {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
            ?: true
    }
    val exactOk = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.class_reminder_enable),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.class_reminder_enable_summary),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            ClassReminderStore.enabled = on
                            notice = null
                            // 关 → 立即撤销全部闹钟；开 → 立即排好未来 7 天
                            ClassReminder.rearm(context)
                        },
                    )
                }
            }
        }

        item("island") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_style))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.class_reminder_island),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (islandSupported) {
                                stringResource(R.string.class_reminder_island_summary)
                            } else {
                                stringResource(R.string.class_reminder_island_unsupported)
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = islandOn && islandSupported,
                        enabled = islandSupported,
                        onCheckedChange = { on ->
                            islandOn = on
                            ClassReminderStore.islandEnabled = on
                            if (!on) ClassReminderIsland.cancelAll(context)
                        },
                    )
                }
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

        item("advanceLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_advance))
        }
        item("advance") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ClassReminderStore.ADVANCE_OPTIONS.forEachIndexed { index, minutes ->
                    if (index > 0) FilterDivider()
                    val label = if (minutes <= 0) {
                        stringResource(R.string.class_reminder_advance_zero)
                    } else {
                        stringResource(R.string.class_reminder_advance_value, minutes)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressableRow(
                                onClick = {
                                    advance = minutes
                                    ClassReminderStore.advanceMinutes = minutes
                                    ClassReminder.rearm(context)
                                },
                                corner = 16.dp,
                            )
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.body1,
                            // 选中的那一档用主题色标出（不用图标：一列文字里图标反而干扰读数）
                            color = if (minutes == advance) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (minutes == advance) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.primary)
                            )
                        }
                    }
                }
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

        // ---------------- 课中提醒（照 Nexio：课中提醒 + 提醒时机） ----------------
        item("inClassLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_in_class))
        }
        item("inClass") {
            var inClassOn by remember { mutableStateOf(ClassReminderStore.inClassEnabled) }
            var full by remember { mutableStateOf(ClassReminderStore.inClassFull) }
            var beforeEnd by remember { mutableIntStateOf(ClassReminderStore.inClassBeforeEndMinutes) }
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
                    Text(
                        text = stringResource(R.string.class_reminder_timing),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    OptionRow(
                        label = stringResource(R.string.class_reminder_timing_full),
                        selected = full,
                        onClick = {
                            full = true
                            ClassReminderStore.inClassFull = true
                            ClassReminder.rearm(context)
                        },
                    )
                    ClassReminderStore.BEFORE_END_OPTIONS.forEach { minutes ->
                        OptionRow(
                            label = stringResource(R.string.class_reminder_timing_before_end, minutes),
                            selected = !full && beforeEnd == minutes,
                            onClick = {
                                beforeEnd = minutes
                                full = false
                                ClassReminderStore.inClassFull = false
                                ClassReminderStore.inClassBeforeEndMinutes = minutes
                                ClassReminder.rearm(context)
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 次日课程提醒 ----------------
        item("nextDayLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_next_day))
        }
        item("nextDay") {
            var nextDayOn by remember { mutableStateOf(ClassReminderStore.nextDayEnabled) }
            var hour by remember { mutableIntStateOf(ClassReminderStore.nextDayHour) }
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
                    Text(
                        text = stringResource(R.string.class_reminder_next_day_time),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    ClassReminderStore.NEXT_DAY_HOURS.forEach { h ->
                        OptionRow(
                            label = stringResource(R.string.class_reminder_next_day_time_value, h),
                            selected = hour == h,
                            onClick = {
                                hour = h
                                ClassReminderStore.nextDayHour = h
                                ClassReminder.rearm(context)
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 上课勿扰 ----------------
        item("dndLabel") {
            FilterSectionLabel(stringResource(R.string.class_reminder_section_dnd))
        }
        item("dnd") {
            var autoDndOn by remember { mutableStateOf(ClassReminderStore.autoDnd) }
            var level by remember { mutableStateOf(ClassReminderStore.dndLevel) }
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
                    Text(
                        text = stringResource(R.string.class_reminder_dnd_level),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    listOf(
                        ClassReminderStore.DndLevel.SILENT to R.string.class_reminder_dnd_silent,
                        ClassReminderStore.DndLevel.PRIORITY to R.string.class_reminder_dnd_priority,
                        ClassReminderStore.DndLevel.TOTAL to R.string.class_reminder_dnd_total,
                    ).forEach { (lv, res) ->
                        OptionRow(
                            label = stringResource(res),
                            selected = level == lv,
                            onClick = {
                                level = lv
                                ClassReminderStore.dndLevel = lv
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 超级岛内容（只有支持超级岛且开着才出现） ----------------
        if (islandSupported && islandOn) {
            item("islandContentLabel") {
                FilterSectionLabel(stringResource(R.string.class_reminder_section_island))
            }
            item("islandContent") {
                var left by remember { mutableStateOf(ClassReminderStore.islandLeft) }
                var inClassRight by remember { mutableStateOf(ClassReminderStore.inClassRight) }
                var aod by remember { mutableStateOf(ClassReminderStore.islandAod) }
                var thumb by remember { mutableStateOf(ClassReminderStore.thumbScope) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.class_reminder_island_left),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    OptionRow(
                        label = stringResource(R.string.class_reminder_island_left_course),
                        selected = left == "course",
                        onClick = { left = "course"; ClassReminderStore.islandLeft = "course" },
                    )
                    OptionRow(
                        label = stringResource(R.string.class_reminder_island_left_room),
                        selected = left == "room",
                        onClick = { left = "room"; ClassReminderStore.islandLeft = "room" },
                    )
                    FilterDivider()
                    Text(
                        text = stringResource(R.string.class_reminder_island_in_class_right),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    OptionRow(
                        label = stringResource(R.string.class_reminder_island_right_remain),
                        selected = inClassRight == "remain",
                        onClick = { inClassRight = "remain"; ClassReminderStore.inClassRight = "remain" },
                    )
                    OptionRow(
                        label = stringResource(R.string.class_reminder_island_right_none),
                        selected = inClassRight == "none",
                        onClick = { inClassRight = "none"; ClassReminderStore.inClassRight = "none" },
                    )
                    FilterDivider()
                    SwitchLine(
                        title = stringResource(R.string.class_reminder_island_aod),
                        summary = stringResource(R.string.class_reminder_island_aod_summary),
                        checked = aod,
                        onChange = { on -> aod = on; ClassReminderStore.islandAod = on },
                    )
                    FilterDivider()
                    Text(
                        text = stringResource(R.string.class_reminder_thumb),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    listOf(
                        ClassReminderStore.ThumbScope.ORIGINAL to R.string.class_reminder_thumb_original,
                        ClassReminderStore.ThumbScope.ROOM to R.string.class_reminder_thumb_room,
                        ClassReminderStore.ThumbScope.COURSE to R.string.class_reminder_thumb_course,
                        ClassReminderStore.ThumbScope.BOTH to R.string.class_reminder_thumb_both,
                    ).forEach { (scope, res) ->
                        OptionRow(
                            label = stringResource(res),
                            selected = thumb == scope,
                            onClick = { thumb = scope; ClassReminderStore.thumbScope = scope },
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
                            onClick = {
                                ClassReminder.scheduleTest(context)
                                notice = context.getString(R.string.class_reminder_test_done)
                            },
                        )
                    }
                    val tip = notice
                    if (tip != null) {
                        Text(
                            text = tip,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.class_reminder_footer),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 通知设置页：Android 8+ 都是这个页面（各家的「通知管理」都从这里进） */
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
            if (summary != null) {
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

/** 单选项一行（选中用主题色标出，和「提前提醒时间」那组完全一致） */
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

/** 电池优化设置页（提醒能不能准时，最后一步靠它） */
private fun Context.openBatterySettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
