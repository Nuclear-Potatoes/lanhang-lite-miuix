package com.lanhang.miuix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全应用统一的「加载中」占位（第四十五轮，用户第 11 条：
 * 「所有加载场景（工作圈图片、我的身份码等）都要有加载界面」）。
 *
 * 以前各页各写一套：有的只有转圈、有的只有文字、有的干脆什么都不画（白屏等数据）。
 * 这里收敛成两个组件，新写页面直接用，别再各画各的：
 *
 * - [LoadingBlock]：整页/整块的加载中（居中转圈 + 一行说明文字，文字可省略）；
 * - [ImageLoadingBox]：图片自己的占位骨架（圆角浅色块 + 小转圈），
 *   给 `SubcomposeAsyncImage` 的「正在取图 / 取图失败」两个状态用。
 */
@Composable
fun LoadingBlock(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            if (!label.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 图片加载占位：圆角浅色块 + 中央小转圈。
 * 尺寸由调用方的 `modifier` 决定（九宫格传 `weight(1f).aspectRatio(1f)`，头像传 `size(...)`）。
 */
@Composable
fun ImageLoadingBox(
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
    }
}
