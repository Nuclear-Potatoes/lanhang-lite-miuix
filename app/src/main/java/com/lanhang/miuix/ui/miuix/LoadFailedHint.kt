package com.lanhang.miuix.ui.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「加载失败」小字 + 点一下重新加载（第二十四轮第 3 条）。
 *
 * 用户原话：「如果联网失败导致无法加载出界面，应该显示小文字为加载失败，点击重新加载。
 * 点击后尝试刷新界面。」所以这里**不用大图标、不用卡片、不写长文案**：
 * 两行小字，整块可点，点了就把重试交给调用方。
 *
 * 用法：列表 / 页面自己维护 `failed` 标记，失败且没有任何内容可显示时画它，
 * 有内容（旧数据）时不要盖住内容 —— 失败只在「一片空白」时才需要这个出路。
 */
@Composable
fun LoadFailedHint(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    text: String = stringResource(R.string.load_failed),
    actionText: String = stringResource(R.string.load_retry),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onRetry)
            .padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = actionText,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
