package com.lanhang.miuix.library

import com.lanhang.miuix.ui.miuix.pressableRow
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.lanhang.miuix.R
import com.lanhang.miuix.core.isCancellation
import com.lanhang.miuix.core.userDetail
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.miuix.showMiuixInfo
import com.lanhang.miuix.ui.miuix.showMiuixPicker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 图书馆座位预约 · 选座页（2026-10-27 第十六轮）。
 *
 * 逻辑层整包移植自原项目（`library/LibrarySeatApi.kt` + `SeatModels.kt` + `SeatMapView.kt`，
 * 服务端是洄图的 SDUI 协议，见 `docs/library-seat-reservation-research.md`）；
 * 界面全部按 Miuix 规范重写：三段式（房间 → 时段 → 座位图）+ 底部预约动作。
 *
 * 时段规则不在客户端写死：开始整点、可选时长都由服务端下发的 [SeatRules] 算
 * （`startHours` 包含当前小时，`durationHours` 受闭馆时间与 max_duration 双重限制）。
 */
@Composable
fun LibrarySeatScreen(
    onOpenBookings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rooms by remember { mutableStateOf<List<SeatRoom>>(emptyList()) }
    var roomIndex by remember { mutableIntStateOf(0) }
    var form by remember { mutableStateOf<SeatForm?>(null) }
    var startHour by remember { mutableIntStateOf(-1) }
    var duration by remember { mutableIntStateOf(0) }
    // 2026-10-27 第十八轮：一层楼可以有多个区域（图书馆一楼 = 一楼自习区 + 自习室 102），
    // 服务端一次把整层都下发，这里按区域分开存，默认停在推荐的那一个。
    var areas by remember { mutableStateOf<List<SeatMapData>>(emptyList()) }
    var areaIndex by remember { mutableIntStateOf(0) }
    var planBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selected by remember { mutableStateOf<SeatPoi?>(null) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    var booking by remember { mutableStateOf(false) }
    var reloadMap by remember { mutableIntStateOf(0) }

    val map = areas.getOrNull(areaIndex)

    val nowHour = remember { LocalTime.now().hour }

    // ① 先登录座位系统（复用蓝航统一身份），再拉空间列表
    LaunchedEffect(Unit) {
        loading = true
        try {
            val ok = withContext(Dispatchers.IO) {
                LibrarySeatApi.ensureLogin(Session.deviceId(context.applicationContext))
            }
            if (!ok) {
                hint = context.getString(R.string.error_library_login)
                return@LaunchedEffect
            }
            val list = withContext(Dispatchers.IO) { LibrarySeatApi.rooms() }
            rooms = list
            if (list.isEmpty()) hint = context.getString(R.string.library_seat_no_room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hint = context.getString(R.string.library_seat_load_failed, e.userDetail(context))
        } finally {
            loading = false
        }
    }

    // ② 换房间 → 拉选座规则，定默认时段
    LaunchedEffect(rooms, roomIndex) {
        val room = rooms.getOrNull(roomIndex) ?: return@LaunchedEffect
        loading = true
        selected = null
        try {
            val f = withContext(Dispatchers.IO) { LibrarySeatApi.form(room) }
            form = f
            val starts = f.rules.startHours(LocalTime.now().hour, f.defaultBeginHour)
            if (starts == null) {
                startHour = -1
                duration = 0
                areas = emptyList()
                areaIndex = 0
                hint = context.getString(R.string.library_seat_no_slot)
            } else {
                if (startHour !in starts) startHour = starts.first
                val durations = f.rules.durationHours(startHour)
                duration = if (durations == null) 0
                else f.defaultDurationHours.coerceIn(durations.first, durations.last)
                hint = ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hint = context.getString(R.string.library_seat_load_failed, e.userDetail(context))
        } finally {
            loading = false
        }
    }

    // ③ 时段定下来 → 查座位图
    LaunchedEffect(rooms, roomIndex, startHour, duration, reloadMap) {
        val room = rooms.getOrNull(roomIndex) ?: return@LaunchedEffect
        if (form == null || startHour < 0 || duration <= 0) return@LaunchedEffect
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                LibrarySeatApi.search(room, beginEpoch(startHour), duration * 3600)
            }
            val keep = areas.getOrNull(areaIndex)?.roomName
            areas = result.areas
            areaIndex = result.areas
                .indexOfFirst { it.roomName == keep }
                .takeIf { it >= 0 && it < result.areas.size } ?: 0
            selected = null
            hint = when {
                result.slotEmpty -> context.getString(R.string.library_seat_empty_slot)
                result.areas.isEmpty() || result.totalFree == 0 ->
                    context.getString(R.string.library_seat_no_seat)
                else -> ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            areas = emptyList()
            areaIndex = 0
            hint = context.getString(R.string.library_seat_load_failed, e.userDetail(context))
        } finally {
            loading = false
        }
    }

    // ④ 平面图背景（拿不到就退化成纯色底 + 色块，座位图仍然可用）
    LaunchedEffect(map?.planUrl) {
        val url = map?.planUrl ?: return@LaunchedEffect
        planBitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = coil.Coil.imageLoader(context).execute(request)
                val drawable = (result as? coil.request.SuccessResult)?.drawable
                drawable?.toBitmap()
            }.getOrNull()
        }
    }

    val rules = form?.rules
    val startOptions = remember(form) {
        rules?.startHours(LocalTime.now().hour, form?.defaultBeginHour ?: 0)
    }
    val durationOptions = remember(form, startHour) { rules?.durationHours(startHour) }

    fun pickStart() {
        val options = startOptions ?: return
        val hours = options.toList()
        showMiuixPicker(
            context = context,
            title = context.getString(R.string.library_seat_start),
            items = hours.map { hourLabel(it) },
            selectedIndex = hours.indexOf(startHour),
            cancelText = context.getString(R.string.common_cancel),
            onPick = { index ->
                if (index in hours.indices) {
                    val hour = hours[index]
                    startHour = hour
                    val next = rules?.durationHours(hour)
                    if (next != null) duration = duration.coerceIn(next.first, next.last)
                    selected = null
                }
            },
        )
    }

    fun pickDuration() {
        val options = durationOptions ?: return
        val values = (options.first..options.last).toList()
        showMiuixPicker(
            context = context,
            title = context.getString(R.string.library_seat_duration),
            items = values.map { context.getString(R.string.library_seat_duration_hours, it) },
            selectedIndex = values.indexOf(duration),
            cancelText = context.getString(R.string.common_cancel),
            onPick = { index -> values.getOrNull(index)?.let { duration = it; selected = null } },
        )
    }

    fun pickRoom() {
        if (rooms.size < 2) return
        showMiuixPicker(
            context = context,
            title = context.getString(R.string.library_seat_room),
            items = rooms.map { it.name },
            selectedIndex = roomIndex,
            cancelText = context.getString(R.string.common_cancel),
            onPick = { index -> if (index in rooms.indices) roomIndex = index },
        )
    }

    fun book() {
        val seat = selected ?: return
        val room = rooms.getOrNull(roomIndex) ?: return
        if (booking) return
        showMiuixConfirm(
            context = context,
            title = context.getString(R.string.library_seat_book_confirm_title),
            message = context.getString(
                R.string.library_seat_book_confirm_msg,
                map?.roomName ?: room.name,
                seat.title,
                slotLabel(startHour, duration),
            ),
            confirmText = context.getString(R.string.library_seat_book),
            dismissText = context.getString(R.string.common_cancel),
            onConfirm = {
                booking = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            LibrarySeatApi.book(seat.id, beginEpoch(startHour), duration * 3600)
                        }
                        selected = null
                        reloadMap++
                        showMiuixInfo(
                            context,
                            context.getString(R.string.library_seat_book_ok),
                            result.message ?: slotLabel(startHour, duration),
                            context.getString(R.string.common_close),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        showMiuixInfo(
                            context,
                            context.getString(R.string.library_seat_book_failed, e.userDetail(context)),
                            null,
                            context.getString(R.string.common_close),
                        )
                    } finally {
                        booking = false
                    }
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        // ---------------- 房间 / 时段 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_seat_section),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.library_seat_my_bookings),
                onClick = onOpenBookings,
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            // 第十九轮：横向不留 insideMargin，让按压高亮能贴到卡片左右边缘（MIUI 的整行压暗），
            // 行内自己补 16.dp 横向 padding，文字位置和以前一模一样。
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                InfoRow(
                    title = stringResource(R.string.library_seat_room),
                    value = rooms.getOrNull(roomIndex)?.name ?: stringResource(R.string.library_seat_none),
                    onClick = if (rooms.size > 1) ({ pickRoom() }) else null,
                )
                InfoRow(
                    title = stringResource(R.string.library_seat_start),
                    value = if (startHour < 0) stringResource(R.string.library_seat_none) else hourLabel(startHour),
                    onClick = if (startOptions != null) ({ pickStart() }) else null,
                )
                InfoRow(
                    title = stringResource(R.string.library_seat_duration),
                    value = if (duration <= 0) stringResource(R.string.library_seat_none)
                    else stringResource(R.string.library_seat_duration_hours, duration),
                    onClick = if (durationOptions != null) ({ pickDuration() }) else null,
                )
            }
        }

        // ---------------- 区域（一层楼多个区域时才出现） ----------------
        // 第十八轮：图书馆一楼其实有两个区域（一楼自习区（位置上有插座）、自习室（102）），
        // 服务端只推荐其中一个，另一个以前根本约不到。区域名很长，所以不用一排胶囊
        // （实测会被座位图那块 AndroidView 盖住），改成两张整行卡片，谁选中谁反色。
        if (areas.size > 1) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 0.dp),
                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    areas.forEachIndexed { index, area ->
                        val active = index == areaIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (active) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable {
                                    if (!active) {
                                        areaIndex = index
                                        selected = null
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = area.roomName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (active) MiuixTheme.colorScheme.onPrimary
                                else MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.library_seat_area_free, area.freeCount),
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (active) MiuixTheme.colorScheme.onPrimary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 座位图 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val data = map
                if (data != null && data.seats.isNotEmpty()) {
                    SeatMapHost(
                        data = data,
                        plan = planBitmap,
                        onSeat = { poi -> selected = poi.takeIf { it.isFree } },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LegendDot(
                            color = MiuixTheme.colorScheme.background,
                            label = stringResource(R.string.library_seat_legend_free),
                            bordered = true,
                        )
                        LegendDot(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.28f),
                            label = stringResource(R.string.library_seat_legend_taken),
                        )
                        LegendDot(
                            color = MiuixTheme.colorScheme.primary,
                            label = stringResource(R.string.library_seat_legend_selected),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.library_seat_free_count, data.freeCount),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) {
                            InfiniteProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                text = if (startHour < 0) stringResource(R.string.library_seat_no_slot)
                                else stringResource(R.string.library_seat_no_seat),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                    }
                }

                // 空态框已经说了「今天没时段 / 没座位」，这里就只在真的能选座时给提示，避免一句话说两遍
                val text = when {
                    selected != null -> stringResource(R.string.library_seat_selected, selected!!.title)
                    startHour < 0 || hint.isNotEmpty() -> ""
                    else -> stringResource(R.string.library_seat_select_seat)
                }
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ---------------- 预约 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (booking) {
                InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.library_seat_book),
                onClick = { book() },
                enabled = selected != null && !booking,
            )
        }

        if (rules != null && startOptions != null) {
            val from = hourLabel(startOptions.first)
            val to = hourLabel(startOptions.last + rules.minDurationHours)
            Text(
                text = stringResource(
                    R.string.library_seat_rule,
                    from,
                    to,
                    rules.minDurationHours,
                    rules.maxDurationHours,
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
        }
    }
}

/** 座位图宿主：自绘 View 装在 Compose 里，配色跟随 Miuix 主题 */
@Composable
private fun SeatMapHost(
    data: SeatMapData,
    plan: Bitmap?,
    onSeat: (SeatPoi) -> Unit,
) {
    // 底色用「卡片同款浅灰」，空闲座位画成白底 + 灰描边才立得起来（原来的浅灰对浅灰实测看不清）
    val backdrop = MiuixTheme.colorScheme.surfaceContainerHigh
    val free = MiuixTheme.colorScheme.background
    val taken = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.28f)
    val closed = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
    val stroke = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
    val selectedFill = MiuixTheme.colorScheme.primary
    val onSelected = MiuixTheme.colorScheme.onPrimary
    val takenText = MiuixTheme.colorScheme.onSurfaceVariantSummary

    AndroidView(
        factory = { ctx ->
            SeatMapView(ctx).apply { onSeatTap = { poi -> onSeat(poi) } }
        },
        update = { view ->
            view.freeFill = free.toArgb()
            view.takenFill = taken.toArgb()
            view.closedFill = closed.toArgb()
            view.freeStroke = stroke.toArgb()
            view.selectedFill = selectedFill.toArgb()
            view.onSelected = onSelected.toArgb()
            view.planBackdrop = backdrop.toArgb()
            view.takenText = takenText.toArgb()
            val expected = data to plan
            if (view.tag != expected) {
                view.tag = expected
                view.setMap(data)
                view.setPlanBitmap(plan)
            }
        },
        // 固定视口高度：View 内部按「宽高都装得下」算基准格，装不下就自己横竖拖动，
        // 不再让整页被撑长（否则图例和按钮会被挤到屏幕外）
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp),
    )
}

/** 图例：小色块 + 文案（白底那块要描边，否则在白卡片上看不见） */
@Composable
private fun LegendDot(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    bordered: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .then(
                    if (bordered) Modifier.border(
                        1.dp,
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
                        RoundedCornerShape(3.dp),
                    ) else Modifier
                ),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 左标题右值的一行（可点，右侧箭头式提示由文字颜色体现） */
@Composable
private fun InfoRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 第十九轮：按压高亮改成和卡片同圆角的整行（之前是直角灰方块、还只覆盖内容宽度）
            .pressableRow(onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.title4,
            color = if (onClick != null) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------- 时间与格式化 ----------------

private fun beginEpoch(hour: Int): Long =
    LocalDate.now().atTime(hour, 0).atZone(ZoneId.systemDefault()).toEpochSecond()

private fun hourLabel(hour: Int): String = "%02d:00".format(hour)

private fun slotLabel(startHour: Int, duration: Int): String =
    "%s - %s".format(hourLabel(startHour), hourLabel(startHour + duration))

internal fun epochLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

internal fun clockLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
