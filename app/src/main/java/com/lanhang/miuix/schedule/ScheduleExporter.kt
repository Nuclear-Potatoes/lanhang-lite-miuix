package com.lanhang.miuix.schedule

import android.content.Context
import com.lanhang.miuix.R
import com.lanhang.miuix.session.Session
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object ScheduleExporter {

    data class Plan(
        val currentWeek: Int,
        val termStart: Int,
        val termEnd: Int,
        val weeks: IntRange
    )

    data class ExportPayload(
        val ics: String,
        val csv: String,
        val text: String,
        /** 不含扩展名的基名，调用方自行拼 .ics / .csv */
        val baseFileName: String,
        val courseCount: Int,
        val currentWeek: Int,
        val firstWeek: Int,
        val lastWeek: Int
    )

    private val dayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    )
    /** 第三方课表 App（Sleepy 等）只认「周一」或 1-7，不认「星期一」 */
    private val weekDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val periodTimes = listOf(
        "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
        "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
        "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35"
    )
    private val icsDate = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val icsStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
    private val fileDate = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val displayDate = DateTimeFormatter.ofPattern("M/d", Locale.US)

    private data class Occurrence(
        val week: Int,
        val day: Int,
        val start: Int,
        val end: Int,
        val name: String,
        val teacher: String,
        val room: String
    )

    /**
     * 一个「课程时段」= 星期 + 起止节 + 周次区间（不按周展开），
     * 用于生成兼容第三方课表 App（Sleepy / WakeUp 系）的 CSV。
     */
    private data class Slot(
        val day: Int,
        val start: Int,
        val end: Int,
        val name: String,
        val teacher: String,
        val room: String,
        /** 原始周次串，如 "6-15"、"1-16,18"；空表示整学期 */
        val weekRange: String,
        /** 原始单双周：0=每周 1=单周 2=双周 */
        val oddEven: String
    )

    fun plan(data: ScheduleData): Plan? {
        val termStart = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val termEnd = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val startWeek = minOf(termStart, termEnd)
        val endWeek = maxOf(termStart, termEnd)
        val currentWeek = resolveCurrentWeek(data, startWeek, endWeek)
        if (currentWeek > endWeek) return null
        return Plan(currentWeek, startWeek, endWeek, startWeek..endWeek)
    }

    fun build(
        context: Context,
        data: ScheduleData,
        plan: Plan,
        weeks: Map<Int, ScheduleData>
    ): ExportPayload? {
        val unnamed = context.getString(R.string.schedule_unnamed_course)
        val occurrences = collectOccurrences(weeks, unnamed)
        if (occurrences.isEmpty()) return null
        val slots = collectSlots(weeks, plan, unnamed)
        val currentMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        return ExportPayload(
            ics = buildIcs(context, occurrences, plan.currentWeek, currentMonday),
            csv = buildCsv(slots),
            text = buildText(context, data, occurrences, plan, currentMonday),
            baseFileName = "${context.getString(R.string.schedule_export_file_prefix)}_" +
                LocalDate.now().format(fileDate),
            courseCount = occurrences.map { it.name }.distinct().size,
            currentWeek = plan.currentWeek,
            firstWeek = plan.weeks.first,
            lastWeek = plan.weeks.last
        )
    }

    private fun resolveCurrentWeek(data: ScheduleData, startWeek: Int, endWeek: Int): Int {
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: startWeek
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val anchorAt = Session.scheduleWeekAnchorAt
        val anchorWeek = Session.scheduleWeekAnchorWeek
        val raw = if (anchorAt > 0L && anchorWeek > 0) {
            val anchorMonday = Instant.ofEpochMilli(anchorAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .with(DayOfWeek.MONDAY)
            anchorWeek + ChronoUnit.WEEKS.between(anchorMonday, todayMonday).toInt()
        } else {
            val modified = ScheduleRepository.cacheLastModified()
            if (modified > 0L) {
                val cacheMonday = Instant.ofEpochMilli(modified)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .with(DayOfWeek.MONDAY)
                cachedWeek + ChronoUnit.WEEKS.between(cacheMonday, todayMonday).toInt()
            } else {
                cachedWeek
            }
        }
        return raw.coerceIn(startWeek, endWeek)
    }

    private fun collectOccurrences(weeks: Map<Int, ScheduleData>, unnamed: String): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        for ((week, data) in weeks.toSortedMap()) {
            for (dayIndex in dayNames.indices) {
                val day = data.schedule?.find { it.dayName == dayNames[dayIndex] } ?: continue
                val raw = mutableListOf<Occurrence>()
                for (period in day.periods.orEmpty()) {
                    val node = period.periodName?.trim()?.toIntOrNull() ?: continue
                    if (node !in 1..11) continue
                    for (course in period.courses.orEmpty()) {
                        raw.add(
                            Occurrence(
                                week = week,
                                day = dayIndex + 1,
                                start = node,
                                end = node,
                                name = course.name?.trim().orEmpty().ifEmpty { unnamed },
                                teacher = course.teacher?.trim().orEmpty(),
                                room = course.room?.trim().orEmpty()
                            )
                        )
                    }
                }
                raw.sortBy { it.start }
                var current: Occurrence? = null
                for (item in raw) {
                    val previous = current
                    val mergeable = previous != null &&
                        item.start == previous.end + 1 &&
                        previous.end != 4 &&
                        previous.end != 8 &&
                        item.name == previous.name &&
                        item.teacher == previous.teacher &&
                        item.room == previous.room
                    if (mergeable) {
                        current = previous.copy(end = item.end)
                    } else {
                        if (previous != null) result.add(previous)
                        current = item
                    }
                }
                if (current != null) result.add(current)
            }
        }
        return result.sortedWith(compareBy({ it.week }, { it.day }, { it.start }))
    }

    /**
     * 收集「课程时段」（含原始周次区间，不按周展开）。
     *
     * GetSchedule 按周返回数据，同一门课会在多周里重复出现，字段完全一致，
     * 因此合并相邻节次后按整条记录去重即可还原出原始周次串。
     */
    private fun collectSlots(
        weeks: Map<Int, ScheduleData>,
        plan: Plan,
        unnamed: String
    ): List<Slot> {
        val all = LinkedHashSet<Slot>()
        for (data in weeks.toSortedMap().values) {
            for (dayIndex in dayNames.indices) {
                val day = data.schedule?.find { it.dayName == dayNames[dayIndex] } ?: continue
                val raw = mutableListOf<Slot>()
                for (period in day.periods.orEmpty()) {
                    val node = period.periodName?.trim()?.toIntOrNull() ?: continue
                    if (node !in 1..11) continue
                    for (course in period.courses.orEmpty()) {
                        raw.add(
                            Slot(
                                day = dayIndex + 1,
                                start = node,
                                end = node,
                                name = course.name?.trim().orEmpty().ifEmpty { unnamed },
                                teacher = course.teacher?.trim().orEmpty(),
                                room = course.room?.trim().orEmpty(),
                                weekRange = course.weekRange?.trim().orEmpty(),
                                oddEven = course.oddEven?.trim().orEmpty()
                            )
                        )
                    }
                }
                raw.sortBy { it.start }
                var current: Slot? = null
                for (item in raw) {
                    val previous = current
                    val mergeable = previous != null &&
                        item.start == previous.end + 1 &&
                        previous.end != 4 &&
                        previous.end != 8 &&
                        item.name == previous.name &&
                        item.teacher == previous.teacher &&
                        item.room == previous.room &&
                        item.weekRange == previous.weekRange &&
                        item.oddEven == previous.oddEven
                    if (mergeable) {
                        current = previous.copy(end = item.end)
                    } else {
                        if (previous != null) all.add(previous)
                        current = item
                    }
                }
                if (current != null) all.add(current)
            }
        }
        // 周次串若在某些周次响应里缺失，优先保留有周次信息的那条，避免多出整学期重复行
        val bySpan = all.groupBy {
            "${it.day}|${it.start}|${it.end}|${it.name}|${it.teacher}|${it.room}"
        }
        val slots = mutableListOf<Slot>()
        for (group in bySpan.values) {
            val withWeek = group.filter { it.weekRange.isNotEmpty() }
            slots.addAll((withWeek.ifEmpty { group }).distinct())
        }
        val fallbackWeeks = "${plan.weeks.first}-${plan.weeks.last}"
        return slots
            .map {
                val weeks = normalizeWeekRange(it.weekRange)
                it.copy(weekRange = weeks.ifEmpty { fallbackWeeks })
            }
            .sortedWith(compareBy({ it.day }, { it.start }, { it.name }))
    }

    private fun buildIcs(
        context: Context,
        occurrences: List<Occurrence>,
        currentWeek: Int,
        currentMonday: LocalDate
    ): String {
        val sb = StringBuilder()
        fun line(value: String) {
            sb.append(value).append("\r\n")
        }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//LanhangLite//Schedule//ZH")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:${context.getString(R.string.schedule_export_file_prefix)}")
        val stamp = Instant.now().atZone(ZoneOffset.UTC).format(icsStamp)
        for (item in occurrences) {
            val date = currentMonday
                .plusWeeks((item.week - currentWeek).toLong())
                .plusDays((item.day - 1).toLong())
            val startTime = periodTimes.getOrNull(item.start - 1)?.first ?: continue
            val endTime = periodTimes.getOrNull(item.end - 1)?.second ?: continue
            val description = buildList {
                add(
                    if (item.start == item.end) {
                        context.getString(R.string.schedule_export_period_single, item.start)
                    } else {
                        context.getString(R.string.schedule_export_period_range, item.start, item.end)
                    }
                )
                if (item.room.isNotEmpty()) add(item.room)
                if (item.teacher.isNotEmpty()) {
                    add(context.getString(R.string.course_label_teacher) + item.teacher)
                }
                add(context.getString(R.string.schedule_week_format, item.week))
            }.joinToString("\n")
            line("BEGIN:VEVENT")
            line(
                "UID:${item.week}-${item.day}-${item.start}-${item.end}-${item.name.hashCode()}" +
                    "-${item.teacher.hashCode()}@lanhang.lite"
            )
            line("DTSTAMP:$stamp")
            line("DTSTART:${icsDate.format(date)}T${compactTime(startTime)}")
            line("DTEND:${icsDate.format(date)}T${compactTime(endTime)}")
            line("SUMMARY:${escapeIcs(item.name)}")
            if (item.room.isNotEmpty()) line("LOCATION:${escapeIcs(item.room)}")
            line("DESCRIPTION:${escapeIcs(description)}")
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    /**
     * CSV：一行 = 一个课程时段（星期 + 起止节 + 周次区间），带 UTF-8 BOM 方便 Excel 识别中文。
     *
     * 注意：本方法的列名与取值**刻意固定为中文，不随界面语言变化** ——
     * 第三方课表 App（Sleepy / WakeUp 系）按中文列名做包含匹配，英文界面导出英文表头会直接导入失败。
     *
     * 列名与取值刻意对齐第三方课表 App（Sleepy / WakeUp 系）的导入解析规则：
     * - 星期写「周一…周日」或 1-7：写「星期一」时 Sleepy 的星期解析失败，会**跳过整行**导致「未能解析任何课程」
     * - 节次拆成「开始节数 / 结束节数」：只写单列「开始节 / 结束节」会被当成“节次”单列，多节连上的课被压成 1 节
     * - 周次保留原始区间串（"1-16,18"）并单列「类型」（每周/单周/双周），不再按周展开成几十行重复课程
     * - 开始/结束时间为该时段的整体起止，对方 App 用它推导作息表
     */
    private fun buildCsv(slots: List<Slot>): String {
        val sb = StringBuilder("\uFEFF")
        fun cell(value: String): String {
            val v = value.replace("\"", "\"\"")
            return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$v\"" else v
        }
        fun line(vararg cells: String) {
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        line("课程", "星期", "开始节数", "结束节数", "周次", "类型", "开始时间", "结束时间", "教师", "教室")
        for (item in slots) {
            val startTime = periodTimes.getOrNull(item.start - 1)?.first ?: continue
            val endTime = periodTimes.getOrNull(item.end - 1)?.second ?: continue
            line(
                item.name,
                weekDayNames[item.day - 1],
                "${item.start}",
                "${item.end}",
                item.weekRange,
                oddEvenLabel(item.oddEven),
                startTime,
                endTime,
                item.teacher,
                item.room
            )
        }
        return sb.toString()
    }

    /** 只保留数字与区间分隔符，"第6-15周" → "6-15" */
    private fun normalizeWeekRange(raw: String): String {
        val cleaned = raw
            .replace('—', '-')
            .replace('–', '-')
            .replace('－', '-')
            .replace('~', '-')
            .replace('，', ',')
            .replace('、', ',')
            .replace('；', ';')
        return cleaned.filter { it.isDigit() || it == '-' || it == ',' || it == ';' }
            .trim(',', ';', '-')
    }

    private fun oddEvenLabel(raw: String): String = when (raw) {
        "1" -> "单周"
        "2" -> "双周"
        else -> "每周"
    }

    private fun buildText(
        context: Context,
        data: ScheduleData,
        occurrences: List<Occurrence>,
        plan: Plan,
        currentMonday: LocalDate
    ): String {
        val lines = mutableListOf<String>()
        lines.add(context.getString(R.string.schedule_export_file_prefix))
        data.termId?.trim()?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        lines.add(
            context.getString(
                R.string.schedule_export_summary,
                plan.weeks.first,
                plan.weeks.last,
                occurrences.map { it.name }.distinct().size
            )
        )
        val displayDayNames = context.resources.getStringArray(R.array.schedule_day_names)
        for (week in plan.weeks) {
            val weekOccurrences = occurrences.filter { it.week == week }
            if (weekOccurrences.isEmpty()) continue
            val monday = currentMonday.plusWeeks((week - plan.currentWeek).toLong())
            lines.add("")
            lines.add(
                context.getString(
                    R.string.schedule_export_week_header,
                    week,
                    displayDate.format(monday),
                    displayDate.format(monday.plusDays(6))
                )
            )
            for (day in 1..7) {
                val dayOccurrences = weekOccurrences.filter { it.day == day }
                if (dayOccurrences.isEmpty()) continue
                lines.add(displayDayNames[day - 1])
                for (item in dayOccurrences) {
                    val period = if (item.start == item.end) {
                        context.getString(R.string.schedule_export_period_single, item.start)
                    } else {
                        context.getString(R.string.schedule_export_period_range, item.start, item.end)
                    }
                    lines.add("  $period ${item.name}")
                    val meta = listOfNotNull(
                        item.teacher.takeIf { it.isNotEmpty() },
                        item.room.takeIf { it.isNotEmpty() }
                    )
                    if (meta.isNotEmpty()) lines.add("    ${meta.joinToString(" · ")}")
                }
            }
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun compactTime(value: String): String {
        val time = value.replace(":", "").trim()
        return if (time.length == 4) "${time}00" else time
    }

    private fun escapeIcs(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
    }
}
