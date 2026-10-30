package com.lanhang.miuix.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 手动课程可选的最大周次（整学期 1~20）；添加页的周次网格也用它 */
internal const val MAX_WEEK = 20

/**
 * 用户手动添加的一节课（2026-10-30 用户第 3 条）。
 *
 * 为什么要单独一份数据：服务端课表是「学校教务系统说什么就是什么」，
 * 但学生自己的补习、社团、实验预习、临时调课这些排不进官方课表。
 * 这里在本机存一份，渲染时**混进同一份 [CourseItem] 列表**（见
 * `ScheduleFragment#getCoursesForDay`），于是网格、列表视图、导出、组件全部自动包含，
 * 不需要在每一处再写一遍「还要不要看手动课」。
 *
 * 2026-10-30 追加（用户：「参照这个添加课程的页面做你的ui」）：周次从「起始~结束区间」
 * 改成**离散集合**，对齐参考图那种 1~20 逐周多选的网格 —— 学生真实课表里
 * 「第 1-8 周 + 第 10-16 周」这种断档很常见，区间表达不了。
 */
data class ManualCourse(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    /** 0 = 周一 … 6 = 周日（与课表 `dayCourses` 的下标一致） */
    val dayOfWeek: Int = 0,
    val periodStart: Int = 1,
    val periodEnd: Int = 1,
    /** 上课周次（升序去重）；默认整学期全选 */
    val weeks: List<Int> = (1..MAX_WEEK).toList(),
    /** `""` 每周 / `"1"` 单周 / `"2"` 双周 —— 只用于导出与详情展示，判定一律看 [weeks] */
    val oddEven: String = "",
) {
    /** 这一周上不上 */
    fun inWeek(week: Int): Boolean = week in weeks

    /** 转成课表渲染用的 [CourseItem]；`weekRange` 带上是为了导出/详情也能自洽 */
    fun toCourseItem(): CourseItem = CourseItem(
        course = Course(
            name = name,
            teacher = teacher.ifBlank { null },
            room = room.ifBlank { null },
            weekRange = compressWeeks(weeks),
            oddEven = oddEven.ifBlank { null },
        ),
        periodStart = periodStart.coerceAtLeast(1),
        periodEnd = periodEnd.coerceAtLeast(periodStart.coerceAtLeast(1)),
    )
}

/**
 * 把离散周次压成教务系统的区间写法：`1-20`、`1-8,10-16`、`1,3,5`。
 *
 * 导出到 .ics / 文本、以及课程详情弹窗里显示的「周次」都走它，
 * 与教务系统的写法保持一致，别让用户看到 `[1, 2, 3, 4]` 这种调试格式。
 */
internal fun compressWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.distinct().sorted()
    val sb = StringBuilder()
    var start = sorted.first()
    var prev = start
    fun flush(end: Int) {
        if (sb.isNotEmpty()) sb.append(',')
        if (start == end) sb.append(start) else sb.append(start).append('-').append(end)
    }
    for (i in 1 until sorted.size) {
        val w = sorted[i]
        if (w == prev + 1) {
            prev = w
            continue
        }
        flush(prev)
        start = w
        prev = w
    }
    flush(prev)
    return sb.toString()
}

/**
 * 手动课程的本机存储（SharedPreferences + JSON）。
 *
 * 只存本机、不上传、不参与任何接口 —— 这是用户自己加的数据，
 * 换设备/清数据就没了，符合「本地课程」的语义。
 */
object ManualCourseStore {

    private const val PREF = "schedule_manual_courses"
    private const val KEY = "items"

    /** 内存缓存：课表每次重绘都会问一遍，别每次都解析 JSON */
    private var cache: MutableList<ManualCourse>? = null

    private fun prefs() = AppContext.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load(): MutableList<ManualCourse> {
        cache?.let { return it }
        val list = mutableListOf<ManualCourse>()
        val raw = prefs().getString(KEY, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        ManualCourse(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = o.optString("name"),
                            teacher = o.optString("teacher"),
                            room = o.optString("room"),
                            dayOfWeek = o.optInt("day", 0),
                            periodStart = o.optInt("ps", 1),
                            periodEnd = o.optInt("pe", 1),
                            weeks = readWeeks(o),
                            oddEven = o.optString("oe"),
                        )
                    )
                }
            }
        }
        cache = list
        return list
    }

    /** 新版读 `wk`（逗号分隔）；旧版是 `ws`/`we` 区间，顺手兼容一下 */
    private fun readWeeks(o: JSONObject): List<Int> {
        val raw = o.optString("wk")
        if (raw.isNotBlank()) {
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..MAX_WEEK }.distinct().sorted()
        }
        val ws = o.optInt("ws", 1)
        val we = o.optInt("we", MAX_WEEK)
        return (ws..we).filter { it in 1..MAX_WEEK }
    }

    private fun persist(list: List<ManualCourse>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("room", c.room)
                    put("day", c.dayOfWeek)
                    put("ps", c.periodStart)
                    put("pe", c.periodEnd)
                    put("wk", c.weeks.joinToString(","))
                    put("oe", c.oddEven)
                }
            )
        }
        prefs().edit().putString(KEY, arr.toString()).apply()
        cache = list.toMutableList()
    }

    fun all(): List<ManualCourse> = load().toList()

    /** 已经用掉的手动课名 —— 配色表要拿它一起参与「均分色相」，否则手动课会跟教务课撞色 */
    fun usedNames(): List<String> =
        load().map { it.name.trim() }.filter { it.isNotEmpty() }

    /** 新增或按 id 覆盖 */
    fun upsert(course: ManualCourse) {
        val list = load()
        val idx = list.indexOfFirst { it.id == course.id }
        if (idx >= 0) list[idx] = course else list.add(course)
        persist(list)
    }

    fun remove(id: String) {
        val list = load()
        list.removeAll { it.id == id }
        persist(list)
    }

    /** 某天某周能上的手动课，已经转成 [CourseItem] */
    fun forDay(dayIndex: Int, week: Int): List<CourseItem> =
        load()
            .filter { it.dayOfWeek == dayIndex && it.name.isNotBlank() && it.inWeek(week) }
            .map { it.toCourseItem() }
}
