package com.lanhang.miuix.schedule

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
