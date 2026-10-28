package com.lanhang.miuix.ui

import android.content.Context

/**
 * 界面缩放系数（第四十五轮，用户第 6 条：「课表所有文字与 UI 自适应，平板字号略微放大」）。
 *
 * 窄屏那一侧的自适应早就有了 —— 列宽/行高不够就按候选档降字号
 * （见 `ScheduleFragment.renderGrid` 的 `numSizeCandidates`）。缺的是**大屏方向**：
 * 候选字号上限写死 13sp / 8sp，平板格子又宽又高，字却和手机一样大，看着又小又空。
 *
 * 这里只放大「静态字号」和「候选档上限」，不碰「放不下就降档」的判定，
 * 所以窄屏行为完全不变；平板上字与行高一起变大，留白自然被填满。
 *
 * - 手机（screenWidthDp < 480）：1.0
 * - 大屏手机 / 折叠展开（≥ 480dp）：1.08
 * - 平板（smallestScreenWidthDp ≥ 600dp）：1.15
 */
object UiScale {
    fun factor(context: Context): Float {
        val cfg = context.resources.configuration
        return when {
            cfg.smallestScreenWidthDp >= 600 -> 1.15f
            cfg.screenWidthDp >= 480 -> 1.08f
            else -> 1f
        }
    }
}
