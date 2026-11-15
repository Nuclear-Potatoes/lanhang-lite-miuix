package com.lanhang.miuix.ui

import android.webkit.JavascriptInterface

/**
 * 一卡通充值页 → 原生的最小桥。
 *
 * 页面里的 `Chongzhi.js` 只会走微信内 JSAPI 支付（`WeixinJSBridge.invoke('getBrandWCPayRequest')`），
 * 而 WebView 里没有这个桥，点击就是静默失败。`OaWebViewActivity.WX_BRIDGE_FALLBACK_JS` 补了一个
 * 只拦 `getBrandWCPayRequest` 的假桥，拦到就通过本类回调原生，弹窗告诉用户怎么真正付掉这笔钱。
 *
 * 注意：`@JavascriptInterface` 的方法跑在 WebView 的 JavaBridge 线程，必须自己切回主线程；
 * 也绝不能在这里伪造支付成功回执。
 */
class PayBridge(private val host: OaWebViewActivity) {

    @JavascriptInterface
    fun needWeixin(pageUrl: String?) {
        android.util.Log.i("OaWebView", "PayBridge.needWeixin " + pageUrl)
        host.runOnUiThread { host.showWeixinPayHint(pageUrl.orEmpty()) }
    }
}
