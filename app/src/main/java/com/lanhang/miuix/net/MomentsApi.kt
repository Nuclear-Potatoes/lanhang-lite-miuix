package com.lanhang.miuix.net

import android.content.Context
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.lanhang.miuix.R
import com.lanhang.miuix.moments.AuthResult
import com.lanhang.miuix.moments.CommentEntry
import com.lanhang.miuix.moments.FeedIdRepo
import com.lanhang.miuix.moments.MomentFeed
import com.lanhang.miuix.moments.TimeLineResult
import com.lanhang.miuix.session.Session
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 融云圈（工作圈）API。协议依据官方 App 反编译：
 * - base: https://nginx-1.app.nchu.edu.cn/moments/api（同域）
 * - POST /user/auth {"cookie":"<登录cookie值>","user_id":"<融云userId>"} → result.authorization
 * - 后续请求带 authorization 头；code 20003(AUTH_EXPIRED) 自动重登重试一次
 * - 成功码 20000
 */
object MomentsApi {
    private const val TAG = "MomentsApi"
    const val SUCCESS = 20000
    const val AUTH_EXPIRED = 20003
    private const val BASE = ApiClient.BASE_URL + "/moments/api"
    const val PAGE_SIZE = 10

    private val JSON = "application/json;charset=utf-8".toMediaType()

    /** 网络层错误文案统一走资源（App 启动时 [init] 已注入 application context） */
    private fun text(id: Int): String = appContext.getString(id)

    private var authorization: String? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun ensureAuth() {
        if (!authorization.isNullOrEmpty()) return
        val cookie = ApiClient.firstSessionCookieValue()
            ?: throw ApiException(-2, text(R.string.error_cookie_missing))
        if (Session.userId.isEmpty()) throw ApiException(-2, text(R.string.error_user_missing))
        // 官方 AuthReq @SerializedName("user_id")（DbHelper.COLUMN_NAME_USER_ID = "user_id"）
        val body = ApiClient.gson.toJson(mapOf("cookie" to cookie, "user_id" to Session.userId))
        val result = call("/user/auth", "POST", body, authorized = false, retry = false)
            ?: throw ApiException(-1, text(R.string.error_auth_empty))
        val auth = ApiClient.gson.fromJson(result, AuthResult::class.java)
        authorization = auth?.authorization
        if (authorization.isNullOrEmpty()) {
            throw ApiException(-1, text(R.string.error_auth_missing))
        }
    }

    fun logout() {
        authorization = null
    }

    /** core：调用并解析 {code,msg,result} 包；code!=20000 抛 ApiException；auth过期自动重试一次 */
    private fun call(
        path: String,
        method: String,
        body: String?,
        authorized: Boolean,
        retry: Boolean
    ): JsonElement? {
        if (authorized) ensureAuth()
        val builder = Request.Builder().url(BASE + path).header("Content-Type", "application/json")
        if (authorized) builder.header("authorization", authorization.orEmpty())
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> {
                if (body != null) builder.delete(body.toRequestBody(JSON)) else builder.delete()
            }
            else -> builder.post((body ?: "").toRequestBody(JSON))
        }
        ApiClient.http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            Log.d(TAG, "$method $path -> ${resp.code} ${text.take(400)}")
            saveDebug("$method $BASE$path\nreq=$body\nresp=$text")
            val obj = try {
                ApiClient.gson.fromJson(text, com.google.gson.JsonObject::class.java)
            } catch (e: Exception) {
                null
            }
            val code = obj?.get("code")?.asInt ?: -1
            if (code == AUTH_EXPIRED && authorized && retry) {
                authorization = null
                return call(path, method, body, authorized, retry = false)
            }
            if (code != SUCCESS) {
                throw ApiException(
                    code,
                    obj?.get("msg")?.asString
                        ?: appContext.getString(R.string.error_request_failed_code, resp.code)
                )
            }
            return if (obj != null && obj.has("result") && !obj.get("result").isJsonNull) {
                obj.get("result")
            } else {
                null
            }
        }
    }

    private fun saveDebug(text: String) {
        if (!::appContext.isInitialized) return
        try {
            File(appContext.filesDir, "moments_last.json").writeText(text)
        } catch (_: Exception) {
        }
    }

    fun getTimeLine(fromFeedId: String?, size: Int = PAGE_SIZE): List<String> {
        val q = "?from_feed_id=${fromFeedId.orEmpty()}&size=$size"
        val result = call("/timeline$q", "GET", null, authorized = true, retry = true)
            ?: return emptyList()
        val parsed = ApiClient.gson.fromJson(result, TimeLineResult::class.java)
        return parsed?.feed_ids ?: emptyList()
    }

    /**
     * GET /timeline/{user_id}?from_feed_id=&size= → 某个人发过的动态 id 列表。
     *
     * 官方 PrivateMomentListActivity（「我的动态」/ 他人主页下半屏）走的就是这条：
     * `RongMomentClient.getUserFeedsFromServer` → `MomentNetHandler.getUserTimeLine`
     * → `GET_USER_TIME_LINE = "/timeline/%s"`，参数与公共时间线一致。
     */
    fun getUserTimeLine(userId: String, fromFeedId: String?, size: Int = PAGE_SIZE): List<String> {
        val q = "?from_feed_id=${fromFeedId.orEmpty()}&size=$size"
        val result = call("/timeline/$userId$q", "GET", null, authorized = true, retry = true)
            ?: return emptyList()
        val parsed = ApiClient.gson.fromJson(result, TimeLineResult::class.java)
        return parsed?.feed_ids ?: emptyList()
    }

    fun getFeeds(ids: List<String>): List<MomentFeed> {
        if (ids.isEmpty()) return emptyList()
        val q = "?with_comments=true&comment_size=50&with_likes=true&like_size=50"
        // 官方 FeedIdArrReq @SerializedName("ids")
        val body = ApiClient.gson.toJson(mapOf("ids" to ids))
        val result = call("/feed/batch$q", "POST", body, authorized = true, retry = true)
            ?: return emptyList()
        val type = object : TypeToken<List<MomentFeed>>() {}.type
        return ApiClient.gson.fromJson(result, type) ?: emptyList()
    }

    fun like(feedId: String) {
        val body = ApiClient.gson.toJson(mapOf("feed_id" to feedId))
        call("/like", "POST", body, authorized = true, retry = true)
    }

    fun cancelLike(feedId: String) {
        call("/like/$feedId", "DELETE", null, authorized = true, retry = true)
    }

    fun postComment(feedId: String, content: String, replyTo: String?) {
        val body = ApiClient.gson.toJson(
            mapOf(
                "feed_id" to feedId,
                "type" to 0,
                "content" to content,
                "reply_to" to (replyTo ?: "")
            )
        )
        call("/comment", "POST", body, authorized = true, retry = true)
    }

    // DELETE /comment/{feed_id}/{comment_id}（只能删自己的）
    fun deleteComment(feedId: String, commentId: String) {
        call("/comment/$feedId/$commentId", "DELETE", null, authorized = true, retry = true)
    }

    /**
     * 删除自己发布的动态（2026-10-25 新增）。
     * 协议与官方 App 一致：`DELETE /feed/{feed_id}`，成功码仍是 20000。
     * 服务端只允许删自己的动态，删别人的会返回非 20000 的业务错，由调用方提示。
     */
    fun deleteFeed(feedId: String) {
        call("/feed/$feedId", "DELETE", null, authorized = true, retry = true)
    }

    // GET /feed/{id}?with_comments=true... 单条动态（评论回刷用）
    fun getFeed(feedId: String): MomentFeed? {
        val q = "?with_comments=true&comment_size=50&with_likes=true&like_size=50"
        val result = call("/feed/$feedId$q", "GET", null, authorized = true, retry = true)
            ?: return null
        return ApiClient.gson.fromJson(result, MomentFeed::class.java)
    }

    /**
     * 发表文字动态。协议见官方 PublishFeedReq / FeedContent：
     * POST /feed {"type":0,"content":"{\"media\":[],\"text\":\"...\"}","org_ids":["<company_id>"]}
     * 返回新动态 feed_id。
     */
    fun publishFeed(text: String, media: List<com.lanhang.miuix.moments.MediaItem> = emptyList()): String? {
        require(text.isNotBlank() || media.isNotEmpty()) { text(R.string.error_content_empty) }
        // content 是 JSON 字符串，不是对象
        val content = ApiClient.gson.toJson(
            mapOf("media" to media, "text" to text)
        )
        val body = ApiClient.gson.toJson(
            mapOf(
                "type" to when {
                    media.isNotEmpty() && text.isNotBlank() -> 2
                    media.isNotEmpty() -> 1
                    else -> 0
                },
                "content" to content,
                "org_ids" to resolveOrgIds()
            )
        )
        val result = call("/feed", "POST", body, authorized = true, retry = true)
            ?: return null
        return ApiClient.gson.fromJson(result, FeedIdRepo::class.java)?.feed_id
    }

    /** org_ids 取登录用户所属公司（官方取第一个根公司）；取不到则传空数组 */
    private fun resolveOrgIds(): List<String> {
        if (Session.companyId.isNotEmpty()) return listOf(Session.companyId)
        val cid = try {
            RceApi.staffsBatch(listOf(Session.userId))?.data?.firstOrNull()?.company_id.orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (cid.isNotEmpty()) Session.companyId = cid
        return if (cid.isEmpty()) emptyList() else listOf(cid)
    }

    // 本地构造评论对象（接口返回的 result 未验证，成功后直接本地插入）
    fun localComment(feedId: String, content: String): CommentEntry =
        CommentEntry(
            comment_id = "local_${System.currentTimeMillis()}",
            feed_id = feedId,
            user_id = Session.userId,
            type = 0,
            content = content,
            reply_to = "",
            create_dt = System.currentTimeMillis()
        )

    // ------------------------------------------------------------ 互动消息
    //
    // 官方 `MomentHistoryMsgActivity` / `MomentNetHandler` 那一套：
    // history 的 result 是**裸数组**（不是 {data:[...]}），所以这里用 Array<T> 反序列化。

    /** 互动消息（type 1 = 评论，2 = 赞），`fromMessageId` 为空取第一页 */
    fun getHistoryMessages(fromMessageId: String?, size: Int = 20): List<InteractionMessage> {
        val q = "?from_message_id=${fromMessageId.orEmpty()}&size=$size"
        val result = call("/message/history$q", "GET", null, authorized = true, retry = true)
            ?: return emptyList()
        return try {
            ApiClient.gson.fromJson(result, Array<InteractionMessage>::class.java)?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 未读互动消息（客户端用来点小红点；服务端只回当前未读的那批） */
    fun getUnreadMessages(): List<InteractionMessage> {
        val result = call("/message/unread", "GET", null, authorized = true, retry = true)
            ?: return emptyList()
        return try {
            ApiClient.gson.fromJson(result, Array<InteractionMessage>::class.java)?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 未读条数（「我的」列表上的角标） */
    fun getUnreadCount(): Int {
        val result = call("/message/unread_count", "GET", null, authorized = true, retry = true)
            ?: return 0
        return try {
            ApiClient.gson.fromJson(result, UnreadCount::class.java)?.count ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
