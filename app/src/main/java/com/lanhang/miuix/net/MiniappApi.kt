package com.lanhang.miuix.net

import android.util.Log
import com.lanhang.miuix.R
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import okhttp3.Request

/**
 * 课表 miniapp 接口：
 * 1. GET go.nchu.edu.cn/?id=78&state=/schedule （带 Token/Version/DeviceID 头）
 *    → 服务端 302 到 miniapp.app.nchu.edu.cn/api/auth?uid=...&signtext=...&redir=/schedule
 * 2. 跟随后拿到 miniapp 会话 Cookie
 * 3. GET miniapp.app.nchu.edu.cn/api/common?method=GetSchedule&week=N
 */
object MiniappApi {
    private const val TAG = "MiniappApi"
    private const val GO_URL = "http://go.nchu.edu.cn/?id=78&state=/schedule"
    private const val MINIAPP = "http://miniapp.app.nchu.edu.cn"
    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    const val UA =
        "Mozilla/5.0 (Linux; Android 16; 23116PN5BC Build/BP2A.250605.031.A3) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 " +
            "Mobile Safari/537.36 NchuApp/${Session.appVersion} NetType/WIFI MicroMessenger/8.0.47.2560"

    private fun client() = ApiClient.http

    /** 走 go.nchu.edu.cn 网关换 miniapp 会话；成功返回 true */
    fun handshake(deviceId: String): Boolean {
        // ldapToken 约 2 小时过期（Error code=4000），握手前先用 RCE 会话刷新
        if (!isJwtFresh(Session.ldapToken)) {
            try {
                RceApi.getLdapToken()?.let { Session.ldapToken = it }
            } catch (e: Exception) {
                Log.w(TAG, "refresh ldapToken failed: $e")
            }
        }
        val req = Request.Builder()
            .url(GO_URL)
            .header("User-Agent", UA)
            .header("Token", Session.ldapToken)
            .header("Version", Session.appVersion)
            .header("DeviceID", deviceId)
            .get()
            .build()
        return try {
            client().newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "handshake code=${resp.code} url=${resp.request.url} body=${body.take(300)}")
                // 会话 Cookie 已存进 ApiClient 的 CookieJar
                resp.code in 200..299 && !body.contains("身份鉴权失败") && !body.contains("Error.aspx")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handshake failed: $e")
            false
        }
    }

    /** 粗略判断 JWT 是否还有 >5 分钟有效期（exp 在 payload 第二段，base64url） */
    private fun isJwtFresh(token: String): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size < 3) return false
            val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            val json = String(payload, Charsets.UTF_8)
            val exp = Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return false
            exp * 1000 - System.currentTimeMillis() > 5 * 60 * 1000
        } catch (e: Exception) {
            false
        }
    }

    /** 拉取某周课表原始 JSON；week=null 表示默认（当前周） */
    fun getScheduleRaw(week: Int?): String {
        val url = if (week == null) {
            "$MINIAPP/api/common?method=GetSchedule"
        } else {
            "$MINIAPP/api/common?method=GetSchedule&week=$week"
        }
        return fetchCommon(url, "schedule_w${week ?: "cur"}")
    }

    /** 学年学期列表（成绩页用），原始 JSON */
    fun getXnxqListRaw(): String =
        fetchCommon("$MINIAPP/api/common?method=GetXnxqList", "xnxq_list")

    /** 某学期成绩明细，原始 JSON */
    fun getStudentScoreRaw(xnxqId: String): String =
        fetchCommon("$MINIAPP/api/common?method=GetStudentScore&xnxqId=$xnxqId", "score_$xnxqId")

    private fun fetchCommon(url: String, dumpName: String): String {
        var body = fetchOnce(url)
        // 会话失效时服务端返回 200 空 body：清 miniapp cookie 重握手再试一次
        if (body.isBlank()) {
            Log.w(TAG, "empty body for $dumpName, re-handshake and retry")
            ApiClient.clearMiniappCookies()
            if (!handshake(Session.deviceId(appContext))) {
                throw ApiException(-1, AppContext.instance.getString(R.string.error_schedule_login))
            }
            body = fetchOnce(url)
        }
        Log.d(TAG, "fetchCommon $dumpName len=${body.length}")
        // 存到应用私有目录便于 run-as 拉取
        try {
            val f = java.io.File(appContext.filesDir, "$dumpName.json")
            f.writeText(body, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "save failed: $e")
        }
        return body
    }

    private fun fetchOnce(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()
        return client().newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(TAG, "fetchOnce code=${resp.code} len=${body.length}")
            if (!resp.isSuccessful) {
                throw ApiException(
                    resp.code,
                    AppContext.instance.getString(R.string.error_schedule_request)
                )
            }
            body
        }
    }

    /** 探测其他可能的 method（节次/周次配置等），仅调试用 */
    fun probe(method: String, extra: String = ""): String {
        val url = "$MINIAPP/api/common?method=$method$extra"
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        return client().newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(TAG, "probe $method code=${resp.code} body=${body.take(500)}")
            body
        }
    }
}
