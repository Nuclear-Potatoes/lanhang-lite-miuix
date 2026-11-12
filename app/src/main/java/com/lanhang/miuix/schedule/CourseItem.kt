package com.lanhang.miuix.schedule

/**
 * 课表上一节课的展示项：课程本体 + 节次区间。
 *
 * 同一门课连上多节时，`ScheduleFragment.mergeCourses()` 会把它合并成一条，
 * 此时 `periodStart != periodEnd`，界面显示成「第 1-2 节」。
 */
data class CourseItem(
    val course: Course,
    val periodStart: Int,
    val periodEnd: Int,
    /**
     * 手动课开了「自定义时间」时的实际起止（当天第几分钟，`-1` = 没开）。
     *
     * 2026-10-30 用户第 4 条：「上课节次与自定义时间只能存在一个，打开自定义时间后
     * 按课上时间与课表时间的比例设置课程在课表中所占大小」。
     * 所以渲染层不能只看节次 —— 有这两个值时，块的位置与高度按分钟在时间轴上插值。
     */
    val startMinute: Int = -1,
    val endMinute: Int = -1,
    /**
     * 用户手动添加的课的 id（`ManualCourse.id`）；教务系统的课为 `null`。
     *
     * 2026-11-01 第五十七轮（用户第 2 条）：「点击用户自己手动添加的课程时，
     * 对话框应多一个编辑按钮」。详情弹窗靠这个字段判断该不该出「编辑」。
     */
    val manualId: String? = null,
) {
    /** 是否按「自定义时间」定位（分钟级），而不是按节次对齐 */
    val byClock: Boolean get() = startMinute in 0..1439 && endMinute in 0..1439 && endMinute > startMinute
}