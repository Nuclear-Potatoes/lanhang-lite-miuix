package com.lanhang.miuix.ui

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface

/**
 * 2026-11-14 第七十七轮：把「赣青二课」的扫码按钮接到原生相机上。
 *
 * 网页那边（`pages-index` 里的 `handleSenCode`）在非微信环境下是这么调的：
 * ```js
 * window.CP2.showScanQr({ needResult: 1 }).then(function (t) {
 *     if (1 == t.code) this.handleSignAdd(t.data.qrCode)
 * })
 * ```
 * `window.CP2` 在青新闻 App 里是原生注入的真桥，在我们的 WebView 里虽然同名存在
 * （站点自己铺了一份 stub），但 `showScanQr` 返回的 Promise 永不 resolve，
 * 用户点下去毫无反应。所以 [OaWebViewActivity] 会在页面加载完成后用 JS 把
 * `CP2.showScanQr` 换成「调这个对象 → 起原生扫码 → resolve」的版本。
 *
 * 只暴露一个方法，且只在二课域名下注入，不做通用 JS 桥。
 */
class ScanBridge(private val host: OaWebViewActivity) {

    /**
     * 网页侧调用入口：启动 [ScanQrActivity]，扫到结果后由 Activity 回调，
     * 再经 `evaluateJavascript` 把 `{code:1, data:{qrCode:...}}` 交还给网页。
     */
    @JavascriptInterface
    fun scan() {
        host.startScanForWeb()
    }

    /** 网页侧的日志出口，方便在 logcat 里看到注入是否生效、回调是否到达。 */
    @JavascriptInterface
    fun log(message: String?) {
        android.util.Log.i("OaWebView", "ScanBridge: " + (message ?: ""))
    }
}

/** 供 [OaWebViewActivity] 复用的请求码常量（放在这里避免再改 Activity 的 companion）。 */
internal const val REQUEST_SCAN_FOR_WEB = 4003

/** 活动里转发扫码结果时用的空 Intent 占位，避免各处重复构造。 */
internal fun emptyScanIntent(): Intent = Intent().apply { setAction(Activity.RESULT_OK.toString()) }
