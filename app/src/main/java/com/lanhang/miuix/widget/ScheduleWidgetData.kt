package com.lanhang.miuix.widget

import com.lanhang.miuix.schedule.ScheduleData
import com.lanhang.miuix.schedule.PeriodClock
import com.lanhang.miuix.schedule.ScheduleRepository
import com.lanhang.miuix.session.Session
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object ScheduleWidgetData {

    data class CourseBlock(
        val name: String,
        val room: String,
        val start: Int,
        val end: Int,
        /** 任课教师（4×2 宽版的副信息要显示「第 N-M 节 | 教室 | 教师」） */
        val teacher: String = ""
    )

    data class DayInfo(
        val date: LocalDate,
        val courses: List<CourseBlock>
    )

    data class WeekSnapshot(
        val week: Int,
        val startWeek: Int,
        val endWeek: Int,
        val days: List<DayInfo>,
        val range: String
    )

    /**
     * 4×2「今日安排」要展示哪一天。
     *
     * 2026-10-27 第二十六轮（用户：「如果今日课程上完，那就在 4×2 小组件上显示明天的课表」）：
     * 今天的课**全部上完**（或者今天本来就没课）就翻到明天 —— 晚上打开桌面，看到的应该是
     * 「明天 / 周三」的课，而不是一句「今天的课已上完」占着两行空白；明天也没课才退回那句提示。
     *
     * 明天的快照单独 [load] 一次（可能跨周，`load` 自己会换算周次），不要用今天的 days 顶替。
     */
    data class WideTarget(
        val date: LocalDate,
        val snapshot: WeekSnapshot?,
        /** 展示的是明天：表头写「明天 / 周三」，且不按钟点淘汰课程 */
        val tomorrow: Boolean
    )

    fun wideTarget(now: LocalDateTime = LocalDateTime.now()): WideTarget {
        val today = now.toLocalDate()
        val nowMinute = PeriodClock.minutesOf(now.toLocalTime())
        val todaySnapshot = load(today)
        val leftToday = todaySnapshot?.days?.firstOrNull { it.date == today }?.courses.orEmpty()
            .count { PeriodClock.endMinute(it.end) > nowMinute }
        if (leftToday > 0) return WideTarget(today, todaySnapshot, false)
        val next = today.plusDays(1)
        val nextSnapshot = load(next)
        val nextCourses = nextSnapshot?.days?.firstOrNull { it.date == next }?.courses.orEmpty()
        return if (nextCourses.isNotEmpty()) {
            WideTarget(next, nextSnapshot, true)
        } else {
            WideTarget(today, todaySnapshot, false)
        }
    }

    private val dayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("M/d")

    /**
     * 生成小部件快照。
     *
     * @param targetDate 期望展示的日期（今日小部件翻天用）；null = 今天。
     *   会先钳制到学期范围（首周周一 ~ 末周周日），再换算成对应周次，
     *   跨周时取该周的按周缓存；无缓存则该周课程为空（不拿本周数据冒充）。
     */
    fun load(targetDate: LocalDate? = null): WeekSnapshot? {
        if (!Session.isLoggedIn) return null
        val base = ScheduleRepository.loadCached() ?: return null
        val data = base.data ?: return null
        val startWeek = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val endWeek = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 1
        val baseWeek = resolveWeek(cachedWeek, startWeek, endWeek)
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val week = resolveTargetWeek(baseWeek, startWeek, endWeek, todayMonday, targetDate)
        val monday = todayMonday.plusWeeks((week - baseWeek).toLong())
        val source = if (week == baseWeek) {
            ScheduleRepository.loadCachedWeek(week)?.data ?: data
        } else {
            ScheduleRepository.loadCachedWeek(week)?.data
        }
        val days = (0..6).map { index ->
            DayInfo(
                date = monday.plusDays(index.toLong()),
                courses = source?.let { coursesForDay(it, index, week) }.orEmpty()
            )
        }
        val sunday = monday.plusDays(6)
        return WeekSnapshot(
            week = week,
            startWeek = startWeek,
            endWeek = endWeek,
            days = days,
            range = "${dateFormatter.format(monday)} - ${dateFormatter.format(sunday)}"
        )
    }

    /**
     * 目标日期 → 周次：先钳制到学期区间（首周周一 ~ 末周周日），再按周差换算，
     * 最后夹在 start..end 之间。targetDate 为 null 时返回本周。
     */
    private fun resolveTargetWeek(
        baseWeek: Int,
        startWeek: Int,
        endWeek: Int,
        baseMonday: LocalDate,
        targetDate: LocalDate?
    ): Int {
        if (targetDate == null) return baseWeek
        val first = minOf(startWeek, endWeek)
        val last = maxOf(startWeek, endWeek)
        val semesterStart = baseMonday.plusWeeks((first - baseWeek).toLong())
        val semesterEnd = semesterStart.plusWeeks((last - first).toLong()).plusDays(6)
        val date = when {
            targetDate.isBefore(semesterStart) -> semesterStart
            targetDate.isAfter(semesterEnd) -> semesterEnd
            else -> targetDate
        }
        val deltaWeeks = ChronoUnit.WEEKS.between(baseMonday, date.with(DayOfWeek.MONDAY))
        return (baseWeek + deltaWeeks.toInt()).coerceIn(first, last)
    }

    /** 目标日期对应的周次（供导航时判断是否需要补拉该周缓存）；未登录/无缓存返回 null */
    fun targetWeekOf(targetDate: LocalDate): Int? {
        if (!Session.isLoggedIn) return null
        val data = ScheduleRepository.loadCached()?.data ?: return null
        val startWeek = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val endWeek = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 1
        val baseWeek = resolveWeek(cachedWeek, startWeek, endWeek)
        return resolveTargetWeek(
            baseWeek, startWeek, endWeek, LocalDate.now().with(DayOfWeek.MONDAY), targetDate
        )
    }

    private fun resolveWeek(cachedWeek: Int, startWeek: Int, endWeek: Int): Int {
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val anchorAt = Session.scheduleWeekAnchorAt
        val anchorWeek = Session.scheduleWeekAnchorWeek
        val raw = if (anchorAt > 0L && anchorWeek > 0) {
            val anchorDate = Instant.ofEpochMilli(anchorAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val anchorMonday = anchorDate.with(DayOfWeek.MONDAY)
            anchorWeek + ChronoUnit.WEEKS.between(anchorMonday, todayMonday).toInt()
        } else {
            val modified = ScheduleRepository.cacheLastModified()
            if (modified > 0L) {
                val cacheDate = Instant.ofEpochMilli(modified)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val cacheMonday = cacheDate.with(DayOfWeek.MONDAY)
                cachedWeek + ChronoUnit.WEEKS.between(cacheMonday, todayMonday).toInt()
            } else {
                cachedWeek
            }
        }
        val from = minOf(startWeek, endWeek)
        val to = maxOf(startWeek, endWeek)
        return raw.coerceIn(from, to)
    }

    private fun coursesForDay(data: ScheduleData, dayIndex: Int, week: Int): List<CourseBlock> {
        val targetName = dayNames.getOrNull(dayIndex) ?: return emptyList()
        val day = data.schedule?.find { it.dayName == targetName } ?: return emptyList()
        val items = mutableListOf<CourseBlock>()
        for (period in day.periods.orEmpty()) {
            val number = period.periodName?.trim()?.toIntOrNull() ?: continue
            if (number !in 1..PeriodClock.count) continue
            for (course in period.courses.orEmpty()) {
                if (!course.isWeekValid(week) || !course.isOddEvenValid(week)) continue
                val name = course.name?.trim().orEmpty().ifEmpty {
                    // 兜底文案跟着应用语言走（这里是 Application context），见 LocaleHelper
                    com.lanhang.miuix.util.LocaleHelper.wrap(
                        com.lanhang.miuix.schedule.AppContext.instance
                    ).getString(com.lanhang.miuix.R.string.schedule_unnamed_course)
                }
                items.add(
                    CourseBlock(
                        name = name,
                        room = course.room?.trim().orEmpty(),
                        start = number,
                        end = number,
                        teacher = course.teacher?.trim().orEmpty()
                    )
                )
            }
        }
        return merge(items.sortedBy { it.start })
    }

    private fun merge(items: List<CourseBlock>): List<CourseBlock> {
        if (items.isEmpty()) return items
        val result = mutableListOf<CourseBlock>()
        var current = items[0]
        for (index in 1 until items.size) {
            val next = items[index]
            val contiguous = next.start == current.end + 1
            val sameBreak = current.end != 4 && current.end != 8
            if (next.name == current.name && next.room == current.room &&
                next.teacher == current.teacher && contiguous && sameBreak
            ) {
                current = current.copy(end = next.end)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
