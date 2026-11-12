package com.lanhang.miuix.im

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.pressableRow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 通知（第六十三轮第 4 条，参考原版蓝航的 `SetNotificationActivity`）。
 *
 * 三组开关与原版一一对应，但判定全在本机做（本项目没有融云推送通道，见 [NotifySettingsStore]）：
 *  1. 接收新消息通知（总开关）；
 *  2. 通知显示消息详情（关掉后只弹「你收到一条新消息」）；
 *  3. 免打扰时段（起止时间，跨零点也算对）。
 *
 * 时间选择没有弹系统 `TimePickerDialog`：整页都是 Miuix 的卡与发丝线，
 * 弹一个原生黑白时钟会突兀；改成点行就地展开滚轮，选完再点一次收起。
 */
@Composable
fun NotifySettingsScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    // SP 里是真源，这里只是把它读进 Compose 状态（写的时候同时写回 SP）
    var enabled by remember { mutableStateOf(NotifySettingsStore.enabled) }
    var showDetail by remember { mutableStateOf(NotifySettingsStore.showDetail) }
    var quietOn by remember { mutableStateOf(NotifySettingsStore.quietEnabled) }
    var start by remember { mutableStateOf(NotifySettingsStore.quietStart) }
    var end by remember { mutableStateOf(NotifySettingsStore.quietEnd) }
    // 0 = 不展开，1 = 展开开始时间滚轮，2 = 展开结束时间滚轮
    var wheelTarget by remember { mutableStateOf(0) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        item("notifyMaster") {
            SmallTitle(stringResource(R.string.notify_settings_section_message))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                NotifySwitchRow(
                    title = stringResource(R.string.notify_settings_master),
                    summary = stringResource(R.string.notify_settings_master_summary),
                    checked = enabled,
                    onCheckedChange = { v ->
                        enabled = v
                        NotifySettingsStore.enabled = v
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                NotifySwitchRow(
                    title = stringResource(R.string.notify_settings_detail),
                    summary = stringResource(R.string.notify_settings_detail_summary),
                    checked = showDetail && enabled,
                    enabled = enabled,
                    onCheckedChange = { v ->
                        showDetail = v
                        NotifySettingsStore.showDetail = v
                    },
                )
            }
        }

        item("notifyQuiet") {
            SmallTitle(stringResource(R.string.notify_settings_section_quiet))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                NotifySwitchRow(
                    title = stringResource(R.string.notify_settings_quiet),
                    summary = stringResource(R.string.notify_settings_quiet_summary),
                    checked = quietOn && enabled,
                    enabled = enabled,
                    onCheckedChange = { v ->
                        quietOn = v
                        NotifySettingsStore.quietEnabled = v
                        if (!v) wheelTarget = 0
                    },
                )
                if (quietOn && enabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotifyValueRow(
                        title = stringResource(R.string.notify_settings_quiet_start),
                        value = NotifySettingsStore.format(start),
                        onClick = { wheelTarget = if (wheelTarget == 1) 0 else 1 },
                    )
                    if (wheelTarget == 1) {
                        NotifyTimeWheel(minute = start) { v ->
                            start = v
                            NotifySettingsStore.quietStart = v
                            // 起止撞在同一刻会被判成「未设置」，这里直接把结束推后一小时，
                            // 免得用户选了 08:00 才发现免打扰整个失效。
                            if (v == end) {
                                val pushed = (v + 60) % NOTIFY_MINUTES_PER_DAY
                                end = pushed
                                NotifySettingsStore.quietEnd = pushed
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotifyValueRow(
                        title = stringResource(R.string.notify_settings_quiet_end),
                        value = NotifySettingsStore.format(end),
                        onClick = { wheelTarget = if (wheelTarget == 2) 0 else 2 },
                    )
                    if (wheelTarget == 2) {
                        NotifyTimeWheel(minute = end) { v ->
                            end = v
                            NotifySettingsStore.quietEnd = v
                            if (v == start) {
                                val pushed = (v + 60) % NOTIFY_MINUTES_PER_DAY
                                start = pushed
                                NotifySettingsStore.quietStart = pushed
                            }
                        }
                    }
                }
            }
        }

        item("notifyFooter") {
            Text(
                text = stringResource(R.string.notify_settings_footer),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
    }
}

private const val NOTIFY_MINUTES_PER_DAY = 1440

@Composable
private fun NotifySwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dim = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = dim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun NotifyValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 15.dp),
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
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 开始 / 结束时间共用的两列滚轮：小时 00–23，分钟 5 分一格（00/05/…/55） */
@Composable
private fun NotifyTimeWheel(minute: Int, onChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotifyWheel(24, minute / 60, Modifier.weight(1f)) { h ->
            onChanged(h * 60 + (minute % 60))
        }
        Text(
            text = ":",
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        NotifyWheel(12, (minute % 60) / 5, Modifier.weight(1f), step = 5) { m ->
            onChanged((minute / 60) * 60 + m)
        }
    }
}

/**
 * 单列滚轮（精简版，形状与课表「自定义时间」的 `WheelColumn` 一致）。
 *
 * 有意不共用那边的实现：那边是 private 且与课表编辑页的联动逻辑缠在一起，
 * 为了多一处入口去动已经稳定的页面不划算。这里只保留真正需要的两点：
 *  · **无限循环**（23 点下面接着 0 点），否则滚到边界会顶住；
 *  · **按滚动偏移连续插值**放大字号，不能靠 `i == firstVisibleItemIndex` 硬切。
 */
@Composable
private fun NotifyWheel(
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
    val centerHeld = remember(itemPx) {
        derivedStateOf {
            state.firstVisibleItemIndex +
                ((state.firstVisibleItemScrollOffset + itemPx / 2f) / itemPx).toInt()
        }
    }
    val centerIdx by centerHeld

    // 协程里持有的是首次组合时的 lambda，必须用 rememberUpdatedState 取最新闭包，
    // 否则滚分钟时用的是旧的小时（23:55 滚到 00:55 而不是 00:00）。
    val latestOnChanged by rememberUpdatedState(onChanged)
    LaunchedEffect(state, count, step) {
        snapshotFlow { centerHeld.value }.collect { center ->
            val idx = (((center - base) % count) + count) % count
            latestOnChanged(idx * step)
        }
    }

    // 外部值被程序改动时（起止撞车后自动推后一小时）滚轮要跟着过去
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
                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
                val frac = if (info == null) {
                    if (i == centerIdx) 1f else 0f
                } else {
                    val vp = state.layoutInfo
                    val vpCenter = (vp.viewportStartOffset + vp.viewportEndOffset) / 2f
                    val itemCenter = info.offset + info.size / 2f
                    (1f - kotlin.math.abs(itemCenter - vpCenter) / itemPx).coerceIn(0f, 1f)
                }
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
