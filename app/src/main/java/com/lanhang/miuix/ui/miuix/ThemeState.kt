package com.lanhang.miuix.ui.miuix

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lanhang.miuix.session.Session

/**
 * 主题档位的唯一真相源（进程级 Compose 状态）。
 *
 * ## 为什么要单独做一个状态，而不是各处读 `Context.isNightMode()`
 *
 * 2026-10-30 用户反馈：「深色模式下 app 颜色没有随系统自动切换」。实测（模拟器
 * `adb shell cmd uimode night no`）确认：系统已经是浅色，界面仍全黑。
 *
 * 根因是**读配置的 Context 不一定是活的**：
 *
 * 1. `LocaleHelper.wrap()` 在「界面语言 ≠ 跟随系统」时用
 *    `base.createConfigurationContext(config)` 包一层，而 `config` 是
 *    `Configuration(base.resources.configuration)` 的**整份快照**（含 `uiMode`）。
 *    这个 override 优先级高于系统，于是 `uiMode` 被永久冻在「包裹那一刻」的值 ——
 *    系统之后怎么切夜间模式，`Activity.resources` 都还是旧值，`isNightMode()` 恒为 false。
 * 2. 即使语言是「跟随系统」，`LocalContext.current.isNightMode()` 读到的也是
 *    **Activity 缓存的那份 configuration**；系统切 uiMode 是否会真的让 Activity 重建，
 *    还取决于机型、`configChanges` 声明与 AppCompat 的 applyDayNight 时机，不可靠。
 *
 * 而 `Application.resources.configuration` 是**进程级、不被 LocaleHelper 包裹**的那一份，
 * 它就是系统真值；配合 `Application.registerComponentCallbacks` 还能收到变化回调。
 * 所以这里把它收拢成一个 Compose 状态：任何 Composable 读 [isDark] 都会自动重组，
 * 不依赖 Activity 重建、也不受语言包裹影响。
 *
 * ## 三个档位
 *
 * `Session.themeMode` = `"system"`（默认）/ `"light"` / `"dark"`。
 * [isDark] 把「系统值」和「用户强制值」合成最终结果。
 */
object ThemeState {

    /** 系统当前的夜间状态（由 Application 的 [ComponentCallbacks] 持续更新） */
    var systemDark by mutableStateOf(false)
        private set

    /** App 内设置的主题档位：`system` / `light` / `dark` */
    var mode by mutableStateOf("system")
        private set

    /** 最终是否使用深色：强制档位优先，否则跟随 [systemDark] */
    val isDark: Boolean
        get() = when (mode) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }

    /** 给非 Compose 的地方（自绘课表、RemoteViews、Dialog 宿主）读的一把口 */
    fun isDarkNow(): Boolean = isDark

    /** 设置页切换主题档位后调用，界面会立刻重组 */
    fun applyMode(newMode: String) {
        if (mode != newMode) mode = newMode
    }

    /** 冷启动时对一次账，并把系统侧的后续变化接进来 */
    fun init(app: Application) {
        systemDark = app.resources.configuration.isNightConfiguration()
        mode = Session.themeMode
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val dark = newConfig.isNightConfiguration()
                if (dark != systemDark) systemDark = dark
            }

            override fun onLowMemory() = Unit
        })
    }
}

/** 从一份 Configuration 里读夜间位（只认 UI_MODE_NIGHT_MASK） */
internal fun Configuration.isNightConfiguration(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
