package com.lanhang.miuix.ui.miuix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lanhang.miuix.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一卡通充值的「扫码去微信付款」弹窗（2026-11-15 第八十三轮）。
 *
 * 背景：一卡通充值只走微信内 JSAPI 支付，WebView 里没有 `WeixinJSBridge`，点确认充值不可能拉起微信
 * （详见 `OaWebViewActivity.WX_BRIDGE_FALLBACK_JS` 的注释）。上一版给的是「复制链接」，
 * 用户反馈「生成二维码更方便扫」——这里就用 zxing 把当前充值页地址画成二维码，
 * 微信「扫一扫」即可在微信里打开同一页继续付款。复制链接保留为兜底（扫码失败/电脑上看的时候用）。
 *
 * 二维码内容是 `location.href` 拿到的完整地址，里面带学校网关下发的 `token`，
 * 所以微信里打开不需要重新登录。
 */
fun showRechargeQr(context: Context, url: String) {
    if (url.isBlank()) return
    showMiuixDialog(
        context,
        MiuixDialogSpec(
            content = { _ ->
                val bitmap = remember(url) { encodeQr(url, QR_PIXELS) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 自定义内容自己负责内边距：`MiuixDialogCard` 只有圆角/底色，没有 padding。
                        // 不补这一段，标题会直接贴在卡片顶边上（用户反馈「标题离弹窗顶部太近，很不美观」）。
                        // 顶部 26dp 比标准弹窗的 22dp 再松一点，因为这里标题下面是二维码，视觉重心更低。
                        .padding(start = 22.dp, end = 22.dp, top = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = context.getString(R.string.oa_ykt_pay_qr_title),
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = context.getString(R.string.oa_ykt_pay_qr_message),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = context.getString(R.string.oa_ykt_pay_qr_title),
                            modifier = Modifier.size(196.dp),
                        )
                    } else {
                        // 编码失败（内容过长等极端情况）：至少别留一块空白，直接告诉用户走复制
                        Text(
                            text = context.getString(R.string.oa_ykt_pay_qr_failed),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = context.getString(R.string.oa_ykt_pay_qr_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            actions = listOf(
                context.getString(R.string.oa_ykt_pay_dismiss) to {},
                context.getString(R.string.oa_ykt_pay_copy) to {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("recharge", url))
                        Toast.makeText(
                            context,
                            context.getString(R.string.oa_ykt_pay_copied),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ),
            /** 二维码偏大，手机横屏时也别把按钮顶出屏幕 */
            cancelable = true,
        ),
    )
}

/** 二维码边长（像素）。196dp 的展示尺寸 × 约 3x 密度，留足余量给扫码识别 */
private const val QR_PIXELS = 640

/**
 * 用 zxing 画一张黑白二维码位图。
 *
 * 纠错等级取 M：这条 URL 带 token，可能有 200+ 字符，M 在「密度」和「容错」之间比较平衡；
 * `MARGIN` 给 1 是为了扫码软件认得出边界，又不至于在弹窗里占太多白边。
 * 失败返回 null，由调用方降级成文字提示（身份码页 `BarcodeRenderer` 是同一套写法）。
 */
private fun encodeQr(content: String, sizePx: Int): Bitmap? = runCatching {
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
