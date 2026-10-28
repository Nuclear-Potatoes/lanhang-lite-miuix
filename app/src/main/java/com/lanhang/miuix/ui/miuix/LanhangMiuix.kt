package com.lanhang.miuix.ui.miuix

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.theme.LocalContentColor
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
 * 深色模式下的主色。
 *
 * Miuix 自带的 `darkColorScheme()` 给的是 `#277AF7`——它在纯黑（#000）上是 5.2:1、
 * 在卡片灰（#242424）上只有 3.9:1，肉眼就是「发闷的深蓝」。蓝色姓名、链接、选中态、
 * 各种按钮在深色下全部不够跳（用户 2026-10-27 第二十轮第 3 条反馈）。
 *
 * 按 HyperOS / Material3 的深色标准（深底用更高明度的同色相），把主色提到 `#4C8DFF`：
 * 黑底 6.6:1、卡片灰 4.9:1，饱和度不变，白字压上去依然清楚。
 */
private val DarkPrimary = Color(0xFF4C8DFF)
private val DarkPrimaryVariant = Color(0xFF5C9BFF)
private val DarkPrimaryContainer = Color(0xFF2A4E8F)
private val DarkOnPrimaryContainer = Color(0xFFD8E6FF)

/**
 * 把一个强调色往白里提一档（深色模式用）。
 *
 * 只做「朝白插值」：色相不变、饱和度略降、明度上去，和 HyperOS 深色下那套强调色同一路子。
 */
fun Color.liftForDark(amount: Float = 0.30f): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

/** 深色主题是否生效（跟随 Miuix 背景亮度，比 `isSystemInDarkTheme` 靠得住） */
@Composable
fun isDarkTheme(): Boolean = MiuixTheme.colorScheme.background.luminance() < 0.5f

/**
 * 语义强调色：浅色模式用原色，深色模式自动提亮一档。
 * 分数色、设置项图标这类「写死的语义色」都过一遍它，深色下才不会糊成一团。
 */
@Composable
fun accentColor(base: Color): Color = if (isDarkTheme()) base.liftForDark() else base

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
        if (darkTheme) {
            // 2026-10-29 用户要求深色配色：所有顶栏与工作圈内容区都是纯黑（不是灰），
            // 只有评论区是灰。顶栏(ShellBars)与页面内容区都用 colorScheme.background，
            // 评论区用 surfaceContainerHigh —— 所以 background=纯黑、surfaceContainerHigh=灰，
            // 就把「顶栏/内容区纯黑 + 评论区灰」一次铺到位。
            darkColorScheme(
                primary = DarkPrimary,
                onPrimary = Color.White,
                primaryVariant = DarkPrimaryVariant,
                onPrimaryVariant = Color.White,
                primaryContainer = DarkPrimaryContainer,
                onPrimaryContainer = DarkOnPrimaryContainer,
                background = Color.Black,
                surfaceContainerHigh = Color(0xFF242424),
                surfaceContainerHighest = Color(0xFF2D2D2D),
            )
        } else {
            // 2026-10-29 用户要求浅色配色：所有顶栏与工作圈内容区都是淡灰（彼此一致），
            // 评论区比淡灰更深一档以拉出对比度。同样是 background=淡灰、surfaceContainerHigh=更深灰。
            lightColorScheme(
                background = Color(0xFFF5F5F5),
                surfaceContainerHigh = Color(0xFFE4E4E4),
                surfaceContainerHighest = Color(0xFFDCDCDC),
            )
        }
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
        MiuixTheme(colors = colors) {
            // 2026-10-27 第二十一轮：Miuix 的 LocalContentColor 默认值是 **Color.Black**，
            // 而 MiuixTheme 自己不提供它 —— 任何没写 tint/color 的 Icon / Text 都拿黑，
            // 浅色主题下看不出来，深色主题下就是「黑色图标压黑底」。
            // 用户反馈的「顶栏返回键 / 首页键和背景融在一起」就是它。这里统一兜住。
            CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
                content()
            }
        }
    }
}
