package com.lanhang.miuix.ui

import android.content.Context
import android.webkit.CookieManager

/**
 * 校外站点的登录态持久化（第七十二轮）。
 *
 * 用户反馈：「需要让内部网页记住赣青二课的登录态，省去反复登录的过程」。
 *
 * 为什么 `CookieManager` 自己不够：Android 的 CookieManager 只把**带 Expires 的持久 Cookie**
 * 写进磁盘，会话 Cookie（Set-Cookie 里没有 Expires/Max-Age）只活在内存里 —— 本页一 `finish()`，
 * 或者进程被回收一次，登录态就没了。赣青二课（`gqhw.jxmediacenter.com`）这类若依 + uni-app
 * 的站点，登录票据恰恰常常是会话 Cookie，于是表现就是「每次进去都要重新登录」。
 *
 * 所以这里在页面加载完成 / 本页暂停时，把该域名当前的 Cookie 串整条抄进 SharedPreferences；
 * 下次建 WebView 时先写回 CookieManager 再加载，登录态就接上了。
 *
 * 只处理**校外**域名：学校域名（`*.nchu.edu.cn`）的 Cookie 由
 * [com.lanhang.miuix.net.ApiClient] 统一管理与同步，不能被这里的旧快照覆盖回去。
 */
internal object WebLoginStore {

    private const val PREF = "web_login"

    /** 超过这个时长就不再写回：票据放久了多半已失效，写回去反而多一次失败请求 */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 把某个域名此刻的 Cookie 串存下来（含会话 Cookie） */
    fun save(context: Context, host: String) {
        if (host.isBlank()) return
        val raw = runCatching {
            CookieManager.getInstance().getCookie("https://$host")
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return
        prefs(context).edit()
            .putString(host, raw)
            .putLong("$host.at", System.currentTimeMillis())
            .apply()
    }

    /** 把上次存下的 Cookie 串写回 CookieManager（过期就丢弃） */
    fun restore(context: Context, host: String) {
        if (host.isBlank()) return
        val p = prefs(context)
        val raw = p.getString(host, null)?.takeIf { it.isNotBlank() } ?: return
        val at = p.getLong("$host.at", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > MAX_AGE_MS) {
            p.edit().remove(host).remove("$host.at").apply()
            return
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // setCookie 一次只吃一条，"a=1; b=2" 必须拆开写（与 OaWebViewActivity#setCookies 同理）
        raw.split(";").forEach { piece ->
            val item = piece.trim()
            if (item.contains('=') && !item.startsWith("=")) {
                runCatching { cm.setCookie("https://$host", item) }
            }
        }
        runCatching { cm.flush() }
    }
}
