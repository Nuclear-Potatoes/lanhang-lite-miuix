package com.lanhang.miuix.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 课表页顶部控件（对齐用户给的参考截图：居中「第 N 周」大标题，两侧极简图标）。
 *
 * 结构：`[回到本周]  .........  ‹ 第 N 周 ›  .........  [网格/周视图]`
 * - 点周次 → Miuix 对话框跳周；左右箭头 → 上一/下一周；网格本体也支持左右滑动切周
 * - 参考截图右上角的 ⋮ 是空菜单，用户要求删掉，这里换成真正有用的视图切换
 *
 * 2026-10-24 二次改版：原来是「左胶囊 + 右 TabRow」，用户要求按截图重排并压缩高度
 * （一屏装下整周课表），所以改成一行图标 + 居中标题。
 */
@Stable
class ScheduleHeaderState {
    var weekLabel by mutableStateOf("")
    var isCurrentWeek by mutableStateOf(true)
    var showingGrid by mutableStateOf(true)

    /** 自定义课表背景时的配色覆盖；null = 用 Miuix 主题色 */
    var accent by mutableStateOf<Color?>(null)
    var onAccent by mutableStateOf<Color?>(null)
    var container by mutableStateOf<Color?>(null)
    var onContainer by mutableStateOf<Color?>(null)
}

@Composable
fun ScheduleHeader(
    state: ScheduleHeaderState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickWeek: () -> Unit,
    onSelectView: (Boolean) -> Unit,
    onToday: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val accent = state.accent ?: scheme.primary
    val onContainer = state.onContainer ?: scheme.onSurface
    // 图标不能用 container(chipFill)：那是近白的胶囊底色，画在浅色页面上等于隐形
    // （用户 2026-10-24：翻周箭头和视图切换按钮「和白色背景融为一体」）。
    val iconTint = (state.onContainer ?: scheme.onSurface).copy(alpha = 0.72f)
    val muted = iconTint

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToday,
            minWidth = 36.dp,
            minHeight = 36.dp,
            cornerRadius = 50.dp,
        ) {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = stringResource(R.string.schedule_back_to_today),
                modifier = Modifier.size(19.dp),
                // 「回到本周」只有离开本周后才可点，才点亮强调色；在本周内保持常规灰
                tint = if (state.isCurrentWeek) muted else accent,
            )
        }

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onPrev, minWidth = 30.dp, minHeight = 30.dp, cornerRadius = 50.dp) {
            Icon(
                imageVector = MiuixIcons.ChevronBackward,
                contentDescription = stringResource(R.string.schedule_prev_week),
                modifier = Modifier.size(19.dp),
                tint = muted,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                // 强调方向：**本周**才是被强调的那个（蓝字 + 淡强调底）；
                // 其他周退回普通文字色、不加底（用户 2026-10-24：原来正好反了）
                .background(if (state.isCurrentWeek) accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(onClick = onPickWeek)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = state.weekLabel,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                // 本周 = 强调色；其他周 = 普通正文色（黑/白，跟随主题）
                color = if (state.isCurrentWeek) accent else onContainer,
            )
        }
        IconButton(onClick = onNext, minWidth = 30.dp, minHeight = 30.dp, cornerRadius = 50.dp) {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = stringResource(R.string.schedule_next_week),
                modifier = Modifier.size(19.dp),
                tint = muted,
            )
        }

        Spacer(Modifier.weight(1f))

        // 视图切换：图标显示「点一下会切到哪」——网格态给列表图标，周视图态给网格图标
        IconButton(
            onClick = { onSelectView(!state.showingGrid) },
            minWidth = 36.dp,
            minHeight = 36.dp,
            cornerRadius = 50.dp,
        ) {
            Icon(
                imageVector = if (state.showingGrid) MiuixIcons.ListView else MiuixIcons.GridView,
                contentDescription = stringResource(
                    if (state.showingGrid) R.string.schedule_switch_to_week else R.string.schedule_switch_to_grid
                ),
                modifier = Modifier.size(19.dp),
                tint = muted,
            )
        }
    }
}
