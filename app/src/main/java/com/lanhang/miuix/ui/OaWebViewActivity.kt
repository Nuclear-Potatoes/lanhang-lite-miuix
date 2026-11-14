package com.lanhang.miuix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lanhang.miuix.R
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.isNightMode
import com.lanhang.miuix.util.LocaleHelper
import com.lanhang.miuix.work.WorkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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

    /**
     * 网页 `<input type="file">` 等着的回调。
     *
     * WebView 自己不会弹系统文件选择器（默认实现直接返回 false），必须由宿主 Activity 代劳。
     * 2026-11-14 第七十一轮：接入「赣青二课」后才需要它 —— 那套系统要上传头像和实践证明材料，
     * 没有这一层的话用户点「上传」毫无反应，看起来就像功能坏了。
     */
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 网页请求摄像头 / 麦克风、但系统权限还没授给本应用时，先把这次请求挂在这里。
     *
     * 2026-11-14 第七十五轮（用户：「这个网页要求扫码能力，点击主页右上角的按钮需要进入
     * 扫码界面」）。赣青二课的扫码页（`pages/scan/scan`）是用 html5-qrcode 那套
     * `getUserMedia` 自己开摄像头的，不依赖 App 给原生扫码界面。WebView 里要让它跑起来，
     * 必须同时满足两件事：清单里声明 CAMERA、并在 [WebChromeClient.onPermissionRequest]
     * 里显式 grant。少任何一件，网页那边拿到的都是 NotAllowedError，表现就是「进了扫码页
     * 但画面全黑」。
     */
    private var pendingMediaRequest: PermissionRequest? = null

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
        private const val EXTRA_PREFLIGHT_URL = "preflightUrl"
        private const val EXTRA_APP_ID = "appId"

        /** 网页 <input type="file"> 触发的系统文件选择器请求码 */
        private const val REQUEST_FILE_CHOOSER = 4001

        /** 网页请求摄像头/麦克风时，本应用向系统申请运行时权限的请求码 */
        private const val REQUEST_MEDIA_PERMISSION = 4002

        /**
         * 打开一个 OA 应用页。
         *
         * 2026-10-27 第十八轮：加了 [preflightUrl] / [appId]。给了它们，本页会**先出界面**
         * （加载态），再在后台把网关走完、拿到真正的落地地址 —— 调用方（工作页）点一下
         * 立刻就有反应，再也不会出现「点开没响应、其他应用也打不开」。
         */
        fun open(
            context: Context,
            url: String,
            appToken: String?,
            title: String?,
            cookie: String? = null,
            preflightUrl: String? = null,
            appId: String? = null,
        ) {
            context.startActivity(
                android.content.Intent(context, OaWebViewActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_APP_TOKEN, appToken)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_COOKIE, cookie)
                    .putExtra(EXTRA_PREFLIGHT_URL, preflightUrl)
                    .putExtra(EXTRA_APP_ID, appId)
            )
        }
    }

    private var headers: HashMap<String, String> = HashMap()
    private var webView: WebView? = null
    private var loading by mutableStateOf(true)
    private var titleText by mutableStateOf("")

    /** 预检没做完时为 null：这段时间页面显示加载态，而不是一片白 */
    private var resolvedUrl by mutableStateOf<String?>(null)

    /** 主框架加载失败 / 渲染成白板：露出「重试 / 用浏览器打开」的出路 */
    private var pageTrouble by mutableStateOf(false)

    /**
     * 正文是否已经真的画出来（第十八轮）。
     *
     * 用户第 1 条投诉的另一半是「打开后一片白、也不知道是在转圈还是死了」：学生邮箱
     * (`mail.stu.nchu.edu.cn/mail/`) 这种站点最狠 —— `onPageFinished` 说加载完成，
     * 屏幕上是纯白。所以首帧正文出现之前一律盖一层加载态；12s 还没正文就判定白板。
     */
    private var firstPaintDone by mutableStateOf(false)

    /** 最近一次 `onPageFinished` 量到的正文长度（-1 = 还没量过） */
    private var bodyBits by mutableStateOf(-1)

    /** 预检用的协程作用域（本页销毁就取消） */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        // 真正的落地地址在本页里异步解析：网关要跳好几次，慢的时候十几秒。
        // 界面先出来（加载态），用户随时可以返回，也能立刻去看别的应用。
        val preflightUrl = intent.getStringExtra(EXTRA_PREFLIGHT_URL)
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        if (preflightUrl.isNullOrBlank()) {
            resolvedUrl = url
        } else {
            uiScope.launch {
                val launch = withContext(Dispatchers.IO) {
                    runCatching { WorkRepository.prepareFor(preflightUrl, appId) }.getOrNull()
                }
                resolvedUrl = launch?.url?.takeIf { it.isNotBlank() } ?: preflightUrl
            }
            // 白板看门狗：12s 内正文一直是空的，就不再假装「还在加载」，把出路摆出来
            uiScope.launch {
                kotlinx.coroutines.delay(12_000)
                if (bodyBits <= 0 && !pageTrouble) {
                    android.util.Log.i("OaWebView", "12s 正文仍为空 -> 判定白板，露出提示")
                    pageTrouble = true
                }
            }
        }

        val composeView = ComposeView(this)
        // 避开状态栏/挖孔（顶栏自己不处理 insets，统一在这一层加）
        ViewCompat.setOnApplyWindowInsetsListener(composeView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        setContentView(composeView)

        composeView.setContent {
            LanhangMiuixTheme(darkTheme = com.lanhang.miuix.ui.miuix.ThemeState.isDark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background),
                ) {
                    SmallTopAppBar(
                        title = titleText,
                        navigationIcon = {
                            // 2026-11-14 第七十四轮（用户：「在 web 页面内，触发返回手势就是返回页面中的上一级，
// 点击屏幕右上方的返回键则直接退出整个 web 页面」）：顶栏这个 ✕/← 现在是「退出本页」，
// 网页内的历史回退交给系统返回手势（见下面的 OnBackPressedCallback → goBackOrFinish）。
IconButton(onClick = { finishNow() }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = getString(R.string.common_back),
                                    tint = MiuixTheme.colorScheme.onBackground,
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
                    // 白屏 / 加载失败时给一条确定的出路（以前只能对着白屏瞎点）
                    if (pageTrouble) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.oa_page_blank_hint),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    text = stringResource(R.string.oa_page_reload),
                                    onClick = {
                                        pageTrouble = false
                                        loading = true
                                        webView?.reload()
                                    },
                                )
                                TextButton(
                                    text = stringResource(R.string.oa_page_open_browser),
                                    onClick = { openInBrowser() },
                                )
                            }
                        }
                    }
                    val ready = resolvedUrl
                    if (ready == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator()
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx -> createWebView(ctx, ready) },
                            )
                            // 正文画出来之前盖一层加载态：宁可见转圈，也不要「无声白屏」
                            if (!firstPaintDone && !pageTrouble) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MiuixTheme.colorScheme.background),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    InfiniteProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
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
    /**
     * 白板检测脚本（见 [onPageFinished]）。
     *
     * 返回 `正文长度|正文高度`。正文高度 ≈ 0 时先注入一条最小高度规则再量一次 ——
     * 实测学生邮箱登录页正是靠这一条从纯白变成能看见登录框的（见第十八轮记录）。
     */
    private val BLANK_CHECK_JS = """
        (function(){try{
          var b=document.body; if(!b) return '0|0';
          var t=(b.innerText||'').trim();
          var bits=t.length+b.getElementsByTagName('img').length+b.getElementsByTagName('canvas').length;
          var h=Math.round(b.getBoundingClientRect().height);
          if(h<=2){
            var st=document.createElement('style');
            st.textContent='html,body{height:auto!important;min-height:100%!important}';
            (document.head||document.documentElement).appendChild(st);
            h=Math.round(b.getBoundingClientRect().height);
          }
          return bits+'|'+h;
        }catch(e){return '1|1';}})()
    """.trimIndent()

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

    /**
     * 把非 http(s) 的链接交给系统（拉起微信/支付宝/拨号/邮件，或 `intent://` 打包跳转）。
     *
     * 2026-11-14 第七十四轮（用户：「缴电费界面充值无法拉起微信/支付宝，修复这些问题」）：
     * 原来这里只有一句 `runCatching { startActivity(ACTION_VIEW, uri) }`，失败被完全吞掉，
     * 用户看到的就是「点充值没反应」。两个真实原因：
     *  1. Android 11+ 的包可见性（package visibility）—— manifest 里没有 `<queries>` 时，
     *     系统根本不告诉我们设备上装了微信/支付宝，Intent 解析必然失败。已在清单里补齐。
     *  2. 支付跳转常用 `intent://…#Intent;scheme=alipays;package=…;end` 这种打包形式，
     *     直接 ACTION_VIEW 打不开，得先用 `Intent.parseUri` 解包；解出来的 Intent 如果
     *     还带了 `browser_fallback_url`，那是应用商给的网页兜底地址。
     * 现在按「解包 → 直接 VIEW → 兜底地址 → 明确提示」四级依次尝试，绝不再静默失败。
     */
    private fun openExternal(raw: String, uri: Uri) {
        fun fail() {
            android.widget.Toast.makeText(
                this,
                getString(R.string.oa_open_app_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        if (raw.startsWith("intent:", ignoreCase = true)) {
            val parsed = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            if (parsed != null) {
                if (runCatching { startActivity(parsed) }.isSuccess) return
                val fallback = parsed.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    if (runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                        }.isSuccess
                    ) return
                }
            }
            // `intent://` 里可能是 weixin:// 之类，再按普通 scheme 试一次
            val scheme = raw.substringAfter("scheme=", "").substringBefore(";")
            if (scheme.isNotBlank()) {
                if (runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://")))
                    }.isSuccess
                ) return
            }
            fail()
            return
        }

        val view = Intent(Intent.ACTION_VIEW, uri)
        if (runCatching { startActivity(view) }.isSuccess) return
        fail()
    }
    private fun finishNow() {
        cancelBackFallback()
        restoringHistory = false
        if (!isFinishing && !isDestroyed) finish()
    }

    /**
     * 把系统文件选择器的结果回填给网页。
     *
     * 多选（`<input multiple>`）要从 [Intent.getClipData] 里逐个取，单选取 [Intent.getData]；
     * 用户按返回取消时必须回 `null`，否则那个 `<input>` 会永远卡在「等待选择」。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val cb = fileCallback ?: return
        fileCallback = null
        val uris: Array<Uri>? = if (resultCode != Activity.RESULT_OK || data == null) {
            null
        } else {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        }
        cb.onReceiveValue(uris)
    }

    /**
     * 网页请求摄像头/麦克风时，系统权限弹窗的结果回到这里。
     *
     * 授权成功就把挂住的那次 [PermissionRequest] 补 grant；失败或用户拒绝时 deny，
     * 让网页那边拿到明确的 NotAllowedError，而不是一直转圈。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA_PERMISSION) return
        val request = pendingMediaRequest ?: return
        pendingMediaRequest = null
        val ok = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!ok) {
            runCatching { request.deny() }
            return
        }
        val wanted = request.resources.orEmpty().filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        runCatching { request.grant(wanted.toTypedArray()) }
            .onFailure { runCatching { request.deny() } }
    }

    /** 第七十二轮：离开本页前再存一次登录态（有些站点是登录成功后才写 Cookie，页面没重载） */
    override fun onPause() {
        super.onPause()
        val h = webView?.url?.let { android.net.Uri.parse(it).host.orEmpty() }.orEmpty()
        if (h.isNotEmpty() && !h.endsWith("nchu.edu.cn")) {
            WebLoginStore.save(this, h)
        }
    }

    override fun onDestroy() {
        cancelBackFallback()
        uiScope.cancel()
        webView = null
        super.onDestroy()
    }

    /** 用系统浏览器打开当前地址：有些站的脚本就是不肯在 WebView 里跑 */
    private fun openInBrowser() {
        val target = webView?.url.orEmpty().ifBlank { resolvedUrl.orEmpty() }
        if (target.isBlank()) return
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target))
            )
        }
    }

    /** `setCookie` 一次只吃一条，"a=1; b=2" 必须拆开写，否则存成畸形 Cookie（第三方站会白屏） */
    private fun setCookies(cm: android.webkit.CookieManager, target: String, raw: String) {
        raw.split(";").forEach { piece ->
            val item = piece.trim()
            if (item.contains('=') && !item.startsWith("=")) {
                runCatching { cm.setCookie(target, item) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, url: String): WebView {
        val view = WebView(context)
        webView = view

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // 2026-11-14 第七十五轮：赣青二课的「地图打卡 / 定位签到」走 H5 的 getCurrentPosition，
        // WebView 默认把地理定位关着，网页拿到的永远是 PositionUnavailable；
        // 扫码页与活动视频还需要媒体自动播放能力，否则 getUserMedia / play() 会被用户手势策略挡住。
        settings.setGeolocationEnabled(true)
        settings.mediaPlaybackRequiresUserGesture = false

        val host = android.net.Uri.parse(url).host.orEmpty()

        // 照抄原版指纹：去掉 "; wv"，追加 NchuApp UA
        val baseUa = settings.userAgentString.replace("; wv", "")
        val netType = "WIFI"
        // 2026-10-27 第十八轮：**MicroMessenger 只给 OA 网关那几个站**。
        // 学生邮箱（`mail.stu.nchu.edu.cn`）的登录页是「微信内嵌」版：UA 里带 MicroMessenger
        // 时它会等微信 JS 桥（WeixinJSBridge）注入再显示内容，WebView 里永远等不到 ——
        // DOM 完整、innerText 有字，但一个像素都不画，用户看到的就是纯白板。
        // 网关/办事大厅那些页反过来需要微信指纹，所以按站点分。
        val oaHost = host.endsWith("app.nchu.edu.cn") || host == "go.nchu.edu.cn"
        // 只有 OA 那些 H5（没有 viewport meta、靠 980px 宽视口排版）需要 wide viewport；
        // 学生邮箱这类标准自适应页面开着它反而会把 body 量成 0 高（DOM 有字、页面全白）。
        settings.loadWithOverviewMode = oaHost
        settings.useWideViewPort = oaHost
        settings.userAgentString = "$baseUa NchuApp/${Session.appVersion} NetType/$netType" +
            if (oaHost) " MicroMessenger/8.0.47.2560" else ""
        // 2026-11-14 第七十七轮（用户：「点击赣青二课的按钮依旧无法扫码」）：
        // 二课首页右上角的扫码按钮 handleSenCode() 只有两条分支 ——
        //   ① isWeiXin() 为真 → navigateTo('/pages/scan/scan')，用站点自带的 html5-qrcode 扫码；
        //   ② 否则 → window.CP2.showScanQr({needResult:1})，CP2 是青新闻 App 注入的原生桥。
        // 我们的 WebView 里既不是微信环境、也没有 CP2，点下去必然 TypeError，界面毫无反应。
        // CP2 那条要自建原生扫码页（相机 + 解码 + 回调），成本高；而站点自己的 H5 扫码页
        // 现成可用（CAMERA 权限与 onPermissionRequest 在第七十五轮已放行），
        // 所以这里只对二课站点伪装微信 UA，把分支掰到 ①。
        // 影响面已核对：全站 isWeiXin() 仅 2 处调用（首页扫码、另一处未启用），不吃亏。
        if (host == "gqhw.jxmediacenter.com") {
            settings.userAgentString = settings.userAgentString + " MicroMessenger/8.0.50.2700"
        }

        // 同步 RCE 会话 Cookie 进 WebView（原版 syncCookie 行为）
        // 2026-10-27 第十八轮：原来把整串 "a=1; b=2" 直接丢给 setCookie，CookieManager
        // 只认一条，于是存成畸形 Cookie —— 学生邮箱这类第三方站拿到畸形 Cookie 就渲染成白板。
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val campusHost = host.endsWith("nchu.edu.cn")
        com.lanhang.miuix.net.ApiClient.allCookies().forEach { (domain, cookie) ->
            setCookies(cm, "http://$domain", cookie)
            setCookies(cm, "https://$domain", cookie)
            // 2026-11-14 第七十四轮（用户：「修改密码、修改手机号两个功能全部失效，
            // 一直提示身份鉴权失败」）。
            //
            // 学校的统一身份认证摊在好几个子域上：`nginx-1.app.nchu.edu.cn` 发票，
            // `go.nchu.edu.cn` 是真正的 SSO 页面（改密码 / 改手机号都挂在这儿，见原版
            // SettingActivity 的 id=79 与 id=83）。而 CookieJar 收上来的这些票大多
            // **host-only**，只在自己那个主机名下有效 —— 跳到 go.nchu.edu.cn 时一个都
            // 带不过去，页面于是报「身份鉴权失败」。
            //
            // 校内这些站点本来就在同一套 SSO 后面，这里把票据补写一份到根域与 go 子域，
            // 让 *.nchu.edu.cn 共用。只在校内域生效，不会外溢到公网站点。
            if (domain.endsWith("nchu.edu.cn")) {
                setCookies(cm, "http://nchu.edu.cn", cookie)
                setCookies(cm, "https://nchu.edu.cn", cookie)
                setCookies(cm, "http://go.nchu.edu.cn", cookie)
                setCookies(cm, "https://go.nchu.edu.cn", cookie)
            }
        }
        // 工作模块预检时拿到的 Cookie：只给校内域名，同样逐条写
        if (campusHost) {
            intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotBlank() }?.let { value ->
                setCookies(cm, "http://$host", value)
                setCookies(cm, "https://$host", value)
            }
        }
        cm.flush()

        // 2026-11-14 第七十二轮：校外站点的登录票据常常是会话 Cookie，只活在内存里，
        // 本页一关就没了。这里先把上次存下的写回去，加载完再存一次（见 WebLoginStore）。
        // 学校域名的 Cookie 由 ApiClient 统一同步，不能被旧快照覆盖，所以只处理校外站。
        if (!campusHost) {
            WebLoginStore.restore(this, host)
            runCatching { cm.flush() }
        }

        // 伪装头只发给校内站点：带一整套 RCE 头（Token / DeviceID / Cookie）去敲学生邮箱、
        // 图书馆这类外部站点，会被当成非法客户端（空白的另一半原因）。
        headers = HashMap<String, String>().apply {
            if (campusHost) {
                intent.getStringExtra(EXTRA_APP_TOKEN)?.takeIf { it.isNotEmpty() }
                    ?.let { put("Token", it) }
                    ?: run { Session.ldapToken.takeIf { it.isNotEmpty() }?.let { put("Token", it) } }
                put("Version", Session.appVersion)
                put("DeviceID", Session.deviceId(this@OaWebViewActivity))
                put("NetType", netType)
                // 2026-10-27 第十八轮：**不再**把 RCE 的 Cookie 串塞进 Cookie 头。
                // 显式 Cookie 头会盖掉 WebView 自己的 CookieJar —— 学生邮箱（Roundcube）
                // 的 `roundcube_sessid` 每次都因此丢掉，静态资源拿不到同一个会话，
                // 页面渲成纯白（用户：「打开后一片白」）。会话 Cookie 已经在上面的
                // CookieManager 里逐条同步过，这里不需要再手工拼一遍。
            }
        }

        // 2026-11-14 第七十一轮：网页里的 <input type="file"> 需要 App 代弹系统选择器。
        // 「赣青二课」要上传头像与证明材料，缺了这一层点「上传」会毫无反应。
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER)
                    true
                } catch (_: Throwable) {
                    fileCallback = null
                    false
                }
            }

            /**
             * 网页要摄像头 / 麦克风时的授权回调。
             *
             * WebView 的默认实现是**一律拒绝**，所以不解这里的话，任何走 `getUserMedia`
             * 的页面（赣青二课的扫码签到、H5 录音）在 WebView 里都是「点了没反应 / 全黑」。
             * 只放行摄像头与麦克风这两类，其余（受保护媒体、持久化存储）一律拒。
             */
            // 2026-11-14 第七十七轮：把网页的 console 转发进 logcat。
            // 赣青二课这类 uni-app H5 出错时页面只是白屏/静默失效，从界面完全看不出原因，
            // 有这一行才能在 logcat 里直接读到 JS 异常与接口报错（过滤 tag = OaWebView）。
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                android.util.Log.i(
                    "OaWebView",
                    "[" + msg.messageLevel() + "] " + msg.message() + " @ " + msg.sourceId() + ":" + msg.lineNumber()
                )
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val wanted = request.resources.orEmpty().filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (wanted.isEmpty()) {
                    runCatching { request.deny() }
                    return
                }
                val need = wanted.mapNotNull {
                    if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA
                    else Manifest.permission.RECORD_AUDIO
                }.filter {
                    checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (need.isEmpty()) {
                    runCatching { request.grant(wanted.toTypedArray()) }
                        .onFailure { runCatching { request.deny() } }
                    return
                }
                // 系统权限还没给：先把这次请求挂住，拿到权限后在 onRequestPermissionsResult 里补 grant
                pendingMediaRequest?.let { runCatching { it.deny() } }
                pendingMediaRequest = request
                runCatching { requestPermissions(need.toTypedArray(), REQUEST_MEDIA_PERMISSION) }
                    .onFailure {
                        pendingMediaRequest = null
                        runCatching { request.deny() }
                    }
            }

            /**
             * 网页要定位权限时的回调。默认实现同样是「不回调 = 一直等待」，
             * 赣青二课的地图打卡与定位签到会卡在「正在获取位置」。
             * 本应用已经持有精确/粗略定位权限（私信发位置用到），所以直接放行，
             * 是否真正出位置由网页自身的定位精度决定。
             */
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                if (callback == null) return
                val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                callback.invoke(origin, granted, false)
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                // 回退期间的那次加载：不拦截（这是我们自己发起的 loadUrl）
                if (restoringHistory) return false
                // 非 http(s)（tel:、mailto:、weixin://、alipays://、intent://）：交系统，别丢给 WebView。
                // 2026-11-14 第七十四轮：原来只有一句 runCatching{ startActivity }，失败被吞成
                // 「点了毫无反应」，详见 openExternal 的注释。
                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                    openExternal(target, request.url)
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
                // 「加载完成」不等于「看得见」。学生邮箱（Roundcube 皮肤）就是典型：DOM 完整、
                // innerText 有字，可 `html/body` 的百分比高度在这里被量成 0 —— 元素一个像素都不占，
                // 屏幕纯白，用户以为又卡死了。所以这里同时量**正文长度**和**正文高度**：
                //   · 高度为 0 → 先补一条 `height:auto; min-height:100%`（只在这种页面注入），
                //     补完再量一次；还是 0 才算白板；
                //   · 白板就把「重新加载 / 用浏览器打开」露出来，绝不留一个没有任何出路的白屏。
                v.evaluateJavascript(BLANK_CHECK_JS) { raw ->
                    val text = raw?.trim('"').orEmpty()
                    val bits = text.substringBefore('|').toIntOrNull() ?: 1
                    val height = text.substringAfter('|', "1").toIntOrNull() ?: 1
                    bodyBits = bits
                    // 2026-11-14 第七十二轮：原来只看 body 高度，于是 uni-app / Vue 那类把内容
                    // 放进 fixed / absolute 容器的页面（例如赣青二课的「我的」）body 高恒为 0，
                    // 一进这个页面就弹「页面可能是空白的」—— 正文其实好好画着。
                    // 现在必须「既没有正文、也没有高度」才算白板。
                    pageTrouble = bits <= 2 && height <= 2
                    firstPaintDone = true
                    // 第七十二轮：校外站点留一份 Cookie（含会话 Cookie），下次进来直接接上
                    val loginHost = android.net.Uri.parse(url).host.orEmpty()
                    if (loginHost.isNotEmpty() && !loginHost.endsWith("nchu.edu.cn")) {
                        WebLoginStore.save(this@OaWebViewActivity, loginHost)
                    }
                    android.util.Log.i(
                        "OaWebView",
                        "finished url=" + hideQuery(url) + " bits=" + bits + " bodyH=" + height +
                            " title=" + v.title,
                    )
                }
            }

            override fun onReceivedError(
                v: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(v, request, error)
                if (request.isForMainFrame) {
                    android.util.Log.w("OaWebView", "主框架加载失败 code=" + error.errorCode)
                    pageTrouble = true
                }
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
                // 2026-11-14 第七十二轮：单页应用（uni-app / Vue 的 hash 路由）内部换页只改 # 后面
                // 那一段，主框架并没有重新导航。原来这里一律「替换栈顶」，于是栈里永远只有一条，
                // 「点进二级页再按返回」直接退出整个网页（用户：「系统中的返回手势触发时则直接
                // 退出所有页面」）。同主机同路径、只有 # 片段不同 = SPA 前进，入栈而不是替换。
                val prevUrl = pageStack.last()
                val prevUri = android.net.Uri.parse(prevUrl)
                val nowUri = android.net.Uri.parse(landed)
                val spaForward = prevUri.host == nowUri.host &&
                    prevUri.path == nowUri.path &&
                    prevUri.fragment != nowUri.fragment
                if (spaForward) {
                    pageStack.add(landed)
                    return
                }
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
