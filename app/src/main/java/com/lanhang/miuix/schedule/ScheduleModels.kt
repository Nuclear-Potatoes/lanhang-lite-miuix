package com.lanhang.miuix.schedule

import com.google.gson.annotations.SerializedName

/** GetSchedule 接口返回的课表数据 */
data class ScheduleResponse(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("data") val data: ScheduleData? = null
)

data class ScheduleData(
    @SerializedName("xnxqId") val termId: String? = null,
    @SerializedName("week") val weekInfo: WeekInfo? = null,
    @SerializedName("schedule") val schedule: List<DaySchedule>? = null
)

data class WeekInfo(
    @SerializedName("当前周次") val currentWeek: String? = null,
    @SerializedName("起始周次") val startWeek: String? = null,
    @SerializedName("结束周次") val endWeek: String? = null
)

data class DaySchedule(
    @SerializedName("星期") val dayName: String? = null,
    @SerializedName("节次") val periods: List<Period>? = null
)

data class Period(
    @SerializedName("节次名") val periodName: String? = null,
    @SerializedName("课程") val courses: List<Course>? = null
)

data class Course(
    @SerializedName("课程名") val name: String? = null,
    @SerializedName("教师") val teacher: String? = null,
    @SerializedName("教室") val room: String? = null,
    @SerializedName("周次") val weekRange: String? = null,
    @SerializedName("单双周") val oddEven: String? = null
) {
    /** 解析周次范围如 "6-15" 或 "3"，返回是否在指定周上课 */
    fun isWeekValid(week: Int): Boolean {
        val range = weekRange ?: return true
        return try {
            if (range.contains("-")) {
                val parts = range.split("-")
                val start = parts[0].trim().toInt()
                val end = parts[1].trim().toInt()
                week in start..end
            } else {
                week == range.trim().toInt()
            }
        } catch (e: Exception) {
            true
        }
    }

    /** 单双周：0=每周，1=单周，2=双周 */
    fun isOddEvenValid(week: Int): Boolean {
        return when (oddEven) {
            "1" -> week % 2 == 1  // 单周
            "2" -> week % 2 == 0  // 双周
            else -> true          // 每周
        }
    }
}
