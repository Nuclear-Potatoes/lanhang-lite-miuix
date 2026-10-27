package com.lanhang.miuix.widget

/**
 * 小部件排版变体。
 *
 * 三个变体共用同一套数据（[ScheduleWidgetData]）与渲染层（[ScheduleWidgetRenderer]），
 * 只有「画哪一块 + 用哪个布局」不同，别为某个尺寸另写一份数据/网络逻辑。
 *
 * - [WEEK]  4×4 整周课表（`ScheduleWidgetProvider`）
 * - [TODAY] 2×2 今日列表（`ScheduleWidgetSmallProvider`，带 ◀ ⟳ ▶ 翻天）
 * - [WIDE]  4×2 今日宽版（`ScheduleWidgetWideProvider`，两行「时刻 + 色条 + 课名」）
 */
enum class WidgetVariant { WEEK, TODAY, WIDE }
