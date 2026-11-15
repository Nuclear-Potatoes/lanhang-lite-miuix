package com.lanhang.miuix.ui.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 / 列表类页面的**唯一**行规范（第八十一轮统一设计风格）。
 *
 * 统一口径（以「设置」页为准）：
 * - 标题 `body1`（不是 17sp Medium）
 * - 副标题 `footnote1`、`onSurfaceVariantSummary`、距标题 2dp
 * - 右侧值 `body2`、次要色（需要强调时传 [valueColor]）
 * - 可点行尾部统一 20dp `ChevronForward` 矢量图标（不是文本「›」）
 * - 上下 15dp、左右 16dp，与设置页每一行完全一致
 *
 * 之前账号与安全 / 权限控制 / 上课提醒各自写了一套 `body2.copy(fontSize = 17.sp,
 * fontWeight = Medium)` + 14dp + 文本「›」，和设置页放在一起能明显看出字号更粗、
 * 箭头更小、行更矮。现在这些页面都改成调用本组件，别再各写一份。
 */
@Composable
internal fun SettingRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.pressableRow(onClick = onClick, corner = 16.dp)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = valueColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            onClick != null -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 设置行里的「标题 + 说明 + 开关」。[enabled] 为 false 时整行与开关一起压暗，
 * 字号 / 间距与 [SettingRow] 完全相同。
 */
@Composable
internal fun SettingSwitchRow(
    title: String,
    summary: String? = null,
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
