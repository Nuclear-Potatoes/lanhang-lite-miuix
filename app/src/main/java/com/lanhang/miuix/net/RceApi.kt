package com.lanhang.miuix.net

import android.os.Build
import com.google.gson.reflect.TypeToken
import com.lanhang.miuix.R
import com.lanhang.miuix.schedule.AppContext
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, message: String) : IOException(message)

object RceApi {
    private val JSON = "application/json;charset=utf-8".toMediaType()

    /** POST /apps/all 的固定入参：0=停用 1=启用 */
    private const val WORK_APPS_BODY = "{\"state\":[0,1]}"

    private inline fun <reified T> get(path: String, withCookie: Boolean = true): T {
        val builder = Request.Builder().url(ApiClient.BASE_URL + ApiClient.API_PREFIX + path).get()
        if (withCookie) {
            ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        }
        return execute(builder.build())
    }

    private inline fun <reified T> post(path: String, body: String, withCookie: Boolean = true): T {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + path)
            .post(body.toRequestBody(JSON))
        if (withCookie) {
            ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        }
        return execute(builder.build())
    }

    private inline fun <reified T> execute(request: Request): T {
        ApiClient.http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val type = object : TypeToken<ApiResponse<T>>() {}.type
            val parsed: ApiResponse<T> = try {
                ApiClient.gson.fromJson(text, type)
            } catch (e: Exception) {
                throw ApiException(
                    -1,
                    AppContext.instance.getString(R.string.error_payload_invalid, text.take(200))
                )
            }
            if (!parsed.isSuccess) {
                throw ApiException(
                    parsed.code ?: -1,
                    parsed.msg ?: AppContext.instance.getString(R.string.error_request_failed)
                )
            }
            @Suppress("UNCHECKED_CAST")
            return parsed.result as T
        }
    }

    fun login(username: String, password: String, deviceId: String): LoginInfo {
        val body = mapOf(
            "username" to username,
            "password" to Security.encryptPassword(password),
            "agent" to mapOf("platform" to "Android", "device_id" to deviceId),
            "status" to "online",
            "device_info" to mapOf(
                "app_version" to "0.1.0",
                "app_version_code" to 1,
                "app_language" to "zh-CN",
                "os_version" to Build.VERSION.RELEASE,
                "device_name" to Build.MANUFACTURER,
                "device_model" to Build.MODEL,
                "network" to "WIFI",
                "timezone" to java.util.TimeZone.getDefault().id,
                "operator" to "",
                "imei" to deviceId,
                "package_name" to "com.lanhang.miuix"
            )
        )
        return post("/user/security_login", ApiClient.gson.toJson(body), withCookie = false)
    }

    fun configurationAll(): ConfigInfo =
        get("/configuration/all", withCookie = false)

    /**
     * 融云 IM 的 token（官方 AuthTask.refreshToken → `POST /user/refresh_token`，
     * 返回 `{"code":10000,"result":{"token":"..."}}`）。私信连接时才调，平时不碰。
     */
    fun refreshImToken(): String? = post<RefreshTokenInfo>("/user/refresh_token", "{}").token

    fun getImageUploadToken(): MediaUploadToken =
        get("/user/get_image_token")

    // 这两个接口返回的是 {"code":10000,"result":"字符串"}，不是嵌套对象
    fun getLdapToken(): String? {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + "/apps/getLdapToken")
            .get()
        ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        return executeTokenString(builder.build())
    }

    /**
     * POST /apps/all → 办事大厅的应用目录。
     *
     * 官方 `OAFragment` / `OASearchActivity` 的入参就是 `{"state":[0,1]}`（0=停用 1=启用），
     * 服务端按当前账号的身份返回可见应用，所以这里不做本地权限判断。
     */
    fun appsAll(): WorkDirectory =
        post("/apps/all", WORK_APPS_BODY)

    fun getAppToken(appId: String): String? {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + "/apps/getAppToken/$appId")
            .get()
        ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        return executeTokenString(builder.build())
    }

    private fun executeTokenString(request: Request): String? {
        ApiClient.http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("RceApi", "token resp code=${resp.code} url=${request.url} body=${text.take(300)}")
            val parsed: RawTokenResponse = try {
                ApiClient.gson.fromJson(text, RawTokenResponse::class.java)
            } catch (e: Exception) {
                throw ApiException(
                    -1,
                    AppContext.instance.getString(R.string.error_payload_invalid, text.take(200))
                )
            }
            if (parsed.code != 10000) {
                throw ApiException(
                    parsed.code ?: -1,
                    AppContext.instance.getString(R.string.error_request_failed)
                )
            }
            return parsed.result
        }
    }

    /**
     * GET /groups/{id} → 群资料（群名 / 成员数 / 群头像）。
     *
     * 官方 `GroupTask.GROUP_INFO = "/groups/%s"`，成功码同样是 `code=10000`（`RceErrorCode.SUCCESS`），
     * 所以直接复用本类的 [get]。私信页的群名就用它——融云 IMLib 2.9 没有 `getGroupsInfo`，
     * 群名在客户端只能靠学校服务端拿（详见 [ImGroupInfo] 的注释）。
     */
    fun groupInfo(groupId: String): ImGroupInfo? = get("/groups/$groupId")

    /**
     * GET /apps/getUserQRCode → result 直接是一段字符串 token。
     * 官方 MyQrCodeActivity 拿到它就地画成二维码（不落盘、不缓存），刷新时间只更新本地时间戳。
     */
    fun getUserQrCode(): String? {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + "/apps/getUserQRCode")
            .get()
        ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        return executeTokenString(builder.build())
    }

    /**
     * GET /staffs/{id} → 完整资料（姓名 / 学号 / 邮箱 / 头像 / depart_name / extra）。
     * 官方 UserTask.STAFF_INFO = "/staffs/%s"，他人主页与身份码都用它取数。
     */
    fun getStaff(userId: String): StaffInfo? = get("/staffs/$userId")

    fun refreshUserByDel(userId: String): Any? =
        get<Any?>("/user/refreshUserByDel/$userId", withCookie = false)

    fun staffsBatch(ids: List<String>): StaffBatchResult? =
        post("/staffs/batch", ApiClient.gson.toJson(mapOf("ids" to ids)))

    /**
     * 通讯录：整棵组织架构树。
     * 官方 `OrganizationTask.DEPARTMENTS_TREE`，一次下发 1500+ 节点，客户端自己按 parent_id 拼树。
     */

    /**
     * `POST /staffs/search`：按学号 / 姓名找人（通讯录搜索用）。
     *
     * 请求体照抄官方 `FriendTask.searchFriendsWithStaffNo`（`type=1` 就是「按工号/学号」），
     * 只是我们不带组织信息（`with_orgs=0`，省得响应膨胀）。失败返回空表——搜索框不该因为
     * 一次网络抖动就弹错误提示。
     */
    suspend fun searchStaff(keyword: String): List<StaffSearchHit> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        val body = com.google.gson.JsonObject().apply {
            add("keywords", com.google.gson.JsonArray().apply { add(q) })
            addProperty("with_orgs", "0")
            addProperty("type", 1)
        }
        return runCatching {
            post<List<StaffSearchHit>>("/staffs/search", ApiClient.gson.toJson(body))
        }.getOrDefault(emptyList())
    }

    /**
     * `POST /user/search/mobile`：按手机号找人（官方 `FriendTask.searchFriendsWithMobile`）。
     * 请求体只有 `{"keywords":[...]}`，没有 type。
     */
    suspend fun searchStaffByMobile(mobile: String): List<StaffSearchHit> {
        val q = mobile.trim()
        if (q.isEmpty()) return emptyList()
        val body = com.google.gson.JsonObject().apply {
            add("keywords", com.google.gson.JsonArray().apply { add(q) })
        }
        return runCatching {
            post<List<StaffSearchHit>>("/user/search/mobile", ApiClient.gson.toJson(body))
        }.getOrDefault(emptyList())
    }

    /**
     * 通讯录搜索的统一入口：姓名 / 学号 / 手机号都能查。
     *
     * - `/staffs/search`（`type=1`）实测同时命中**姓名**与**学号 / 工号**；
     * - 关键词是 11 位手机号时，再补一次 `/user/search/mobile`——那个接口只认手机号，
     *   两个结果按 id 去重、顺序保持「先精确人、后补充」。
     */
    suspend fun searchContacts(keyword: String): List<StaffSearchHit> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        val byKeyword = searchStaff(q)
        val digits = q.filter { it.isDigit() }
        val merged = if (digits.length == 11 && digits == q) {
            (byKeyword + searchStaffByMobile(q)).distinctBy { it.id }
        } else {
            byKeyword
        }
        if (merged.isEmpty()) return merged
        // `/user/search/mobile` 只回 id/name/mobile，没有学号；缺学号的一批统一补一次
        // `/staffs/batch`（一次请求搞定，别一个人一个人去查）。
        val needNo = merged.filter { it.staffNo.isBlank() && it.id.isNotBlank() }.map { it.id }
        if (needNo.isEmpty()) return merged
        val briefs = runCatching { staffsBatch(needNo) }.getOrNull()?.data
            ?.mapNotNull { b -> b.staffNo?.takeIf { it.isNotBlank() }?.let { b.id to it } }
            ?.toMap()
            .orEmpty()
        if (briefs.isEmpty()) return merged
        return merged.map { hit ->
            if (hit.staffNo.isBlank()) hit.copy(staffNo = briefs[hit.id] ?: hit.staffNo) else hit
        }
    }

    fun departmentTree(): List<Department> =
        get<DepartmentTree>("/departments/tree").data

    /**
     * 某个部门的下一级：子部门 + 成员（`type` 1 = 部门，其余 = 人员）。
     * 官方 `OrganizationTask.departmentsBranches(id, offset, limit)`；根层走 `/departments/root`。
     */
    fun departmentBranches(deptId: String?, offset: Int, limit: Int): DepartmentBranches =
        if (deptId.isNullOrBlank()) {
            get<DepartmentBranches>("/departments/root?offset=$offset&limit=$limit")
        } else {
            get<DepartmentBranches>("/departments/$deptId/branches?offset=$offset&limit=$limit")
        }
}
