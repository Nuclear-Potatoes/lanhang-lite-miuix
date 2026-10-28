package com.lanhang.miuix.schedule

import java.time.LocalTime

/**
 * 作息时间表：节次 → 上课 / 下课时间。
 *
 * 课表接口只返回节次号（`节次名`），不返回钟点，所以全校按这里统一换算。
 * 小部件「今日课程」判断一节课是否上完、算「距下课还有 N 分」，都以此为唯一来源，
 * 不要在别处再硬编码一份时间表（历史上出现过 3 份副本，改一处漏两处）。
 */
object PeriodClock {

    /** 12 节：08:00-08:45 … 21:45-22:30（第 4 节后午休、第 8 节后晚饭） */
    private val ranges = listOf(
        LocalTime.of(8, 0) to LocalTime.of(8, 45),
        LocalTime.of(8, 55) to LocalTime.of(9, 40),
        LocalTime.of(10, 0) to LocalTime.of(10, 45),
        LocalTime.of(10, 55) to LocalTime.of(11, 40),
        LocalTime.of(14, 0) to LocalTime.of(14, 45),
        LocalTime.of(14, 55) to LocalTime.of(15, 40),
        LocalTime.of(16, 0) to LocalTime.of(16, 45),
        LocalTime.of(16, 55) to LocalTime.of(17, 40),
        LocalTime.of(19, 0) to LocalTime.of(19, 45),
        LocalTime.of(19, 55) to LocalTime.of(20, 40),
        LocalTime.of(20, 50) to LocalTime.of(21, 35),
        // 2026-10-28 用户：加第 12 节（21:45-22:30）
        LocalTime.of(21, 45) to LocalTime.of(22, 30)
    )

    /** 一天最多 12 节（课表网格 / 倒计时 / 导出都按这个上限做边界判断） */
    val count: Int get() = ranges.size

    fun start(period: Int): LocalTime? = ranges.getOrNull(period - 1)?.first

    fun end(period: Int): LocalTime? = ranges.getOrNull(period - 1)?.second

    /** 当天的分钟数（0-1439）；节次越界返回 -1，调用方据此跳过 */
    fun startMinute(period: Int): Int = minutesOf(ranges.getOrNull(period - 1)?.first)

    fun endMinute(period: Int): Int = minutesOf(ranges.getOrNull(period - 1)?.second)

    fun minutesOf(time: LocalTime?): Int =
        if (time == null) -1 else time.hour * 60 + time.minute

    /** 「08:00-11:40」；单节为整段「08:00-08:45」；越界返回空串 */
    fun rangeText(startPeriod: Int, endPeriod: Int): String {
        val start = start(startPeriod) ?: return ""
        val end = end(if (endPeriod >= startPeriod) endPeriod else startPeriod) ?: return ""
        return "${format(start)}-${format(end)}"
    }

    private fun format(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}
