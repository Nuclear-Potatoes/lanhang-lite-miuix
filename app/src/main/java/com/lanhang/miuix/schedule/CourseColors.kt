package com.lanhang.miuix.schedule

/**
 * 课程配色表：**全学期每门课一个色相，互不重复**，课表与小部件共用同一张表。
 *
 * 2026-10-27 第二十八轮（用户原话：
 * 「扩展课表中课程选色的多样性，保证可读性的同时也要保证不要有课程的颜色出现重复的情况，
 * 并且要保证课程颜色与小部件中课程的颜色同步，比如我的高数B和航空航天概论就出现了颜色重复的情况」）。
 *
 * 老做法有两处毛病：
 *  1. 课表用固定色板 + 「离已用色最远」贪心分配（`ScheduleFragment.COURSE_PALETTE`），
 *     课程一多色板就用完，`maxByOrNull` 只能从已经用掉的色里再挑一个 —— 于是高数B1 和
 *     航空航天概论都是那档绿；
 *  2. 小组件另起一套 `hashCode * 137.508` 的色相算法，和课表**根本不是一个色**。
 *
 * 现在统一到这里：把本学期所有课程名排序后**均分 360° 色相**（n 门课 → 间隔 360/n°），
 * 于是
 *  - 不同课一定不同色（n ≤ 360 时严格不重复，实际一学期十几门，间隔都在 20° 以上）；
 *  - 同课一定同色（纯函数，跟出现顺序、周次、是否只画某一天无关）；
 *  - 课表和小部件取的都是 `hueOf(name)`，同一个色相，再各自按亮/暗主题派生染底与文字色。
 *
 * 明度/饱和度不在这里定：课表走 [ScheduleStyle.courseFill] 的染色 + 深字，
 * 小组件走 `ScheduleWidgetRenderer` 的 tint/ink（对比度 ≥ 4.5），各自负责可读性。
 */
object CourseColors {

    /** 色相均分用的起点，稍微偏一点避开「正红」在浅色主题下过于跳的问题 */
    private const val HUE_OFFSET = 8f

    /** 课程名 → 色相（0..360）。不在本学期表里的名字退化为 hash 色相（正常不会发生）。 */
    @Volatile private var table: Map<String, Float> = emptyMap()

    fun hueOf(courseName: String): Float {
        val key = courseName.trim()
        table[key]?.let { return it }
        ensureTable()
        table[key]?.let { return it }
        return fallback(key)
    }

    /** 课表刷新/换学期后调用，下次取色重新生成（进程内缓存即可） */
    fun invalidate() {
        table = emptyMap()
    }

    private fun ensureTable() {
        if (table.isNotEmpty()) return
        val names = runCatching { ScheduleRepository.allCachedCourseNames() }.getOrDefault(emptyList())
        table = build(names)
    }

    private fun build(names: List<String>): Map<String, Float> {
        if (names.isEmpty()) return emptyMap()
        val step = 360f / names.size
        val map = LinkedHashMap<String, Float>(names.size * 2)
        names.forEachIndexed { index, name ->
            map[name] = ((HUE_OFFSET + step * index) % 360f + 360f) % 360f
        }
        return map
    }

    /** 兜底：老算法（hash × 黄金角），只在拿不到学期课程表时用 */
    private fun fallback(name: String): Float =
        (((name.hashCode() * 137.508f) % 360f) + 360f) % 360f
}
