package com.lanhang.miuix.ui.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片里「整行可点」的统一按压效果。
 *
 * 直接用 `Modifier.clickable { }` 时，按压高亮是按**行本身那个矩形**画的：
 * 四个角是直角，而外面的 `Card` 是圆角，于是屏幕上会浮出一个灰方块，
 * 边缘还跟卡片对不齐（用户第十九轮原话：「阴影没完全覆盖这一栏，而是呈现一个正方形」）。
 *
 * 这里把点击层先 `clip` 成和卡片一致的圆角，再挂 clickable：
 * 高亮范围 = 整行（含 padding），圆角 = 卡片圆角，按下去就是 MIUI 那种「整行压暗」。
 *
 * 约定：行内 padding 写在 `pressableRow` **之后**（这样高亮能覆盖 padding 与整行宽度），
 * `onClick` 传 `null` 表示这一行不可点（此时只裁剪、不挂点击）。
 */
fun Modifier.pressableRow(
    onClick: (() -> Unit)?,
    corner: Dp = 12.dp,
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
