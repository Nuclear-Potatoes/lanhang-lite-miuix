package com.lanhang.miuix.mine

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.shell.ReportListScroll
import com.lanhang.miuix.ui.shell.ShellState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 身份码页的 UI 状态（数据与请求都在 [MineFragment] 里） */
data class IdCardUiState(
    val name: String = "",
    val staffNo: String = "",
    val department: String = "",
    val avatarUrl: String = "",
    val token: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val refreshedAt: String = "",
)

/**
 * 身份码页（对齐官方 `MyQrCodeActivity`）。
 *
 * 官方版式：头像 + 姓名 + 学号/工号 + 组织 → 学号条码 → 二维码 → 「刷新时间：…」，
 * 二维码里的内容就是 `GET /apps/getUserQRCode` 返回的那串 token（一次一换，点二维码重取）。
 *
 * 二维码用 zxing 现算：屏幕上的码必须**深色模块 + 白底**才扫得出来，
 * 所以这张卡片在深色主题下也保持白底黑码（和系统自带的会员码一致）。
 */
@Composable
fun IdCardScreen(
    state: IdCardUiState,
    onRefresh: () -> Unit,
    /** 滚动位置由外面的「我的」页面栈持有（用户第九轮第 3 条：返回上级不该跳回顶部） */
    listState: LazyListState = rememberLazyListState(),
) {
    ReportListScroll(listState, ShellState.TAB_MINE)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        item("card") {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanhangAvatar(url = state.avatarUrl, name = state.name, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.name.ifBlank { "--" },
                            style = MiuixTheme.textStyles.title2,
                            color = Color(0xFF111111),
                        )
                        Text(
                            text = listOf(state.staffNo, state.department)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                                .ifBlank { "--" },
                            style = MiuixTheme.textStyles.footnote1,
                            color = Color(0xFF8A8A8E),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                val barcode = remember(state.staffNo) {
                    if (state.staffNo.isBlank()) null else BarcodeRenderer.code128(state.staffNo, 900, 160)
                }
                if (barcode != null) {
                    Image(
                        bitmap = barcode.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                }

                val qr = remember(state.token) {
                    state.token?.takeIf { it.isNotBlank() }?.let { BarcodeRenderer.qr(it, 720) }
                }
                when {
                    state.loading && qr == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.idcard_loading),
                            style = MiuixTheme.textStyles.footnote1,
                            color = Color(0xFF8A8A8E),
                        )
                    }

                    qr != null -> Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = stringResource(R.string.idcard_title),
                        modifier = Modifier
                            .size(232.dp)
                            .clickable { onRefresh() },
                    )

                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.error ?: stringResource(R.string.idcard_failed),
                            style = MiuixTheme.textStyles.body2,
                            color = Color(0xFFC0392B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onRefresh() }
                                .padding(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.idcard_refresh_time, state.refreshedAt.ifBlank { "--" }),
                    style = MiuixTheme.textStyles.footnote2,
                    color = Color(0xFF9A9A9E),
                )
                Text(
                    text = stringResource(R.string.idcard_retry_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = Color(0xFFB4B4B8),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item("hint") {
            Text(
                text = stringResource(R.string.idcard_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}

/** zxing 的位图渲染（QR / Code128），失败一律返回 null，由页面降级 */
private object BarcodeRenderer {

    fun qr(content: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Code128 条码：内容就是学号/工号（官方身份码页同款） */
    fun code128(content: String, widthPx: Int, heightPx: Int): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val matrix = com.google.zxing.oned.Code128Writer()
            .encode(content, BarcodeFormat.CODE_128, widthPx, heightPx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
