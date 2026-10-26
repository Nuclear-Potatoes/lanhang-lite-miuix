package com.lanhang.miuix.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 圆形头像：先画「姓名首字 + 主题色底」兜底，有图再盖上去。
 *
 * 工作圈、我的、身份码、个人主页四处都要头像，统一走这里，免得四份实现细节各不相同。
 */
@Composable
fun LanhangAvatar(
    url: String?,
    name: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val initial = name.trim().take(1).ifBlank { "?" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Medium,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 头像占位用的中性灰（没有姓名也没有图时） */
val AvatarPlaceholder: Color = Color(0x33000000)
