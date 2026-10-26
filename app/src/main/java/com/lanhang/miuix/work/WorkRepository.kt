package com.lanhang.miuix.work

import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.net.WorkApp
import com.lanhang.miuix.net.WorkDirectory
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作（OA）数据源。
 *
 * 官方 App 的「工作」只是个 WebView 壳：门户地址来自 `/configuration/all` 的
 * `features.work.url`，应用目录来自 `POST /apps/all`，点某个应用再换
 * `GET /apps/getAppToken/{id}` 的 token 去开页面。
 */
object WorkRepository {

    @Volatile
    private var directory: WorkDirectory? = null

    @Volatile
    private var portal: String? = null

    /** 门户地址：优先用缓存，没有就拉一次配置 */
    suspend fun portalUrl(force: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!force) portal?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        Session.workUrl.takeIf { it.isNotBlank() }?.let {
            portal = it
            return@withContext it
        }
        val fromConfig = runCatching {
            RceApi.configurationAll().features?.work?.url?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (fromConfig != null) {
            Session.workUrl = fromConfig
            portal = fromConfig
            fromConfig
        } else {
            ""
        }
    }

    suspend fun directory(force: Boolean = false): WorkDirectory = withContext(Dispatchers.IO) {
        if (!force) directory?.let { return@withContext it }
        val fresh = RceApi.appsAll()
        directory = fresh
        runCatching {
            fresh.sections().forEach { (title, apps) ->
                android.util.Log.i(
                    TAG,
                    "section=${title ?: "loose"} " + apps.joinToString("｜") { "${it.name}(range=${it.range})" },
                )
            }
        }
        fresh
    }

    suspend fun appToken(app: WorkApp): String? = withContext(Dispatchers.IO) {
        val id = app.id?.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching { RceApi.getAppToken(id) }.getOrNull()
    }

    /**
     * 打开某个办事应用前的准备：换 app token → 先把 `go.nchu.edu.cn` 网关走一遍
     * （网关会 302 到真正的应用地址，同时下发会话 Cookie），最后把落地地址和 Cookie 一起交给 WebView。
     *
     * 不预检直接让 WebView 打开的话，网关只能看到「首帧带了 Token 头、子请求没带」的请求，
     * 会直接回 `Error.aspx?code=4000 客户端身份鉴权失败`。
     */
    suspend fun prepare(app: WorkApp): WorkLaunch = withContext(Dispatchers.IO) {
        val token = appToken(app)
        val start = app.openUrl.orEmpty()
        if (start.isBlank()) return@withContext WorkLaunch("", token, null)
        WorkLaunch(preflight(start, token), token, cookieFor(start))
    }

    /** 门户页（配置里的 features.work.url）同样先走一遍网关，拿 ldap token */
    suspend fun preparePortal(url: String): WorkLaunch = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext WorkLaunch("", null, null)
        val token = Session.ldapToken.takeIf { it.isNotBlank() }
            ?: runCatching { RceApi.getLdapToken() }.getOrNull()
        WorkLaunch(preflight(url, token), token, cookieFor(url))
    }

    /** 跟随跳转，把落地地址和沿途 Cookie 收进 ApiClient 的 CookieJar */
    private fun preflight(url: String, token: String?): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", MiniappApi.UA)
            .header("Version", Session.appVersion)
            .header("DeviceID", Session.deviceId(AppContext.instance))
            .get()
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Token", it) }
        return runCatching {
            ApiClient.http.newCall(builder.build()).execute().use { resp ->
                val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                android.util.Log.i(
                    TAG,
                    "preflight code=${resp.code} final=${resp.request.url} body=${body.take(200)}",
                )
                resp.request.url.toString()
            }
        }.getOrDefault(url)
    }

    /** 该地址对应域名下的 Cookie 串（WebView 那边不一定能自己带上） */
    private fun cookieFor(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        val matched = ApiClient.allCookies()
            .filterKeys { domain ->
                val d = domain.lowercase().trimStart('.')
                host == d || host.endsWith(".$d") || d.endsWith(".$host")
            }
            .values
        return (matched.takeIf { it.isNotEmpty() } ?: ApiClient.allCookies().values.toList())
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ")
    }

    private const val TAG = "WorkRepository"
}

/** 交给 WebView 的三件套 */
data class WorkLaunch(val url: String, val token: String?, val cookie: String?)
