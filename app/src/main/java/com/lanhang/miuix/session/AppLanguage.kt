package com.lanhang.miuix.session

import com.lanhang.miuix.R
import com.lanhang.miuix.util.LocaleHelper

/**
 * 界面语言：跟随系统 / 简体中文 / English。
 *
 * 不再走 AppCompat 的 per-app language（`setApplicationLocales`）——它会触发系统的
 * Activity 重启，切换过程有约 0.4s 的纯黑帧（见 [LocaleHelper] 的说明）。
 * 现在只做两件事：把偏好存到 [Session.language]、调 [LocaleHelper.prime] 让新语言
 * 对后续创建的 Context 生效；让当前界面立刻变语言靠 `Activity.recreate()`（设置页调）。
 *
 * 偏好存在 [Session.language]，每次启动由 [com.lanhang.miuix.App.onCreate] 重新 prime。
 */
object AppLanguage {

    const val SYSTEM = LocaleHelper.SYSTEM
    const val ZH = LocaleHelper.ZH
    const val EN = LocaleHelper.EN

    /** 设置页展示顺序 */
    val options = listOf(SYSTEM, ZH, EN)

    fun labelRes(mode: String): Int = when (mode) {
        ZH -> R.string.settings_language_zh
        EN -> R.string.settings_language_en
        else -> R.string.settings_language_system
    }

    /**
     * 让 [mode] 对**之后**创建的 Context 生效。
     *
     * 当前 Activity 不会因此自己刷新语言，需要调用方显式 `Activity.recreate()`
     * （见 [com.lanhang.miuix.mine.MineFragment.showLanguageDialog]）。
     */
    fun apply(mode: String) {
        LocaleHelper.prime(mode)
    }
}
