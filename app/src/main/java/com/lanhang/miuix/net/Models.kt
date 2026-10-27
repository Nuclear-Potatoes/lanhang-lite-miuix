package com.lanhang.miuix.net

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    val code: Int? = null,
    val msg: String? = null,
    val result: T? = null
) {
    val isSuccess: Boolean get() = code == 10000
}

// 官方 LoginInfo：staff 字段带 @SerializedName("staff")
data class LoginInfo(
    val token: String? = null,
    @SerializedName("staff") val staffInfo: StaffInfo? = null,
    val user_type: Int? = null
)

// 官方 StaffInfo.getUserId() 返回的是 id 字段。
// /staffs/{id} 比登录返回的那份多出学号、邮箱、头像与 extra（extra 是段 JSON 字符串，
// 里面 org / userOrgencode 才是「我的」「身份码」要显示的那一行组织名）。
data class StaffInfo(
    val id: String? = null,
    val name: String? = null,
    val code: String? = null,
    val mobile: String? = null,
    val company_id: String? = null,
    val depart_id: String? = null,
    val depart_name: String? = null,
    @SerializedName("staff_no") val staffNo: String? = null,
    val email: String? = null,
    val tel: String? = null,
    @SerializedName("portrait_url") val portraitUrl: String? = null,
    @SerializedName("portrait_big_url") val portraitBigUrl: String? = null,
    @SerializedName("user_type") val userType: Int? = null,
    val extra: String? = null
) {
    val userId: String? get() = id

    /** extra 里的组织名：官方 MeFragment 优先取 org，取不到再退 userOrgencode */
    val org: String? get() {
        val raw = extra?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val o = com.google.gson.JsonParser.parseString(raw).asJsonObject
            fun pick(key: String) = o.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            pick("org") ?: pick("userOrgencode")
        } catch (_: Exception) {
            null
        }
    }

    /** 手机号打码（官方在他人主页就是这么显示的）：138****8000 */
    val maskedMobile: String? get() {
        val m = mobile?.filter { it.isDigit() } ?: return null
        if (m.length < 7) return null
        return m.substring(0, 3) + "****" + m.substring(m.length - 4)
    }
}

data class RawTokenResponse(val code: Int? = null, val result: String? = null)

// POST /staffs/batch 批量解析；官方 StaffInfo.getUserId() 返回的是 id 字段
/**
 * 组织名清洗。
 *
 * 服务端 `depart_name` / `extra.org` 有两种坑：
 *  - `机电学院-2026-2603071` 这种「学院 + 年级 + 组织编码」拼在一起的；
 *  - 纯数字组织编码（`2601063`），根本不是给人看的。
 * 统一规则：按分隔符切开，丢掉纯数字段，剩下的拼回去；一段不剩就返回 null（不显示）。
 */
fun String?.cleanOrgName(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val kept = raw.split('-', '－', '—', '_', ' ', '/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { part -> part.all { it.isDigit() } }
    return kept.joinToString("-").ifBlank { null }
}

data class StaffBrief(
    val id: String? = null,
    val name: String? = null,
    val portrait_url: String? = null,
    val depart_name: String? = null,
    val duty_name: String? = null,
    val company_id: String? = null,
    /**
     * 学号 / 工号（服务端 `staff_no`）。
     *
     * 第二十轮（2026-10-27）用户要求通讯录里显示学号：`/staffs/batch` 会把它带回来。
     * 学生是 `260106336@stu.nchu.edu.cn` 这种形式，个别接口给纯学号，见 [studentNo]。
     */
    @SerializedName("staff_no") val staffNo: String? = null
) {
    /** 界面上的学号：去掉 `@stu.nchu.edu.cn` 这种后缀，取不到就 null（界面自动省掉这一行） */
    val studentNo: String? get() = staffNo?.substringBefore('@')?.takeIf { it.isNotBlank() }
}

data class StaffBatchResult(val data: List<StaffBrief>? = null, val timestamp: Long? = null)

data class ConfigInfo(val features: Features? = null) {
    data class Features(
        val security: Security? = null,
        val work: Work? = null,
        @SerializedName("media") val mediaServer: MediaServerInfo? = null,
        /** 融云 IM 的下发配置（官方 FeatureConfigManager.getRongIMInfo → /configuration/all） */
        val im: ImInfo? = null
    )
    data class Security(val password_public_key_rsa: String? = null)
    data class Work(val enable: Boolean? = null, val url: String? = null)

    /** 官方 RongIMInfo：app_key + 导航/媒体服务器（IMTask 会拿来做 RongIM.setServerInfo） */
    data class ImInfo(
        @SerializedName("app_key") val appKey: String? = null,
        @SerializedName("navi_urls") val naviUrls: List<String>? = null,
        @SerializedName("media_urls") val mediaUrls: List<String>? = null
    )
}

/** POST /user/refresh_token 的返回（官方 RefreshTokenInfo） */
data class RefreshTokenInfo(val token: String? = null)

data class MediaServerInfo(
    val type: Int? = null,
    @SerializedName("max_file_size") val maxFileSize: Long? = null,
    @SerializedName("upload_urls") val uploadUrls: List<String>? = null,
    @SerializedName("download_urls") val downloadUrls: List<String>? = null
)

/**
 * GET /groups/{id} → 学校 RCE 的群资料（官方 `GroupTask.getGroupInfoFromServer`，
 * 路径常量 `GROUP_INFO = "/groups/%s"`）。
 *
 * 只声明本客户端用得到的字段。**群名的权威值只有这个接口有**：融云 IMLib 2.9 的客户端 API
 * 没有 `getGroupsInfo`（`RongIMClient` 上只有 `joinGroup / quitGroup / syncGroup`），
 * SDK 本地库里的群名是谁写进去就是什么，所以群名必须问学校服务端，
 * 不能在客户端按群 id 拼占位名（那会把真名覆盖掉，2026-10-26 用户反馈的「群名不对」就是它）。
 */
data class ImGroupInfo(
    val id: String? = null,
    val name: String? = null,
    /** 成员数（群资料卡用得到，先留着） */
    val memberCnt: Int? = null,
    val portraitUrl: String? = null
)

data class MediaUploadToken(
    val token: String? = null,
    val key: String? = null,
    val server: String? = null,
    val domain: String? = null,
    val target: String? = null
)
