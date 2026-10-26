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
    val periodEnd: Int
)
