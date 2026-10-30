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

    /**
     * 重新对一次账：**每个 Activity 回到前台都要调**（见 `App.onActivityResumed`）。
     *
     * 2026-10-30 用户第二次反馈「切换深浅色模式时 app 没有正确切换」，参考录屏拆帧后确认
     * 操作是**下拉通知栏点「深色模式」开关**。这条路径和 `cmd uimode night` 不一样：
     * 通知栏展开时 Activity 只是被遮挡、并没有暂停，系统这次 config change 不会可靠地
     * 派发到进程 —— [ComponentCallbacks.onConfigurationChanged] 收不到，于是 [systemDark]
     * 停在旧值，收起通知栏回到 App 一看还是原来的亮色。
     *
     * 所以除了被动等回调，还得在「每次回到前台」主动问一次系统：
     * 优先问 `UiModeManager`（系统真值，不依赖有没有派发 configuration），问不到才退回
     * 读 `Application.resources.configuration`。
     */
    fun refresh(context: android.content.Context) {
        val dark = readSystemDark(context)
        if (dark != systemDark) systemDark = dark
    }

    /** 冷启动时对一次账，并把系统侧的后续变化接进来 */
    fun init(app: Application) {
        systemDark = readSystemDark(app)
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

/**
 * 系统侧「现在是不是深色」的真值。
 *
 * `UiModeManager.nightMode` 直接就是用户在设置/通知栏选的那一档，跟 configuration
 * 有没有派发到本进程无关；只有 AUTO / CUSTOM（日出日落、定时）这两档它不回答「此刻」，
 * 才退回读 `Application.resources.configuration`（进程级、不被 LocaleHelper 包裹）。
 */
private fun readSystemDark(context: android.content.Context): Boolean {
    val um = context.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    when (um?.nightMode) {
        android.app.UiModeManager.MODE_NIGHT_YES -> return true
        android.app.UiModeManager.MODE_NIGHT_NO -> return false
    }
    val app = context.applicationContext ?: context
    return app.resources.configuration.isNightConfiguration()
}
