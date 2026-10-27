package com.lanhang.miuix.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.core.isCancellation
import com.lanhang.miuix.core.userDetail
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.miuix.showMiuixInfo
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
 * 我的座位预约（2026-10-27）。
 *
 * 每条预约按服务端的订单状态机给出可用动作（签到 / 暂离 / 续座 / 签退 / 取消），
 * 签到窗口一律按**服务端下发的时间**算（[SeatBooking.serverNow]），手机时钟偏几分钟就会出错。
 */
@Composable
fun SeatBookingsScreen(
    onOpenSignCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bookings by remember { mutableStateOf<List<SeatBooking>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        try {
            bookings = withContext(Dispatchers.IO) { LibrarySeatApi.myBookings() }
            hint = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hint = context.getString(R.string.library_seat_load_failed, e.userDetail(context))
        } finally {
            loading = false
        }
    }

    fun run(action: SeatAction, booking: SeatBooking) {
        if (busyId != null) return
        busyId = booking.id
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { LibrarySeatApi.perform(action, booking) }
                val okText = when (action) {
                    SeatAction.CHECK_IN -> R.string.seat_action_check_in_ok
                    SeatAction.CHECK_OUT -> R.string.seat_action_check_out_ok
                    SeatAction.STEP_OUT -> R.string.seat_action_step_out_ok
                    SeatAction.COME_BACK -> R.string.seat_action_come_back_ok
                    SeatAction.CANCEL -> R.string.seat_action_cancel_ok
                }
                showMiuixInfo(
                    context,
                    context.getString(okText),
                    message?.takeIf { it.isNotBlank() },
                    context.getString(R.string.common_close),
                )
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showMiuixInfo(
                    context,
                    context.getString(R.string.seat_action_failed, e.userDetail(context)),
                    null,
                    context.getString(R.string.common_close),
                )
            } finally {
                busyId = null
            }
        }
    }

    fun confirmThen(titleRes: Int, message: String, action: SeatAction, booking: SeatBooking) {
        showMiuixConfirm(
            context = context,
            title = context.getString(titleRes),
            message = message,
            confirmText = context.getString(titleRes),
            dismissText = context.getString(R.string.common_cancel),
            onConfirm = { run(action, booking) },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            loading && bookings.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { InfiniteProgressIndicator(modifier = Modifier.size(22.dp)) }

            bookings.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hint.ifBlank { stringResource(R.string.library_seat_bookings_empty) },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                if (hint.isNotEmpty()) {
                    item("hint") {
                        Text(
                            text = hint,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                        )
                    }
                }
                items(bookings, key = { it.id }) { booking ->
                    BookingCard(
                        booking = booking,
                        busy = busyId == booking.id,
                        onAction = { action -> run(action, booking) },
                        onCancel = {
                            confirmThen(
                                R.string.seat_cancel_confirm_title,
                                context.getString(R.string.seat_cancel_confirm),
                                SeatAction.CANCEL,
                                booking,
                            )
                        },
                        onStepOut = {
                            confirmThen(
                                R.string.seat_step_out_confirm_title,
                                context.getString(
                                    R.string.seat_step_out_confirm,
                                    booking.limitLeftBack / 60,
                                ),
                                SeatAction.STEP_OUT,
                                booking,
                            )
                        },
                        onCheckOut = {
                            confirmThen(
                                R.string.seat_check_out_confirm_title,
                                context.getString(R.string.seat_check_out_confirm),
                                SeatAction.CHECK_OUT,
                                booking,
                            )
                        },
                        onSignCode = onOpenSignCode,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookingCard(
    booking: SeatBooking,
    busy: Boolean,
    onAction: (SeatAction) -> Unit,
    onCancel: () -> Unit,
    onStepOut: () -> Unit,
    onCheckOut: () -> Unit,
    onSignCode: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.library_seat_map_title, booking.roomName, booking.seatNum),
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(statusTextRes(booking.status)),
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (booking.isInUse) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.library_seat_time_row,
                    epochLabel(booking.beginAt),
                    clockLabel(booking.endAt),
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                if (booking.canCheckIn()) {
                    TextButton(
                        text = stringResource(R.string.seat_action_check_in),
                        onClick = { onAction(SeatAction.CHECK_IN) },
                        enabled = !busy,
                    )
                }
                if (booking.isInUse) {
                    TextButton(
                        text = stringResource(R.string.seat_action_step_out),
                        onClick = onStepOut,
                        enabled = !busy,
                    )
                    TextButton(
                        text = stringResource(R.string.seat_action_check_out),
                        onClick = onCheckOut,
                        enabled = !busy,
                    )
                }
                if (booking.isAway) {
                    TextButton(
                        text = stringResource(R.string.seat_action_come_back),
                        onClick = { onAction(SeatAction.COME_BACK) },
                        enabled = !busy,
                    )
                    TextButton(
                        text = stringResource(R.string.seat_action_check_out),
                        onClick = onCheckOut,
                        enabled = !busy,
                    )
                }
                if (booking.isPendingSign) {
                    TextButton(
                        text = stringResource(R.string.seat_sign_code_action),
                        onClick = onSignCode,
                        enabled = !busy,
                    )
                    TextButton(
                        text = stringResource(R.string.seat_action_cancel),
                        onClick = onCancel,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/**
 * 座位签到码（二维码由服务端画好，这里只负责显示与刷新）。
 *
 * 签到窗口：开始前 30 分钟 ~ 开始后 30 分钟（服务端下发，见 [SeatBooking.canCheckIn]）。
 */
@Composable
fun SeatSignCodeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        failed = false
        try {
            val bytes = withContext(Dispatchers.IO) {
                val code = LibrarySeatApi.signCode()
                LibrarySeatApi.signCodeImage(code)
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) failed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
            bitmap = null
        } finally {
            loading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val image = bitmap
                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) { InfiniteProgressIndicator(modifier = Modifier.size(22.dp)) }
                } else if (image != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(10.dp),
                    ) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = stringResource(R.string.seat_sign_code_title),
                            modifier = Modifier.size(220.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.seat_sign_code_failed),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = stringResource(R.string.seat_sign_code_refresh),
                    onClick = { if (!loading) reload++ },
                    enabled = !loading,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.seat_sign_code_hint),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (failed && !loading) Spacer(Modifier.height(0.dp))
    }
}

/** 订单状态码 → 文案（口径见调研报告 §9：0 待签到 / 1 使用中 / 2 暂离 / ≥3 已结束） */
private fun statusTextRes(status: Int): Int = when (status) {
    0 -> R.string.seat_status_pending
    1 -> R.string.seat_status_in_use
    2 -> R.string.seat_status_away
    3 -> R.string.seat_status_checked_out
    4 -> R.string.seat_status_canceled
    5 -> R.string.seat_status_violated
    6 -> R.string.seat_status_no_return
    7 -> R.string.seat_status_system
    8 -> R.string.seat_status_refused
    else -> R.string.seat_status_unknown
}
