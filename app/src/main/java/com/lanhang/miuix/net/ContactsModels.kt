package com.lanhang.miuix.net

import com.google.gson.annotations.SerializedName

/**
 * 通讯录（组织架构）模型。
 *
 * 接口来自官方客户端 `cn.rongcloud.rce.lib.OrganizationTask`：
 * - `GET /departments/tree` → 整棵组织树（1557 个节点，一次性下发，`parent_id` 为空的是根）
 * - `GET /departments/{id}/branches?offset=&limit=` → 某个部门的子部门 + 成员（`type` 1=部门，其余=人员）
 *
 * 字段名全部是服务端的下划线命名，用 [SerializedName] 映射成驼峰，跟工作圈那套模型保持一致。
 */

/** 组织树节点 */
data class Department(
    val id: String = "",
    @SerializedName("parent_id") val parentId: String = "",
    val name: String = "",
    @SerializedName("full_name") val fullName: String = "",
    @SerializedName("member_count") val memberCount: Int = 0,
    @SerializedName("manager_id") val managerId: String = "",
)

/** `GET /departments/tree` 的 result */
data class DepartmentTree(
    @SerializedName("total_count") val totalCount: Int = 0,
    val data: List<Department> = emptyList(),
)

/** 部门下的一个条目：子部门（type=1）或成员 */
data class OrgEntry(
    val id: String = "",
    @SerializedName("parent_id") val parentId: String = "",
    val type: Int = 0,
    val name: String = "",
    @SerializedName("portrait_url") val portraitUrl: String = "",
    val mobile: String = "",
    @SerializedName("duty_name") val dutyName: String = "",
    @SerializedName("member_count") val memberCount: Int = 0,
    val order: Int = 0,
) {
    val isDepartment: Boolean get() = type == 1
}

/**
 * `POST /staffs/search` 命中的一个人。
 *
 * 官方客户端是 `cn.rongcloud.rce.lib.FriendTask#searchFriendsWithStaffNo`：
 * 入参 `{"keywords":[...],"with_orgs":"0","type":1}`，服务端按**学号 / 工号**匹配，
 * 实测（2026-10-27）按姓名也能命中同一个人。
 */
data class StaffSearchHit(
    val id: String = "",
    val name: String = "",
    @SerializedName("portrait_url") val portraitUrl: String = "",
    @SerializedName("staff_no") val staffNo: String = "",
    val email: String = "",
    @SerializedName("user_type") val userType: Int = 0,
    @SerializedName("duty_name") val dutyName: String = "",
    /**
     * 手机号。
     *
     * `/staffs/search` 不带这个字段，只有 `/user/search/mobile` 会回——
     * 所以「按手机号搜到的人」这里才有值（第二十轮第 1、2 条：学号 / 手机号都要能搜、都要显示）。
     */
    val mobile: String = "",
) {
    /** 去掉 `@stu.nchu.edu.cn` 这类后缀的学号 / 工号 */
    val studentNo: String? get() = staffNo.substringBefore('@').takeIf { it.isNotBlank() }
}

/** `GET /departments/{id}/branches` 的 result（带分页信息） */
data class DepartmentBranches(
    @SerializedName("total_count") val totalCount: Int = 0,
    val count: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: List<OrgEntry> = emptyList(),
)

/** 工作圈「消息」里的一条互动记录（`GET /message/history`） */
data class InteractionMessage(
    @SerializedName("message_id") val messageId: String = "",
    @SerializedName("feed_id") val feedId: String = "",
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("comment_content") val commentContent: String? = null,
    @SerializedName("reply_to") val replyTo: String? = null,
    @SerializedName("create_dt") val createDt: Long = 0,
    /** 1 = 评论，2 = 赞（官方 HistoryMsgType） */
    val type: Int = 0,
    val status: Int = 0,
) {
    val isComment: Boolean get() = type == 1
}

/** `GET /message/unread_count` */
data class UnreadCount(@SerializedName("count") val count: Int = 0)
