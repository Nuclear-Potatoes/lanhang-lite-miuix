package com.lanhang.miuix.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换：不用系统的 per-app locale（`AppCompatDelegate.setApplicationLocales`），
 * 而是用 [wrap] 给每个 Activity / Service / RemoteViews 的 base context 包一层
 * `createConfigurationContext`，切换时显式 `Activity.recreate()`。
 *
 * 为什么不用系统的 per-app locale：它走的是系统级的配置变更 + Activity 重启，
 * 切换过程中会出现约 0.4s 的纯黑 relaunch 帧（实测小米/Android 15）。
 * [wrap] 这套方案不经过系统 relaunch，`recreate()` 是 Activity 自己重建，
 * 窗口不会被拆掉再拉起，因此不闪黑屏。
 *
 * 口径来自 E:\app_start 的 `util/LocaleHelper`（可直接对照），独立实现。
 */
object LocaleHelper {

    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    /** 进程启动时的系统语言：选「跟随系统」时回退到它 */
    private val systemLocale: Locale = Locale.getDefault()

    @Volatile
    var language: String = SYSTEM
        private set

    /** 进程启动时读一次偏好（[App.onCreate]），之后每次切换语言都会重新 prime */
    fun prime(language: String) {
        this.language = language
        Locale.setDefault(resolve(language))
    }

    fun resolve(language: String = this.language): Locale = when (language) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
        else -> systemLocale
    }

    /** 跟随系统时原样返回，否则包一层强制语言的 Context */
    fun wrap(base: Context): Context {
        if (language == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(resolve()))
        return base.createConfigurationContext(config)
    }
}
