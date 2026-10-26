package com.lanhang.miuix.ui.miuix

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 当前 Context 是否处于夜间模式。
 *
 * 主题模式由 `Session.themeMode` + `AppCompatDelegate.setDefaultNightMode` 决定，
 * 所以 Compose 侧不要只看 `isSystemInDarkTheme()`，要跟随 Activity 的 Configuration。
 */
fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * 蓝航 Lite 的 Miuix 主题。
 *
 * 直接使用 Miuix 自带的亮/暗两套色板（`lightColorScheme` / `darkColorScheme`），
 * 这样所有 Miuix 组件（卡片、开关、胶囊按钮、导航栏…）都保持库本身的设计观感。
 */
@Composable
fun LanhangMiuixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme) {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    // Miuix 的 OverlayDialog 内部用 NavigationBackHandler 接返回键；Fragment 里的 ComposeView
    // 拿不到 navigation-event 宿主（androidx.activity 1.9 的 ComponentActivity 不实现这个 owner），
    // 不补就会抛 "No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner"
    // ——用户 2026-10-24 反馈「点评论/＋就崩」就是它。这里给整棵树提供一个进程内可用的 dispatcher。
    val navigationEventOwner = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navigationEventOwner) {
        MiuixTheme(colors = colors, content = content)
    }
}
