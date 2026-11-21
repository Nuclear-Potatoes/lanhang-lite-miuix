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

    /** 第七十七轮：留给扫码回调用的 WebView 引用。 */
    private var scanHostWebView: WebView? = null

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
         * 第九十三轮：网页调 LhScan.scan 起原生扫码前，向系统申请 CAMERA 运行时权限的请求码。
         *
         * 以前这里是空的 —— 全工程没有任何一处申请过 CAMERA（清单里只有声明），
         * [ScanQrActivity] 又假设「上游已经申请过」，拿不到权限就立刻 finish，
         * 于是网页收到 code=0、用户看到的就是「点扫码毫无反应」。
         */
        private const val REQUEST_SCAN_PERMISSION = 4004

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

    /**
     * 第九十六轮：预检时**现取**到的短期 token（工作应用的 appToken / 门户的 ldapToken）。
     *
     * 偶发鉴权失败的根因就在这里：`prepareFor()` 明明现取了新鲜 token，但旧代码只取了
     * [resolvedUrl]，token 被丢掉，[createWebView] 只能回落到 `Session.ldapToken` ——
     * 那个 JWT 约 2 小时过期且登录后不刷新，过期后网关回 `code=4000 客户端身份鉴权失败`。
     * 刚登录/刚刷新课表时它还是新的，所以表现为「偶发」。这里把现取 token 留住并交给 WebView。
     */
    private var resolvedToken: String? = null

    /** 同上：预检在落地域名下拿到的会话 Cookie，一并交给 WebView */
    private var resolvedCookie: String? = null

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
            // 第九十六轮：不预检落地地址时，token 也照样现取一次。
            // 改密码 / 改手机号 / 校园网账号页都走这条：以前只靠缓存的 Session.ldapToken，
            // 过期后就是「身份鉴权失败」。AccountSecurityScreen 虽已现取，但校园网账号页
            // （CampusNetScreen）只传了 appId 却没现取，这里统一兜住。
            if (!appId.isNullOrBlank()) {
                // 有 appId：等现取 token 完成再落地 url —— createWebView 是在 url 落地时
                // 才建 WebView 并读 headers 的，token 必须在此之前就位（赋值顺序不能反）。
                uiScope.launch {
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching { com.lanhang.miuix.net.RceApi.getAppToken(appId) }.getOrNull()
                    }
                    resolvedToken = fresh?.takeIf { it.isNotBlank() }
                    android.util.Log.i(
                        "OaWebView", "无预检现取token appId=$appId 成功=" + (resolvedToken != null),
                    )
                    resolvedUrl = url
                }
            } else {
                resolvedUrl = url
            }
        } else {
            uiScope.launch {
                val launch = withContext(Dispatchers.IO) {
                    runCatching { WorkRepository.prepareFor(preflightUrl, appId) }.getOrNull()
                }
                // 第九十六轮：把预检现取的 token / 落地 Cookie 留住。旧代码只取 url，
                // token 丢失 → WebView 回落过期的 ldapToken → 偶发「客户端身份鉴权失败」。
                resolvedToken = launch?.token?.takeIf { it.isNotBlank() }
                resolvedCookie = launch?.cookie?.takeIf { it.isNotBlank() }
                android.util.Log.i(
                    "OaWebView",
                    "预检完成 appId=$appId 现取token=" + (resolvedToken != null) +
                        " 落地Cookie=" + (resolvedCookie != null) +
                        " url=" + hideQuery(launch?.url ?: preflightUrl),
                )
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
    /**
     * 第七十七轮：接管二课网页的扫码入口。
     * 站点自己的 window.CP2.showScanQr 是个空壳（Promise 永不 resolve），
     * 这里换成「调 LhScan.scan() 起原生相机，扫到后 resolve 出 code=1 与 data.qrCode」。
     *
     * 第九十三轮加固（用户：「扫码依旧无法正常唤起」）：
     *  1. 原来只在 CP2 已存在时打一次补丁，`CP2 not ready` 就直接放弃，没有重试。
     *     二课是 uni-app 单页应用，CP2 / uni 可能首屏之后才挂上 window，后续路由跳转
     *     也不会再触发 onPageFinished —— 所以改成「补丁函数 + 250ms 轮询补挂（30 秒后停）」。
     *  2. 顺带把 `uni.scanCode` 也挂到同一实现，覆盖站点可能走的另一条调用路径。
     *  3. 补丁幂等：每次都比对函数引用，站点把 showScanQr 换回自己的 stub 也会被再次接管。
     */
    private val SCAN_BRIDGE_JS = """
        (function () {
            if (window.__lhScanHooked) {
                try { window.__lhScanPatch && window.__lhScanPatch(); } catch (e) { }
                return;
            }
            window.__lhScanHooked = true;

            function callNative() {
                return new Promise(function (resolve) {
                    window.__lhScanResolve = resolve;
                    try {
                        LhScan.scan();
                    } catch (e) {
                        console.log('LhScan: call failed ' + e);
                        resolve({ code: 0 });
                    }
                });
            }

            window.__lhScanPatch = function () {
                try {
                    var cp = window.CP2;
                    if (cp && cp.showScanQr !== window.__lhShowScanQrImpl) {
                        window.__lhShowScanQrImpl = function () {
                            console.log('LhScan: intercept showScanQr');
                            return callNative();
                        };
                        cp.showScanQr = window.__lhShowScanQrImpl;
                        console.log('LhScan: showScanQr patched');
                    }
                } catch (e) {
                    console.log('LhScan: patch CP2 failed ' + e);
                }
                try {
                    if (window.uni && typeof window.uni.scanCode === 'function' &&
                        window.uni.scanCode !== window.__lhUniScanCode) {
                        window.__lhUniScanCode = function (opts) {
                            opts = opts || {};
                            callNative().then(function (r) {
                                try {
                                    if (r && r.code === 1 && r.data && r.data.qrCode) {
                                        if (opts.success) opts.success({ result: r.data.qrCode, scanType: 'QR_CODE' });
                                    } else {
                                        if (opts.fail) opts.fail({ errMsg: 'scanCode:fail cancel' });
                                    }
                                    if (opts.complete) opts.complete({});
                                } catch (e2) { }
                            });
                        };
                        window.uni.scanCode = window.__lhUniScanCode;
                        console.log('LhScan: uni.scanCode patched');
                    }
                } catch (e) {
                    console.log('LhScan: patch uni failed ' + e);
                }
            };

            window.__lhScanPatch();
            console.log('LhScan: bridge installed CP2=' + (typeof window.CP2) +
                ' uni=' + (typeof window.uni) + ' native=' + (typeof window.LhScan));
            // 站点是 uni-app 单页应用：CP2 / uni 可能首屏之后才挂上 window，
            // 后续路由跳转也不会再触发 onPageFinished，所以自己轮询补挂（30 秒后停）。
            var tries = 0;
            var timer = setInterval(function () {
                try { window.__lhScanPatch(); } catch (e) { }
                if (++tries > 120) clearInterval(timer);
            }, 250);
        })()
    """.trimIndent()
    /**
     * 第七十七轮：赣青二课网页的兼容补丁（站点自身有两个 bug，都在 H5 侧兜住）。
     *
     * 一、「我的活动 → 更多」整页白屏。
     *    该页 onLoad 第一句就写 this 上的 draftsItem.handleSearchData("")，
     *    而 draftsItem 是「草稿箱」这个 tab 里的 searchItem 组件，默认 tab 下根本没渲染，
     *    于是抛 TypeError、页面挂载中断、整页空白。这里给每个组件实例的 refs 装一个访问器：
     *    访问 draftsItem 时先返回打桩对象，真组件挂载后会 set 覆盖，行为回到正常。
     *    桩里的 handleSearchData 不能是空实现 —— 它正是首屏加载的触发器，
     *    所以延后 300ms 转发给页面实例自己的同名方法（beforeCreate 时 methods 还没初始化）。
     *
     * 二、onLoad / onShow 抛异常时 uni-app 会把整页标记为失败。
     *    给 Vue 的 errorHandler 挂一层「先记日志再交给原处理器」，避免一条与渲染无关的
     *    异常把页面直接打死。Vue 构造器不从 window 暴露（打包内联），只能从
     *    getApp() 拿到的根实例往上取 constructor。
     */
    /**
     * 一卡通「充值」的微信支付兜底（2026-11-15 第八十二轮）。
     *
     * 用户反馈：「校园一卡通点击充值时无法拉起微信 app」。抓页面脚本确认了根因，不是我们的
     * Intent 有问题，而是这条路**根本不在浏览器里走**：
     *
     * `Chongzhi.js` 里确认充值只有一条微信分支 ——
     * ```
     * weixinpay() → wechatRecharge() → wechatOrder() → onBridgeReady()
     *            → WeixinJSBridge.invoke('getBrandWCPayRequest', {appId,timeStamp,nonceStr,package,signType,paySign})
     * ```
     * 这是**微信内 JSAPI 支付**：appId/签名都由学校公众号下单生成，`package` 是 JSAPI 的
     * prepay_id，只有微信内置浏览器里的 `WeixinJSBridge` 能拿它发起付款。整个 `Chongzhi.js`
     * 里既没有 `weixin://` 也没有 mweb 兜底（已全文检索确认）。
     *
     * 我们的 WebView 里 `WeixinJSBridge` 是 `undefined`，于是 `WeixinJSBridge.invoke(...)`
     * 直接抛 `ReferenceError`，异常没人接 —— 用户看到的就是「点确认充值毫无反应」。
     *
     * 第三方 App 无法伪造这个桥（伪造就是假支付回执，绝不能做），也不能把 JSAPI 的
     * prepay_id 塞进 `weixin://wap/pay`（那只认 MWEB 单）。所以这里做的是「不再静默失败」：
     * 补一个只拦 `getBrandWCPayRequest` 的桥，把用户引到真正能付款的地方（微信里打开本页）。
     */
    private val WX_BRIDGE_FALLBACK_JS = """
        (function () {
            console.log('LhWx bridge=' + (typeof window.WeixinJSBridge) +
                ' pay=' + (typeof window.LhPay) + ' hooked=' + !!window.__lhWxBridgeHooked);
            if (window.__lhWxBridgeHooked) return;
            window.__lhWxBridgeHooked = true;
            if (typeof window.WeixinJSBridge !== 'undefined') return;
            window.WeixinJSBridge = {
                invoke: function (name, params, cb) {
                    console.log('LhWx invoke ' + name);
                    if (name === 'getBrandWCPayRequest') {
                        try {
                            if (window.LhPay) window.LhPay.needWeixin(String(location.href));
                        } catch (e) { }
                    }
                },
                call: function () { },
                on: function () { }
            };
        })();
    """.trimIndent()

    private val GQHW_COMPAT_JS = """
(function () {
            if (window.__lhCompatDone) return;
            window.__lhCompatDone = true;
            function getVue() {
                if (window.Vue && window.Vue.config) return window.Vue;
                try {
                    var app = window.getApp && window.getApp();
                    var vm = app && app['${'$'}vm'];
                    var C = vm && vm.constructor;
                    if (C && C.config) return C;
                } catch (e) { }
                return null;
            }
            var tries = 0;
            function patch() {
                var V = getVue();
                if (!V || !V.config) { if (tries++ < 40) setTimeout(patch, 200); return; }
                var prev = V.config.errorHandler;
                V.config.errorHandler = function (err, vm, info) {
                    try { console.log('LhCompat swallow: ' + err + ' | ' + info); } catch (e) { }
                    if (prev) { try { prev(err, vm, info); } catch (e) { } }
                };
                if (!V.__lhDraftsMixin) {
                    V.__lhDraftsMixin = true;
                    V.mixin({
                        beforeCreate: function () {
                            var self = this;
                            try {
                                if (!self['${'$'}refs']) return;
                                var stub = {
                                    handleSearchData: function (v) {
                                        setTimeout(function () {
                                            try {
                                                if (typeof self.handleSearchData === 'function') self.handleSearchData(v || '');
                                            } catch (e) { console.log('LhCompat load failed ' + e); }
                                        }, 300);
                                    },
                                    handleSearchClearData: function () { },
                                    handleInputData: function () { }
                                };
                                Object.defineProperty(self['${'$'}refs'], 'draftsItem', {
                                    configurable: true,
                                    get: function () { return self.__lhDrafts || stub; },
                                    set: function (v) { self.__lhDrafts = v; }
                                });
                            } catch (e) { }
                        }
                    });
                }
                console.log('LhCompat ready');
            }
            patch();

            // ============ 第九十一轮：通用「列表容器高度塌陷」修复 ============
            // 第七十八轮只修了「我的活动」页（硬编码 .list-bg），mounted 钩子还挑组件
            // （if (!this.seachList && !this.listData) return;），排行榜 / 获得成绩页
            // 用的是另一套 class，压根不在修复范围里 —— 用户图二里表头在、下面全空就是这么来的。
            // 改成不认 class、按几何特征找：自身被量成 0 高、scrollHeight 却有真实内容、
            // 且是滚动容器（uni-app 的 .uni-scroll-view 一律 overflow:hidden）就补高到视口。
            var lhDiagAt = 0;
            function lhDiag(tag, n) {
                try {
                    var now = Date.now();
                    if (now - lhDiagAt < 4000) return;
                    lhDiagAt = now;
                    console.log('LhCompat ' + tag + ' fixed=' + n +
                        ' hash=' + (location.hash || '') +
                        ' listBg=' + document.querySelectorAll('.list-bg').length +
                        ' scrollView=' + document.querySelectorAll('.uni-scroll-view').length +
                        ' bodyH=' + Math.round(document.body.getBoundingClientRect().height) +
                        ' bodyBits=' + (document.body.innerText || '').trim().length);
                } catch (e) { }
            }

            // 诊断用：把几个疑似列表容器的真实尺寸 / 子节点数打出来（logcat 搜 LhCompat probe）。
            function lhProbe() {
                try {
                    var sels = ['.list-bg', '[class*="rank"]', '[class*="list"]', '.uni-scroll-view'];
                    for (var k = 0; k < sels.length; k++) {
                        var ns = document.querySelectorAll(sels[k]);
                        for (var m = 0; m < Math.min(2, ns.length); m++) {
                            var n = ns[m];
                            console.log('LhCompat probe ' + sels[k] + ' #' + m +
                                ' class=' + String(n.className || '').slice(0, 70) +
                                ' kids=' + n.children.length +
                                ' h=' + Math.round(n.getBoundingClientRect().height) +
                                ' sh=' + n.scrollHeight + ' ch=' + n.clientHeight +
                                ' txt=' + (n.innerText || '').trim().length);
                        }
                    }
                } catch (e) { }
            }

            function lhFixHeights() {
                var fixed = 0;
                try {
                    var vh = window.innerHeight || 800;

                    // A. 老路径保留：.list-bg 往上爬到第一个 overflow:hidden 的祖先（已实测有效）。
                    var first = document.querySelector('.list-bg');
                    if (first) {
                        var el = first.parentElement, guard = 0;
                        while (el && guard < 12) {
                            var csA = getComputedStyle(el);
                            if (String(csA.overflow).indexOf('hidden') >= 0) {
                                var boxA = el.getBoundingClientRect();
                                if (Math.round(boxA.height) <= 40) {
                                    var wantA = Math.max(240, vh - Math.round(boxA.top) - 6);
                                    el.style.height = wantA + 'px';
                                    fixed++;
                                }
                                break;
                            }
                            el = el.parentElement;
                            guard++;
                        }
                    }

                    // B. 通用路径：任何「明明有内容却被压成 0 高」的容器。
                    var all = document.body ? document.body.getElementsByTagName('*') : [];
                    for (var i = 0; i < all.length && fixed < 8; i++) {
                        var n = all[i], st, r;
                        try { st = getComputedStyle(n); r = n.getBoundingClientRect(); } catch (e) { continue; }
                        if (!st || st.display === 'none' || st.visibility === 'hidden') continue;
                        if (Math.round(r.height) > 40) continue;
                        if (n.scrollHeight < 120) continue;
                        if (r.top > vh || r.bottom < -40) continue;
                        var ov = String(st.overflow || '') + String(st.overflowY || '') + String(st.overflowX || '');
                        var scrollish = ov.indexOf('hidden') >= 0 || ov.indexOf('auto') >= 0 || ov.indexOf('scroll') >= 0;
                        var cn = String(n.className || '');
                        var uniish = /uni-|list|rank|content|scroll|table|wrap|item|card/i.test(cn);
                        if (!scrollish && !uniish) continue;
                        var want = Math.max(240, vh - Math.round(r.top) - 6);
                        n.style.height = want + 'px';
                        n.style.minHeight = want + 'px';
                        fixed++;
                    }

                    // C. 行高塌陷：v-for 已把条目渲染进 DOM，但每行被算成 0 高
                    //    （排行榜表头正常、下面一片空白就是这种）。
                    var rows = document.querySelectorAll('.list-bg, [class*="rank-item"], [class*="list-item"], [class*="table-row"], [class*="row-item"]');
                    for (var j = 0; j < rows.length; j++) {
                        var row = rows[j], rr;
                        try { rr = row.getBoundingClientRect(); } catch (e) { continue; }
                        if (Math.round(rr.height) > 4) continue;
                        if ((row.innerText || '').trim().length < 1 && row.children.length < 1) continue;
                        row.style.minHeight = '56px';
                        fixed++;
                    }
                } catch (e) { }
                if (fixed > 0) lhDiag('fix', fixed);
                return fixed;
            }

            function lhKick(delay) { setTimeout(lhFixHeights, delay || 0); }
            document.addEventListener('DOMContentLoaded', function () { lhKick(120); lhKick(600); lhKick(1600); });
            window.addEventListener('load', function () { lhKick(200); lhKick(1000); lhKick(2400); });
            window.addEventListener('hashchange', function () {
                lhKick(100); lhKick(500); lhKick(1400);
                setTimeout(lhProbe, 1200);
            });
            window.addEventListener('popstate', function () { lhKick(100); lhKick(600); });

            var lhApp = getApp();
            var lhVue = lhApp && lhApp['${'$'}vm'] && lhApp['${'$'}vm'].constructor;
            if (lhVue) {
                lhVue.mixin({
                    mounted: function () { lhKick(400); lhKick(1200); lhKick(2600); },
                    created: function () {
                        try {
                            var opts = this['${'$'}options'] || {};
                            if (opts.__lhShowWrapped) return;
                            if (typeof opts.onShow !== 'function') return;
                            opts.__lhShowWrapped = true;
                            var orig = opts.onShow;
                            opts.onShow = function () {
                                var r = orig.apply(this, arguments);
                                lhKick(120); lhKick(500); lhKick(1500);
                                return r;
                            };
                        } catch (e) { }
                    }
                });
            }

            if (!window.__lhFixTimer) {
                window.__lhFixTimer = setInterval(function () {
                    if (document.hidden) return;
                    lhFixHeights();
                }, 700);
            }
            console.log('LhCompat init ' + (location.href || '').slice(0, 160));
            setTimeout(lhProbe, 2600);
        })()
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
        // 第八十二轮：这类跳转以前完全静默，出问题只能靠猜。打一行日志，后面查「拉不起来」直接看它。
        android.util.Log.i("OaWebView", "openExternal " + hideQuery(raw))

        fun fail() {
            android.util.Log.w("OaWebView", "openExternal failed: " + hideQuery(raw))
            android.widget.Toast.makeText(
                this,
                getString(R.string.oa_open_app_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        /** 按浏览器的方式发起跳转：带 BROWSABLE，微信/支付宝的支付页就认这个 category */
        fun view(u: Uri): Boolean {
            val it = Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)
            return runCatching { startActivity(it) }
                .onFailure { e -> android.util.Log.w("OaWebView", "startActivity failed: " + e) }
                .isSuccess
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
                if (view(Uri.parse("$scheme://"))) return
            }
            fail()
            return
        }

        if (view(uri)) return
        fail()
    }
    private fun finishNow() {
        cancelBackFallback()
        restoringHistory = false
        if (!isFinishing && !isDestroyed) finish()
    }

    /**
     * 第八十二轮：一卡通充值点了没反应时给出一条真能走通的路。
     *
     * 微信 JSAPI 支付只认微信内置浏览器，第三方 App 不可能代它完成，所以这里不假装成功：
     * 把当前充值页地址画成二维码，用微信「扫一扫」即可在微信里打开同一页付款
     *（二维码里的地址带学校网关下发的 token，过去不用重新登录）；也留了「复制链接」兜底。
     * 第八十三轮按用户要求从「复制链接」升级成扫码。
     */
    internal fun showWeixinPayHint(pageUrl: String) {
        if (isFinishing || isDestroyed) return
        val link = pageUrl.ifBlank { webView?.url.orEmpty() }
        // 桥是全局挂的，这里按**当前页面**的域名收口：只认一卡通站点，别的站点调进来直接忽略
        if (!android.net.Uri.parse(link).host.orEmpty().contains("ykt")) return
        com.lanhang.miuix.ui.miuix.showRechargeQr(this, link)
    }

    /** 相机权限申请回来之后，是否要继续起扫码页（第九十三轮） */
    private var pendingScanAfterPermission = false

    /**
     * 第七十七轮：网页里的 LhScan.scan 落到这里，起原生扫码页。
     * 必须在主线程开 Activity，JavascriptInterface 的回调线程不是主线程。
     *
     * 第九十三轮修正：**先要运行时权限**。之前这里直接 startActivityForResult，
     * 而 CAMERA 只有清单声明、从来没有运行时申请；[ScanQrActivity] 拿不到权限就
     * 立刻 finish 并把 code=0 回给网页，用户看到的就是「点了没反应」。
     */
    fun startScanForWeb() {
        runOnUiThread {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchScanPage()
                return@runOnUiThread
            }
            android.util.Log.i("OaWebView", "scan: 相机权限未授予，先申请")
            pendingScanAfterPermission = true
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_SCAN_PERMISSION)
            }.onFailure {
                android.util.Log.w("OaWebView", "scan: 权限申请发起失败", it)
                pendingScanAfterPermission = false
                toast("无法申请相机权限，请在系统设置里允许「蓝航」使用相机")
                resolveScanCancelled()
            }
        }
    }

    /** 起原生扫码页；起不来就把「取消」回给网页，不能让它一直挂着。 */
    private fun launchScanPage() {
        runCatching { startActivityForResult(ScanQrActivity.intent(this), REQUEST_SCAN_FOR_WEB) }
            .onFailure {
                android.util.Log.w("OaWebView", "scan activity failed", it)
                toast("扫码页启动失败：" + (it.message ?: it.javaClass.simpleName))
                resolveScanCancelled()
            }
    }

    /** 把「用户取消 / 没扫到」回给网页那个挂起的 Promise。 */
    private fun resolveScanCancelled() {
        val web = scanHostWebView ?: return
        runOnUiThread {
            runCatching {
                web.evaluateJavascript("window.__lhScanResolve && window.__lhScanResolve({code:0});", null)
            }
        }
    }

    private fun toast(message: String) {
        runCatching {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 第七十七轮：把扫码结果按站点约定的结构回调给网页。
     * 站点判定是 code 等于 1 且 data.qrCode 有值，结构必须严格对上；
     * 取消时回 code=0，让网页自己决定提示什么，不会一直挂着。
     */
    private fun deliverScanResult(resultCode: Int, data: Intent?) {
        val web = scanHostWebView ?: return
        val text = if (resultCode == ScanQrActivity.RESULT_SCANNED) {
            data?.getStringExtra(ScanQrActivity.EXTRA_RESULT).orEmpty()
        } else {
            ""
        }
        val js = if (text.isEmpty()) {
            "window.__lhScanResolve && window.__lhScanResolve({code:0});"
        } else {
            val esc = text.replace("\\", "\\\\").replace("'", "\\'")
            "window.__lhScanResolve && window.__lhScanResolve({code:1,data:{qrCode:'" + esc + "'}});"
        }
        runOnUiThread { runCatching { web.evaluateJavascript(js, null) } }
    }
    /**
     * 把系统文件选择器的结果回填给网页。
     *
     * 多选（`<input multiple>`）要从 [Intent.getClipData] 里逐个取，单选取 [Intent.getData]；
     * 用户按返回取消时必须回 `null`，否则那个 `<input>` 会永远卡在「等待选择」。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // 第七十七轮：二课扫码页回来，把结果交还给网页那个挂起的 Promise。
        if (requestCode == REQUEST_SCAN_FOR_WEB) {
            deliverScanResult(resultCode, data)
            return
        }
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
        // 第九十三轮：网页扫码入口专用的相机权限回执
        if (requestCode == REQUEST_SCAN_PERMISSION) {
            if (!pendingScanAfterPermission) return
            pendingScanAfterPermission = false
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            android.util.Log.i("OaWebView", "scan: 相机权限结果 granted=" + granted)
            if (granted) {
                launchScanPage()
            } else {
                toast("需要相机权限才能扫码，请在系统设置里允许「蓝航」使用相机")
                resolveScanCancelled()
            }
            return
        }
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

        // 第七十七轮：把扫码能力接到网页上。
        // 只对赣青二课注入，其它站点（校内 OA、邮箱）一律不暴露这个 JS 接口。
        // 网页侧用 window.CP2.showScanQr 调我们的 LhScan.scan，见 SCAN_BRIDGE_JS。
        if (host.endsWith("jxmediacenter.com")) {
            scanHostWebView = view
            view.addJavascriptInterface(ScanBridge(this), "LhScan")
        }

        // 第八十二轮：一卡通充值要微信 JSAPI 桥，WebView 里没有 —— 桥缺失时由页面回调到这里，
        // 弹窗把用户引到微信里打开充值页（详见 WX_BRIDGE_FALLBACK_JS 的注释）。
        //
        // 这里**不能**按入口域名判断：工作模块预检出来的是网关 `ottapp.api.nchu.edu.cn`，
        // 真正的充值页是网关 302 过去的 `ykt-wechat.ykt.nchu.edu.cn`（实测抓到的初始 host
        // 就是 ottapp.api，按 host 判断永远注册不上，桥等于不存在）。所以统一挂上，
        // 真正的域名校验放在 showWeixinPayHint 里按当前页面地址做。
        view.addJavascriptInterface(PayBridge(this), "LhPay")
        android.util.Log.i("OaWebView", "LhPay 已注册 host=" + host)

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
        // 2026-11-14 第七十七轮补正（用户：「点击赣青二课的按钮依旧无法扫码」）：
        // 一度试过对二课站点伪装微信 UA，把扫码按钮掰到站点自带的 /pages/scan/scan（html5-qrcode）。
        // 实测打脸 —— 伪装后整页重载、Vue 根本没挂载（body 里只剩 <noscript> 与 script，
        // #app 消失、videos=0 canvas=0），比原来更糟。
        // 而灌进页面的诊断脚本读出来：`window.CP2` 是存在的一整套桥对象，
        // showScanQr / selectMedia / getLocation / wxSign / upload … 都在，
        // 所以按钮本来就不会抛 TypeError —— 真相是它返回的 Promise 永远不 resolve
        //（真正的扫码动作要青新闻 App 那边配合）。修法见 ScanBridge：由我们接管 showScanQr。

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
        // 第九十六轮：优先用预检协程里留住的落地 Cookie（现取），其次才是启动时 intent 带的旧快照。
        if (campusHost) {
            (resolvedCookie ?: intent.getStringExtra(EXTRA_COOKIE))
                ?.takeIf { it.isNotBlank() }?.let { value ->
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
                // 第九十六轮：优先级 预检现取token > intent 带的token > 缓存 ldapToken。
                // 缓存那个约 2 小时过期且不自动刷新，是「偶发身份鉴权失败」的元凶。
                val freshToken = resolvedToken
                    ?: intent.getStringExtra(EXTRA_APP_TOKEN)?.takeIf { it.isNotEmpty() }
                    ?: Session.ldapToken.takeIf { it.isNotEmpty() }
                freshToken?.let { put("Token", it) }
                android.util.Log.i(
                    "OaWebView",
                    "Token 来源=" + when {
                        resolvedToken != null -> "预检现取"
                        !intent.getStringExtra(EXTRA_APP_TOKEN).isNullOrBlank() -> "intent传入"
                        !Session.ldapToken.isNullOrBlank() -> "缓存ldap(可能过期)"
                        else -> "无"
                    },
                )
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
                    // 第七十七轮：二课站点装扫码桥。
                    // 站点自己的 CP2.showScanQr 只返回一个永不 resolve 的 Promise，
                    // 换掉它，用户点右上角扫码才会有反应（细节见 SCAN_BRIDGE_JS）。
                    if (url.contains("jxmediacenter.com")) {
                        v.evaluateJavascript(SCAN_BRIDGE_JS) { }
                        // 临时诊断（「我的活动」白屏定位用，验完删）
                        v.evaluateJavascript(GQHW_COMPAT_JS) { }
                    }
                    // 一卡通充值：微信 JSAPI 桥缺失时的兜底（详见 WX_BRIDGE_FALLBACK_JS）
                    if (url.contains("ykt-wechat")) {
                        v.evaluateJavascript(WX_BRIDGE_FALLBACK_JS) { }
                    }
                }
            }

            override fun onReceivedError(
                v: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(v, request, error)
                if (!request.isForMainFrame) return
                // 第八十二轮：3xx 跳到 weixin:// 这类地址时 WebView 不会走 shouldOverrideUrlLoading，
                // 只在主框架报一个 ERR_UNKNOWN_URL_SCHEME。以前这里只当成「白板」，支付链接就此丢掉。
                // 现在把那次失败的地址捡回来交给系统，微信/支付宝才有机会被拉起。
                if (error.errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) {
                    val failed = request.url?.toString().orEmpty()
                    val scheme = request.url?.scheme.orEmpty().lowercase()
                    val ignorable = scheme.isEmpty() ||
                        scheme == "about" || scheme == "data" ||
                        scheme == "blob" || scheme == "javascript" || scheme == "file"
                    android.util.Log.w(
                        "OaWebView",
                        "unknown scheme code=" + error.errorCode + " scheme=" + scheme,
                    )
                    if (!ignorable && failed.isNotEmpty()) {
                        openExternal(failed, request.url)
                        return
                    }
                }
                android.util.Log.w("OaWebView", "主框架加载失败 code=" + error.errorCode)
                pageTrouble = true
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