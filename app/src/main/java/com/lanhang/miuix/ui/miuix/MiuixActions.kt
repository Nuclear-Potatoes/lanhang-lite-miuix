package com.lanhang.miuix.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 卡片内的动作胶囊。
 *
 * 第六十九轮（用户：「根据 nexio 的设计重做…所有的交互按钮」）：全站凡是「一行里并排两个以上动作」的地方
 * （座位预约的签到/暂离/退座、校园网的登录/退出 …）原来用的是 `TextButton`。Miuix 的 `TextButton`
 * 自带 40dp 最小高度和较宽内边距，两个并排就顶满一行、还把卡片撑高，和添加课程页里 32dp 的星期胶囊
 * 完全是两套语法。这里统一成 32dp 高、10dp 圆角、13sp 的胶囊：
 * - `primary = true` 的是这条记录「现在最该点的那个」（签到 / 返回 / 登录），主题色文字 + 12% 主题色底；
 * - 其余是次要动作，灰字 + 浅灰底。
 *
 * 点击不带涟漪（`indication = null`），与添加课程页的胶囊保持一致——Miuix 的卡片交互本身没有水波纹。
 */
@Composable
internal fun CardActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    val bg = when {
        !enabled -> scheme.surfaceContainerHighest.copy(alpha = 0.5f)
        primary -> scheme.primary.copy(alpha = 0.12f)
        else -> scheme.surfaceContainerHighest
    }
    val fg = when {
        !enabled -> scheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
        primary -> scheme.primary
        else -> scheme.onSurfaceVariantSummary
    }
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = fg,
        )
    }
}

/** 卡片底部一排动作胶囊（右对齐、8dp 间距）。 */
@Composable
internal fun CardActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 动作行：左边名字，右侧主题色动作文字 + 箭头，整行可点。
 *
 * 第六十九轮：Nexio 的卡片里没有独立的实心按钮，需要触发的动作一律做成「行」——
 * 它和值行长得一样，只是右侧是动词而不是当前值。原来「发一条测试提醒」「去授权」「发私信」
 * 这些是 `TextButton`，和旁边的行不在同一套视觉语法里。
 */
@Composable
internal fun ActionRow(
    title: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = action,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = "  \u203A",
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
