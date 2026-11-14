package com.lanhang.miuix.work

/**
 * 「工作」Tab 里的原生功能页（不是服务端 H5 应用）。
 *
 * 2026-10-27 第十六轮：用户要求把原项目的**校园网一键认证**和**图书馆座位预约**搬进来，
 * 并且「ui 界面必须严格遵守 miuix 设计规范」。所以这两个功能不再是 `OaWebViewActivity`
 * 里的一张网页，而是**本 App 自己的 Compose 页面**，走和「我的」Tab 一样的页面栈：
 * 推入/返回共用外壳那一套顶栏收放（[com.lanhang.miuix.ui.shell.BarAnimation]），
 * 不新开 Activity、不写第二套顶栏。
 *
 * [depth] 只用来告诉 [com.lanhang.miuix.ui.miux.MiuiNavStack] 这次是推入还是返回。
 *
 * 2026-10-27 第十八轮：用户反馈「图书馆座位功能中我的预约二级菜单过渡动画方向与其他不一致」。
 * 根因是 [LIBRARY_BOOKINGS] 以前和 [LIBRARY_SEAT] 同为 1 —— 深度相同，[MiuiNavStack] 判不出
 * 「这是往下推一层」，于是按「平级」处理，动画方向就反了。现在每往下一层就 +1。
 */
enum class WorkRoute(val depth: Int) {
    /** 工作首页（办事大厅目录 + 常用功能） */
    ROOT(0),

    /** 校园网登录 */
    CAMPUS_NET(1),

    /** 图书馆座位预约（选座） */
    LIBRARY_SEAT(1),

    /** 我的座位预约 */
    LIBRARY_BOOKINGS(2),

    /** 座位签到码（从我的预约进） */
    LIBRARY_SIGN_CODE(3),
}

/** 「校园网登录」这条常用功能由 App 自己实现，服务端目录里没有它 */
const val NATIVE_CAMPUS_NET_ID = "native:campus_net"

/** 服务端目录里的这个应用被本机原生页接管（用户要求「替换掉座位预约的功能」） */
const val SEAT_APP_MARK = "座位预约"

/**
 * 「赣青二课」（江西省高校第二课堂成绩单平台）在工作页里的条目 id。
 *
 * 2026-11-14 第七十一轮：用户要求「把赣青二课接入工作功能中」。逆向青新闻 3.3.3
 * （`com.jxnews.jxttn`，19 个 dex、3030 个业务类）后确认它**不在客户端里** ——
 * 全包搜「赣青二课 / 赣青 / 二课 / 第二课堂 / 素质拓展」零命中，也没有对应类和域名，
 * 它是服务端菜单下发的外部 H5。真实地址由用户给出：`https://gqhw.jxmediacenter.com/#/`
 * （页面标题就叫「第二课堂」，Vue + uni-app 打的单页应用，静态资源还挂在
 * `pic.tt.jxnews.com.cn` 上，确认是青新闻体系内的二课系统）。
 *
 * 所以这里**不做原生页**：那套系统有 118 条前端路由（活动、实践、志愿、成绩单、申报、
 * 答题、竞赛、组织、校友圈……）且全程要登录态，重写成原生页既不可能也没必要。
 * 工作页只负责给出入口，点开由 [com.lanhang.miuix.ui.OaWebViewActivity] 承载。
 *
 * 这个 id 用在两处：进顶部「常用功能」槽位，以及跳过学校 OA 网关预检（见 WorkPage.openApp）。
 */
const val GQHW_APP_ID = "native:gqhw"