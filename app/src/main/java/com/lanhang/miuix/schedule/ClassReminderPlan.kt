package com.lanhang.miuix.schedule

import com.lanhang.miuix.widget.ScheduleWidgetData
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「接下来该在哪些时刻提醒」的计算 —— 纯逻辑，不碰 AlarmManager、不写文件。
 *
 * 拆出来的理由与上游一致：这一层最容易出错（学期边界、单双周、同一节多门课、跨周），
 * 而它只依赖课表缓存和一个时间戳，可以脱离 Android 运行时单独推演。
 * 调度（[ClassReminder]）只负责把这里算出的时刻落成闹钟。
 *
 * 数据来源：直接复用小组件的 [ScheduleWidgetData.load]，它已经处理好了
 * 「周次换算 / 单双周 / 同一格的连堂合并」，不必再写第二份课表遍历 —— 少一份就可能两边不一致。
 */
object ClassReminderPlan {

    /** 预排窗口：今天起 7 个日历天。跨周自然覆盖，不需要「周一重排」这种特判 */
    const val WINDOW_DAYS = 7

    /** 窗口内最多排多少个闹钟（7 天 × 11 节 = 77 是理论上限，留余量后截断） */
    const val MAX_SLOTS = 64

    /**
     * 一个提醒槽位 = **某天的某一节次**，而不是「一条课程记录」。
     *
     * 按节次聚合的理由：同一时刻可能有多门课（合班、重修、数据重复下发），
     * 按课程排会连弹两条，而且通知 id 按节次分配，两条同 id 会互相覆盖。聚合后一课一条，正文里列多门。
     */
    data class Slot(
        /** 提醒所属日期 */
        val date: LocalDate,
        /** 起始节次（1..11），同时决定通知 id */
        val period: Int,
        /** 上课时刻（毫秒） */
        val startAt: Long,
        /** 提醒时刻（毫秒）= 上课时刻 − 提前量 */
        val triggerAt: Long,
        /** 这一节要上的课（通常 1 门，合班时多门） */
        val courses: List<ScheduleWidgetData.CourseBlock>,
    ) {
        /** 稳定标识：`epochDay-节次`。跨进程（PendingIntent extras）也用这个 */
        val key: String get() = keyOf(date, period)
    }

    fun keyOf(date: LocalDate, period: Int): String = "${date.toEpochDay()}-$period"

    /** 反解 [key]；格式不对返回 null（脏 extras 不能崩在广播里） */
    fun parseKey(key: String): Pair<LocalDate, Int>? {
        val parts = key.split('-')
        if (parts.size != 2) return null
        val day = parts[0].toLongOrNull() ?: return null
        val period = parts[1].toIntOrNull() ?: return null
        if (period !in 1..PeriodClock.count) return null
        return LocalDate.ofEpochDay(day) to period
    }

    /**
     * 某一天落在第几教学周；`null` = 不在学期内（假期 / 还没开学），调用方据此**不排闹钟**。
     *
     * 小组件的 [ScheduleWidgetData.load] 会把日期**钳制**到学期区间（假期也照样返回末周的数据），
     * 所以这里必须先自己判一次边界，否则整个暑假都会按末周的课天天响。
     */
    fun semesterWeekOf(date: LocalDate): Int? {
        val info = ScheduleRepository.loadCached()?.data?.weekInfo ?: return null
        val start = info.startWeek?.trim()?.toIntOrNull() ?: 1
        val end = info.endWeek?.trim()?.toIntOrNull() ?: 20
        val cached = info.currentWeek?.trim()?.toIntOrNull() ?: 1
        val first = minOf(start, end)
        val last = maxOf(start, end)
        val base = cached.coerceIn(first, last)
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val delta = ChronoUnit.WEEKS.between(todayMonday, date.with(DayOfWeek.MONDAY)).toInt()
        val week = base + delta
        return if (week in first..last) week else null
    }

    /** 某一天全部槽位（**不做时间过滤**，供触发时二次校验用） */
    fun slotsOn(date: LocalDate, advanceMinutes: Int): List<Slot> {
        if (semesterWeekOf(date) == null) return emptyList()
        val snapshot = ScheduleWidgetData.load(date) ?: return emptyList()
        val day = snapshot.days.firstOrNull { it.date == date } ?: return emptyList()
        val blocks = day.courses
        if (blocks.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val advanceMs = advanceMinutes.coerceIn(0, 60) * 60_000L
        return blocks.groupBy { it.start }
            .mapNotNull { (period, courses) ->
                val startTime: LocalTime = PeriodClock.start(period) ?: return@mapNotNull null
                val startAt = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                Slot(
                    date = date,
                    period = period,
                    startAt = startAt,
                    triggerAt = startAt - advanceMs,
                    courses = courses.sortedBy { it.name },
                )
            }
            .sortedBy { it.startAt }
    }

    /**
     * 从 [now] 起 [WINDOW_DAYS] 天内、**触发时刻还没到**的槽位，按时间升序。
     *
     * 只收 `triggerAt > now`：已经过去的提醒**不补发**（关机、没电、错过就是错过）。
     * 但「闹钟响晚了」是另一回事 —— 那次仍会在 [ClassReminder.handleAlarm] 里
     * 按「现在是否还没上课」再判一次，晚几分钟也照常提醒。
     */
    fun upcoming(now: Long, advanceMinutes: Int): List<Slot> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val result = mutableListOf<Slot>()
        for (offset in 0 until WINDOW_DAYS) {
            val date = today.plusDays(offset.toLong())
            result += slotsOn(date, advanceMinutes).filter { it.triggerAt > now }
            if (result.size >= MAX_SLOTS) break
        }
        return result.take(MAX_SLOTS)
    }

    /** 设置页上「下一次提醒」那一行；没有则 null */
    fun next(now: Long, advanceMinutes: Int): Slot? = upcoming(now, advanceMinutes).firstOrNull()
}
