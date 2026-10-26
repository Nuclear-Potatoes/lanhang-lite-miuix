package com.lanhang.miuix.grades

import com.google.gson.annotations.SerializedName

// GET /api/common?method=GetXnxqList → {"code":0,"msg":"ok","data":[...]}
data class XnxqListResponse(
    val code: Int? = null,
    val msg: String? = null,
    val data: List<GradeSemester>? = null
)

data class GradeSemester(
    @SerializedName("学期ID") val id: String? = null,
    @SerializedName("学期名称") val name: String? = null,
    @SerializedName("当前学期") val current: String? = null
) {
    val isCurrent: Boolean get() = current == "1"
}

// GET /api/common?method=GetStudentScore&xnxqId=... → {"code":0,"msg":"ok","data":[...]}
data class ScoreResponse(
    val code: Int? = null,
    val msg: String? = null,
    val data: List<GradeItem>? = null
)

data class GradeItem(
    @SerializedName("课程名称") val courseName: String? = null,
    @SerializedName("学分") val credit: String? = null,
    @SerializedName("总成绩") val score: String? = null,
    @SerializedName("课程性质") val nature: String? = null,
    @SerializedName("课程属性") val attribute: String? = null,
    @SerializedName("考试方式") val examMode: String? = null,
    @SerializedName("总学时") val hours: String? = null,
    @SerializedName("课程号") val courseCode: String? = null,
    @SerializedName("学年学期ID") val termId: String? = null
) {
    val creditValue: Double? get() = credit?.toDoubleOrNull()
    val scoreValue: Int? get() = score?.toIntOrNull()
}
