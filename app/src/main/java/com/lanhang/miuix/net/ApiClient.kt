package com.lanhang.miuix.net

import android.content.Context
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

object ApiClient {
    const val BASE_URL = "https://nginx-1.app.nchu.edu.cn"
    const val API_PREFIX = "/api"

    lateinit var http: OkHttpClient
        private set
    val gson: Gson = Gson()

    private val cookies = ConcurrentHashMap<String, Cookie>()
    private var debugContext: Context? = null
    private var cookieSp: android.content.SharedPreferences? = null

    private data class StoredCookie(
        val domain: String? = null,
        val name: String? = null,
        val value: String? = null,
        val path: String? = null,
        val expiresAt: Long = 0,
        val secure: Boolean = false,
        val httpOnly: Boolean = false
    )

    fun init(context: Context) {
        debugContext = context.applicationContext
        cookieSp = context.getSharedPreferences("lanhang_cookies", Context.MODE_PRIVATE)
        loadCookies()
        // 第二十四轮：断网时必须有「快速失败」。
        // 以前只有 connect 20s / read 30s，而 OkHttp 在 DNS 失败、弱网、重试的情况下
        // 会把一圈组合起来拉到一分钟以上，界面就一直停在「加载中」黑屏 ——
        // 用户看到的不是「加载失败」，是「永远不动」。所以补一条 callTimeout：
        // 整次调用（含重试、重定向、读 body）硬上限，到点就抛 IOException，
        // 页面统一走「加载失败 → 点击重新加载」。
        http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
                    for (cookie in list) {
                        val key = "${cookie.domain}|${cookie.name}"
                        if (cookie.expiresAt > System.currentTimeMillis()) {
                            cookies[key] = cookie
                            persist(cookie)
                        } else {
                            cookies.remove(key)
                            cookieSp?.edit()?.remove(key)?.apply()
                        }
                        appendDebug("SET $url -> ${cookie.name} (domain=${cookie.domain}, path=${cookie.path}, expiresAt=${cookie.expiresAt})")
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookies.values.filter { it.matches(url) }
                }
            })
            .build()
    }

    private fun loadCookies() {
        val sp = cookieSp ?: return
        for ((key, raw) in sp.all) {
            try {
                val stored = gson.fromJson(raw as? String ?: continue, StoredCookie::class.java)
                if (stored?.name == null || stored.domain == null) continue
                if (stored.expiresAt in 1 until System.currentTimeMillis()) continue
                val builder = Cookie.Builder()
                    .name(stored.name)
                    .value(stored.value.orEmpty())
                    .path(stored.path ?: "/")
                    .domain(stored.domain)
                if (stored.secure) builder.secure()
                if (stored.httpOnly) builder.httpOnly()
                if (stored.expiresAt > 0) builder.expiresAt(stored.expiresAt)
                cookies[key] = builder.build()
            } catch (_: Exception) {
            }
        }
    }

    private fun persist(cookie: Cookie) {
        val stored = StoredCookie(
            domain = cookie.domain,
            name = cookie.name,
            value = cookie.value,
            path = cookie.path,
            expiresAt = cookie.expiresAt,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly
        )
        cookieSp?.edit()
            ?.putString("${cookie.domain}|${cookie.name}", gson.toJson(stored))
            ?.apply()
    }

    private fun appendDebug(line: String) {
        try {
            val dir = debugContext?.filesDir ?: return
            val file = java.io.File(dir, "cookies_debug.txt")
            file.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    fun cookieHeader(): String? {
        if (cookies.isEmpty()) return null
        return cookies.values.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun allCookies(): Map<String, String> {
        return cookies.values.groupBy { it.domain }
            .mapValues { (_, list) -> list.joinToString("; ") { "${it.name}=${it.value}" } }
    }

    /** 按名字/域名（子串）匹配批量删 cookie（图书馆座位系统的 lib-zw 会话、校园网门户会话用它清）。
     *  2026-10-27 移植图书馆座位预约时新增：上游同名方法，语义一致。 */
    fun clearCookiesMatching(match: String) {
        val it = cookies.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.contains(match)) {
                it.remove()
                cookieSp?.edit()?.remove(e.key)?.apply()
            }
        }
    }

    fun clearCookies() {
        cookies.clear()
        cookieSp?.edit()?.clear()?.apply()
    }

    /** 只清 miniapp 会话 Cookie，保留 RCE 等其他域会话 */
    fun clearMiniappCookies() {
        val it = cookies.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.contains("miniapp")) {
                it.remove()
                cookieSp?.edit()?.remove(e.key)?.apply()
            }
        }
    }

    // 融云圈鉴权需要登录会话 cookie 的“值部分”（官方 MainActivity:252-257 取 = 后面的值）
    // 官方 HttpClientHelper.getCookie(): cookieStore.get(URI("https://nginx-1.app.nchu.edu.cn/api/"))
    // 登录下发的 RCESESSIONID path=/api，必须用 /api/ 探测 URL 才能匹配到（用根路径会漏掉）
    fun firstSessionCookieValue(): String? {
        val probe = (BASE_URL + "/api/").toHttpUrl()
        val list = cookies.values.filter { it.matches(probe) }
        try {
            debugContext?.filesDir?.let { dir ->
                java.io.File(dir, "cookies_candidates.txt").writeText(
                    buildString {
                        appendLine("=== candidates for $probe ===")
                        list.forEach { appendLine("${it.name}=${it.value} (domain=${it.domain}, path=${it.path})") }
                    }
                )
            }
        } catch (_: Exception) {
        }
        return list.firstOrNull { it.name.equals("RCESESSIONID", ignoreCase = true) }?.value
            ?: list.firstOrNull { it.name.equals("JSESSIONID", ignoreCase = true) }?.value
            ?: list.firstOrNull()?.value
    }
}
