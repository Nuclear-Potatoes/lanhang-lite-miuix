package com.lanhang.miuix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lanhang.miuix.R
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.isNightMode
import com.lanhang.miuix.util.LocaleHelper
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 带伪装请求头的通用 WebView 页（办事大厅 / 各 OA 应用）。
 *
 * 2026-10-25：原来的 MaterialToolbar 是 `?attr/colorPrimary` 的**整条蓝色顶栏**，跟 Miuix 外壳
 * 完全不是一套语言（用户明确反馈「顶栏依旧为蓝色」）。现在整页改成 Compose：
 * Miuix `SmallTopAppBar`（页面底色、没有蓝色、返回箭头用 Miuix 图标）+ WebView 填满剩余空间，
 * 加载中在顶栏下面压一条主色细线，不再用 Material 的圆形 ProgressBar。
 */
class OaWebViewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_APP_TOKEN = "appToken"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_COOKIE = "cookie"

        fun open(
            context: Context,
            url: String,
            appToken: String?,
            title: String?,
            cookie: String? = null,
        ) {
            context.startActivity(
                android.content.Intent(context, OaWebViewActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_APP_TOKEN, appToken)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_COOKIE, cookie)
            )
        }
    }

    private var headers: HashMap<String, String> = HashMap()
    private var webView: WebView? = null
    private var loading by mutableStateOf(true)
    private var titleText by mutableStateOf("")

    /**
     * **我们自己的页面栈**（第十二轮）。
     *
     * 之前靠 WebView 的 `canGoBack()/goBack()` 决定返回，被两个现实打穿：
     *  1. 本页为了让每个请求都带伪装头，每跳一次都要 `loadUrl(url, headers)`，
     *     而 `goBack()` 之后页面只要自己再重定向回原地址，历史就又长一条 ——
     *     越点返回历史越长，页面却一动不动（用户：「缴电费依旧卡在内部网页无法退出」）。
     *  2. 单页应用用 hash 路由时 `canGoBack()` 为真但页面视觉上没变化。
     * 所以现在**不再相信 WebView 的历史**：每次真正的主框架跳转都记进 [pageStack]，
     * 返回时按自己这份栈退一级，栈空了就直接关掉本页。
     */
    private val pageStack = ArrayList<String>()

    /** 日志用：去掉查询串，OA 的地址里常带 signtext/token，不许写进 logcat */
    private fun hideQuery(url: String): String = url.substringBefore('?')

    private fun stackTrace() = pageStack.joinToString(" -> ") { hideQuery(it) }

    /** 上一次按下返回时的地址与时间：同一个地址连续按两次 = 这页自己退不动，直接退出 */
    private var lastBackUrl: String? = null
    private var lastBackAt = 0L

    /** 刚刚被「返回」离开的地址：短时间内页面自己又想跳回去（重定向回原页）就拦掉，让用户停在上一条 */
    private var justLeftUrl: String? = null
    private var justLeftAt = 0L

    /**
     * 正在做「历史回退」。
     *
     * 第十一轮必须加这个标记：本页为了让每个请求都带上伪装头，`shouldOverrideUrlLoading`
     * 里对**每次跳转**都 `loadUrl(url, headers)`。问题是 `goBack()` 也会触发
     * `shouldOverrideUrlLoading`（目标 = 上一条历史），于是「回退」又被当成一次**新跳转**
     * 重新 load 一遍 —— 结果历史里又多出一条，`canGoBack()` 永远为 true 且页面看着没变，
     * 用户点多少次返回都停在原地（反馈：「缴电费页面反复返回无效」）。
     * 回退期间不重载、交给 WebView 自己走历史，返回键才真的能一级一级退出去。
     */
    private var restoringHistory = false

    /** 回退后的兜底：600ms 内历史位置没动 = 这次回退没生效，直接关掉页面，绝不把用户困住 */
    private var backFallback: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        titleText = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.web_title_fallback)

        val composeView = ComposeView(this)
        // 避开状态栏/挖孔（顶栏自己不处理 insets，统一在这一层加）
        ViewCompat.setOnApplyWindowInsetsListener(composeView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        setContentView(composeView)

        composeView.setContent {
            LanhangMiuixTheme(darkTheme = isNightMode()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background),
                ) {
                    SmallTopAppBar(
                        title = titleText,
                        navigationIcon = {
                            IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = getString(R.string.common_back),
                                )
                            }
                        },
                    )
                    if (loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(MiuixTheme.colorScheme.primary),
                        )
                    }
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        factory = { ctx -> createWebView(ctx, url) },
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBackOrFinish()
            }
        })
    }

    /**
     * 返回：① 同一地址再按一次 → 直接退出；② 自己的栈里还有上一页 → 回到上一页；③ 栈空 → 退出。
     *
     * 三条都是**确定性**的，不依赖 WebView 的历史语义，所以「点多少次都停在原地」不可能再发生：
     *  - [lastBackUrl]/[lastBackAt]：地址没变说明这次返回没换来新页面（页面自己跳回去、
     *    或单页应用），那么**第二次按返回就直接关掉本页**，绝不把用户困在网页里。
     *  - [pageStack]：正常情况退一级网页。
     *  - 退到第一页就 `finish()`，回到「工作」页。
     */
    private fun goBackOrFinish() {
        val view = webView
        if (view == null) {
            finishNow()
            return
        }
        val now = System.currentTimeMillis()
        val current = view.url.orEmpty()
        android.util.Log.i(
            "OaBack",
            "back: current=" + hideQuery(current) + " stack=[" + stackTrace() + "] lastBack=" +
                (lastBackUrl?.let { hideQuery(it) } ?: "-"),
        )

        // ① 上一次返回之后页面地址没变（页面自己又跳回来了 / 单页应用）→ 这次直接退出
        if (lastBackUrl == current && now - lastBackAt < 3000L) {
            android.util.Log.i("OaBack", "rule1: 地址没变，直接退出")
            finishNow()
            return
        }
        lastBackUrl = current
        lastBackAt = now

        // ② 自己的页面栈里还有上一页
        if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.size - 1)
            val prev = pageStack.last()
            android.util.Log.i("OaBack", "rule2: 回上一页 " + hideQuery(prev))
            if (prev.isNotBlank() && prev != current) {
                // 页面自己短时间内又想跳回刚才那一页（典型：缴费结果页重定向回缴费页），
                // 拦掉这种「程序发起」的跳转，让用户真的停在上一页（见 shouldOverrideUrlLoading）
                justLeftUrl = current
                justLeftAt = now
                restoringHistory = true
                cancelBackFallback()
                val beforeUrl = current
                val fallback = Runnable {
                    backFallback = null
                    restoringHistory = false
                    // 800ms 后还停在原来那一页 = 这次返回没生效，直接退出，不做第二次尝试
                    if (view.url.orEmpty() == beforeUrl) {
                        android.util.Log.i("OaBack", "兜底: 800ms 没换页，退出")
                        finishNow()
                    }
                }
                backFallback = fallback
                view.postDelayed(fallback, 800L)
                view.loadUrl(prev, headers)
                return
            }
        }

        // ③ 页面栈到底了：退出本页，回到「工作」
        android.util.Log.i("OaBack", "rule3: 栈底，退出本页")
        finishNow()
    }

    private fun cancelBackFallback() {
        backFallback?.let { webView?.removeCallbacks(it) }
        backFallback = null
    }

    private fun finishNow() {
        cancelBackFallback()
        restoringHistory = false
        if (!isFinishing && !isDestroyed) finish()
    }

    override fun onDestroy() {
        cancelBackFallback()
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, url: String): WebView {
        val view = WebView(context)
        webView = view

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 照抄原版指纹：去掉 "; wv"，追加 NchuApp UA
        val baseUa = settings.userAgentString.replace("; wv", "")
        val netType = "WIFI"
        settings.userAgentString =
            "$baseUa NchuApp/${Session.appVersion} NetType/$netType MicroMessenger/8.0.47.2560"

        // 同步 RCE 会话 Cookie 进 WebView（原版 syncCookie 行为）
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        com.lanhang.miuix.net.ApiClient.allCookies().forEach { (domain, cookie) ->
            cm.setCookie("http://$domain", cookie)
            cm.setCookie("https://$domain", cookie)
        }
        cm.flush()

        // 工作模块预检时拿到的 Cookie：直接塞给目标域名，子请求也能带上
        intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotEmpty() }?.let { value ->
            cm.setCookie(url, value)
            val host = android.net.Uri.parse(url).host ?: ""
            runCatching { cm.setCookie("http://$host", value) }
            cm.setCookie("http://$host", value)
            cm.setCookie("https://$host", value)
            cm.flush()
        }

        headers = HashMap<String, String>().apply {
            intent.getStringExtra(EXTRA_APP_TOKEN)?.takeIf { it.isNotEmpty() }
                ?.let { put("Token", it) }
                ?: run { Session.ldapToken.takeIf { it.isNotEmpty() }?.let { put("Token", it) } }
            put("Version", Session.appVersion)
            put("DeviceID", Session.deviceId(this@OaWebViewActivity))
            put("NetType", netType)
            com.lanhang.miuix.net.ApiClient.cookieHeader()?.let { put("Cookie", it) }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                // 回退期间的那次加载：不拦截（这是我们自己发起的 loadUrl）
                if (restoringHistory) return false
                // 非 http(s)（tel:、mailto:、唤起别的 App）：交系统，别丢给 WebView
                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                    runCatching {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
                // 刚被返回离开的那一页，若**不是用户点的**（页面自己重定向回去），拦掉：
                // 否则用户按返回后页面立刻自己跳回来，看到的就是「怎么点都不动」
                val now = System.currentTimeMillis()
                if (target == justLeftUrl && now - justLeftAt < 2000L && !request.hasGesture()) {
                    return true
                }
                // 同一个地址的重复跳转（hash 路由、重定向）不重载
                if (target == v.url) return true
                // 记进自己的页面栈。两种情况特殊处理：
                //  · A → B → A（典型：缴费结果页 302 回缴费页）：B 用户根本没看见，
                //    把它从栈里删掉，别让「返回」弹到一个不可见的中间页上；
                //  · 与栈顶相同：不重复记。
                if (!request.hasGesture() && pageStack.size >= 2 &&
                    pageStack[pageStack.size - 2] == target
                ) {
                    pageStack.removeAt(pageStack.size - 1)
                } else if (pageStack.lastOrNull() != target) {
                    pageStack.add(target)
                }
                android.util.Log.i("OaBack", "nav -> " + hideQuery(target) + " stack=[" + stackTrace() + "]")
                // 其余跳转照旧：带伪装头重新加载（原版行为）
                v.loadUrl(target, headers)
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(v, url, favicon)
                // 只撤「回退中」标记，**不要**在这里撤销兜底：
                // 第十二轮踩过的坑 —— 回退一开始 onPageStarted 就先到，兜底被取消，
                // 于是「页面自己跳回原页」时再也没有人把用户放出去（缴电费就是这么卡住的）。
                restoringHistory = false
                loading = true
            }

            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                loading = false
            }

            /**
             * 3xx 重定向**不会**经过 `shouldOverrideUrlLoading`（WebView 自己跟的），
             * 所以「缴费结果页 302 回缴费页」这类落地地址不会进我们的页面栈 ——
             * 实测后果：栈顶停在看不见的 /result，返回时拿它和当前地址一比就只好直接退出，
             * 跳过了真正的上一页。这里在每次提交历史时把栈顶对齐成真实地址。
             */
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(v, url, isReload)
                if (restoringHistory || isReload) return
                val landed = url.orEmpty()
                if (!landed.startsWith("http")) return
                if (pageStack.isEmpty()) {
                    pageStack.add(landed)
                    return
                }
                if (pageStack.last() == landed) return
                // 落地地址就是上一条（A → B 又重定向回 A）：把看不见的 B 删掉，别留中间页
                if (pageStack.size >= 2 && pageStack[pageStack.size - 2] == landed) {
                    pageStack.removeAt(pageStack.size - 1)
                } else {
                    pageStack[pageStack.size - 1] = landed
                }
            }
        }

        pageStack.clear()
        pageStack.add(url)
        view.loadUrl(url, headers)
        return view
    }
}
