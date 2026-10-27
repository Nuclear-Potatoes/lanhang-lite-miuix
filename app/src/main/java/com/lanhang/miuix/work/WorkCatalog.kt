package com.lanhang.miuix.work

import com.lanhang.miuix.net.WorkApp

/**
 * 「工作」页的功能清单（本地化）。
 *
 * 2026-10-27 第二十六轮：用户要求「将工作页面的功能区本地化，不要联网获取工作中的功能列表」。
 * 以前这张清单来自学校接口 `POST /apps/all`，于是每次进「工作」都要先等一次网络：
 * 断网/弱网就是一屏转圈，连「校园网登录」「图书馆座位预约」这两个**本机原生**功能都点不进去。
 *
 * 现在清单固化在下面（内容 = 2026-10-27 抓取 `POST /apps/all` 的快照，id 与跳转地址原样保留，
 * 顺序就是服务端给的顺序）。点开某个应用仍然走原来的链路（网关预检 + WebView），
 * 也就是说**列表不联网，功能本身照旧联网**；换季/学校新增应用时改这张表即可。
 *
 * 2026-10-27 第二十七轮：
 *  - 「校园网一键认证」删掉：原生「校园网登录」已经完全取代它；
 *  - 「校园网账号设置」搬进「校园网登录 → 网络认证自助服务」区域（地址与 appId 见 CampusNetScreen）。
 *
 * 刻意没有收录下面两类：
 *  - 「最近使用」：那是服务端按你最近点过的应用动态生成的，写死就永远是同一批，反而误导；
 *    顶部「常用功能」已经承担了这个角色。
 *  - 「系统应用」：整组只有「通知服务」一个无效功能（点开是空白），历史上已屏蔽。
 */
object WorkCatalog {

    /**
     * 分类 → 应用。`null` 分类表示服务端没归类的零散应用（本地清单里没有，保留类型以防将来补）。
     * 与 `WorkDirectory.sections()` 的输出结构一致，[WorkPage] 不需要任何改动就能吃下。
     */
    val sections: List<Pair<String?, List<WorkApp>>> = listOf(
        "教学办公" to listOf(
            WorkApp(
                id = "15em3jfgTGwt_Za7eZufsQ",
                name = "南航要闻",
                description = "南昌航空大学新闻",
                homePageUrl = "http://www.nchu.edu.cn/xwzx/chyw",
            ),
            WorkApp(
                id = "xEBpkP_NSPogmsCQ31VTQ0",
                name = "掌上学工",
                description = "学生工作系统",
                homePageUrl = "http://go.nchu.edu.cn/?id=69",
            ),
            WorkApp(
                id = "0TM-Vhi1RcUij_qOvYj-k0",
                name = "宿舍门禁",
                description = "人脸识别门禁系统手机端",
                homePageUrl = "http://go.nchu.edu.cn/?id=43",
            ),
            WorkApp(
                id = "z3SbZ9E3TCkljD3riqTyjE",
                name = "门禁照片更新",
                description = "门禁照片更新",
                homePageUrl = "http://m.app.nchu.edu.cn/renlianmenjin.html",
            ),
            WorkApp(
                id = "T3rwRtF_SrMp27ZALKT78g",
                name = "智慧教务（本科生）",
                description = "新教务系统",
                homePageUrl = "http://Go.nchu.edu.cn/?id=84",
            ),
            WorkApp(
                id = "bPmf2EoLQlQv8D4BXiJPZY",
                name = "校公共机房上机预约",
                description = "校公共机房使用预约",
                homePageUrl = "http://go.nchu.edu.cn/?id=91",
            ),
            WorkApp(
                id = "5lgJhazkTyAko4J0D3Kn9M",
                name = "实验室安全",
                description = "实验室安全",
                homePageUrl = "http://go.nchu.edu.cn/?id=93",
            ),
            WorkApp(
                id = "4Flx17CUQuEtGDMQzjHDt8",
                name = "教学质量管理平台",
                description = "教学质量管理平台",
                homePageUrl = "http://go.nchu.edu.cn/?id=94",
            ),
        ),
        "校园服务" to listOf(
            WorkApp(
                id = "ZByGrd41RlUgiXlRhkyIo0",
                name = "我的课表",
                description = "课表",
                homePageUrl = "http://go.nchu.edu.cn/?id=78&state=/schedule",
            ),
            WorkApp(
                id = "9RbDx_6vRtUmIu1DCS1-iY",
                name = "成绩",
                description = "成绩",
                homePageUrl = "http://go.nchu.edu.cn/?id=78&state=/score",
            ),
            WorkApp(
                id = "r9hjpgK7Tq8pjy9lcecYIQ",
                name = "校门进出报备",
                description = "保卫处校门进出管理系统",
                homePageUrl = "http://go.nchu.edu.cn/?id=64",
            ),
            WorkApp(
                id = "iFhV-7jCRjUgY8UZzdO0wE",
                name = "查用电",
                description = "学生宿舍及家属区用电查询",
                homePageUrl = "http://go.nchu.edu.cn/?id=18",
            ),
            WorkApp(
                id = "7eHpgOZnRxMlQFnUaPaKjM",
                name = "缴电费",
                description = "学生宿舍及家属区一卡通缴电费",
                homePageUrl = "http://go.nchu.edu.cn/?id=63&state=0014",
            ),
            WorkApp(
                id = "iwC1GteMTfsm6ed9bvi0XY",
                name = "校园一卡通",
                description = "一卡通系统",
                homePageUrl = "http://go.nchu.edu.cn/?id=63",
            ),
            WorkApp(
                id = "hddkh6kPRIksS_z_Hd12c4",
                name = "校园直播",
                description = "IPTV电视直播和校园直播",
                homePageUrl = "https://iptv.nchu.edu.cn",
            ),
            WorkApp(
                id = "s17AMagnSLErAH_CIeVUdo",
                name = "学生邮箱",
                description = "学生电子邮件系统",
                homePageUrl = "http://mail.stu.nchu.edu.cn/",
            ),
            WorkApp(
                id = "p3GdGNp0QMQgbe7xnJtiuQ",
                name = "文档库",
                description = "信息中心",
                homePageUrl = "http://go.nchu.edu.cn/?id=77",
            ),
            WorkApp(
                id = "jHeiuPCvRMEo7oVMm5Uce0",
                name = "大型仪器共享平台",
                description = "大型仪器共享平台",
                homePageUrl = "http://go.nchu.edu.cn/?id=86",
            ),
            WorkApp(
                id = "TWh2_7wzQ3Ul0kpm3Cao8g",
                name = "后勤报修",
                description = "后勤管理处报修系统",
                homePageUrl = "https://web.nchu.edu.cn/page/hqbx.html",
            ),
            WorkApp(
                id = "zyQkP1-QTiAqvKGPabay1Q",
                name = "书记校长信箱",
                description = "书记校长信箱",
                homePageUrl = "http://go.nchu.edu.cn/?id=21",
            ),
            WorkApp(
                id = "g209SU78R1MlXrCMyonIRk",
                name = "体育场馆预约",
                description = "体育场馆预约",
                homePageUrl = "http://go.nchu.edu.cn/?id=97",
            ),
            WorkApp(
                id = "6lPdnVkUQeIssgVQ5HQtRA",
                name = "航小导",
                description = "学工AI辅导员",
                homePageUrl = "http://go.nchu.edu.cn/?id=99",
            ),
        ),
        "网上办事" to listOf(
            WorkApp(
                id = "hOVPVnZOQekiRl9lGADNqw",
                name = "网上办事大厅",
                description = "一站式网上办事大厅",
                homePageUrl = "http://Go.nchu.edu.cn/?id=59",
            ),
            WorkApp(
                id = "wG0di5DIRzonP1UQ767mz4",
                name = "新闻稿件报审",
                description = "宣传部",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=77f48995-8622-4644-a00e-ec0195023b74",
            ),
            WorkApp(
                id = "pKl9toyhS5QoJ-14VPkYrg",
                name = "室外摊点活动申请",
                description = "保卫处",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=cddcf76b-18a4-4287-83a8-5b2b44a7051b",
            ),
            WorkApp(
                id = "dQV2uiBRR1QuACV9cCrfOk",
                name = "教学楼门禁卡申请",
                description = "信息中心",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=b41c658e-95db-49c6-9546-b38bbbe88e0a",
            ),
            WorkApp(
                id = "dK_51v66RH0o1D0qcJ1i7M",
                name = "室外摊点活动申请",
                description = "保卫处",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=daecce95-7e77-49f8-9546-2aaf32aa93bf",
            ),
            WorkApp(
                id = "LXZiZfqORF4nFM-43sKEp8",
                name = "出差申请审批",
                description = "财务处",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=b86abda7-d2b5-4b33-948c-f100c51cb6fd",
            ),
            WorkApp(
                id = "zM1c0Z66R9Ylcw2ZzJGe6U",
                name = "研究生休学审批",
                description = "研究生休学审批",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=b4b9b670-32bf-4ac7-bf8c-2fc9367cc2a7",
            ),
            WorkApp(
                id = "tzU1_RA_SgsiSx2ADReOcU",
                name = "创新创业奖学金申报",
                description = "创新创业奖学金线上流程申报",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=af355d67-c6ec-4fe9-b38a-a9741dc1bc08",
            ),
            WorkApp(
                id = "sx-ZgQpXQyIqnvZeWlx55c",
                name = "法人证书使用申请",
                description = "档案馆",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=6bd6d68a-dfc4-48e6-932e-57d5c68fe630",
            ),
            WorkApp(
                id = "qLW3JrnBThYrrZvFZpBQCw",
                name = "安全一键通",
                description = "安全一键通",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=f407649c-3cba-4f73-9001-2731d38c7354",
            ),
            WorkApp(
                id = "qGyuslWIR-osigJItPNQpU",
                name = "研究生复学审批",
                description = "研究生复学（重新入学）审批",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=e212f097-7589-4ecb-833d-989022d7ce3d",
            ),
            WorkApp(
                id = "g04-XVNmR9ku65Aqwouoiw",
                name = "研究生临时困难补助",
                description = "研究生临时困难补助申请",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=6e01e48c-8961-41f1-9b9a-533a55f06ba2",
            ),
            WorkApp(
                id = "afYcRIrLQr4lm9DjhMd1ig",
                name = "本科生门禁卡申请",
                description = "信息中心",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=7e93752c-f580-4075-a16b-b8b9c8a9c04b",
            ),
            WorkApp(
                id = "Ty0DtNKFQ3Qop5FWIwA1M8",
                name = "专项学术资源访问开通",
                description = "专项学术资源访问开通申请",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=778cd11e-2f7d-4b1c-928d-764caff9174b",
            ),
            WorkApp(
                id = "Ouq-a_oeRyAgt43b7fUN0A",
                name = "研究生请销假",
                description = "研究生请销假",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=78018660-9e5c-4027-ab7d-17daa8664a8d",
            ),
            WorkApp(
                id = "Hfb6uPy7RuUjhaqoSCqpt8",
                name = "数据中心机房托管设",
                description = "数据中心机房托管设备申请",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=714790ef-28cd-4eef-8ef2-6e4c4bd286e1",
            ),
            WorkApp(
                id = "Fl7-xntcSJ0kri7XWFMjRg",
                name = "高性能计算账户申请",
                description = "高性能计算账户申请",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=1ef71e02-b6cc-40dc-a2a4-7e9dc2c43a2d",
            ),
            WorkApp(
                id = "B6rwE43QQ_In-3tnItbvrE",
                name = "研究生开通门禁卡申请",
                description = "研究生开通门禁卡申请",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=b41c658e-95db-49c6-9546-b38bbbe88e0a",
            ),
            WorkApp(
                id = "7K1_A1vPSt4p9pMtBcM-G4",
                name = "校园宣传品布置申请表",
                description = "校园宣传品布置申请表",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=233ede71-a721-4072-ad83-9f48fcff3eec",
            ),
            WorkApp(
                id = "3paHHXdmSIwnvlLud8flao",
                name = "校内施工许可证审批",
                description = "校内施工许可证审批",
                homePageUrl = "http://go.nchu.edu.cn/?id=10&state=98d8948a-6863-44e6-8392-4a953351b256",
            ),
        ),
        "网络服务" to listOf(
            WorkApp(
                id = "8K67Pc8VR-YhPP7clrJ66A",
                name = "高性能计算平台文档",
                description = "高性能计算平台文档",
                homePageUrl = "http://go.nchu.edu.cn/?id=80",
            ),
            WorkApp(
                id = "LTghTxJWR-grqgwNoEcOOM",
                name = "校园网测速",
                description = "现代教育技术与信息中心",
                homePageUrl = "http://Go.nchu.edu.cn/?id=9",
            ),
            WorkApp(
                id = "h1W8qN_GQp4gXwME-uEoIA",
                name = "网络故障报修",
                description = "网络报修",
                homePageUrl = "http://go.nchu.edu.cn/?id=82",
            ),
            WorkApp(
                id = "I6NLT2QhS_4m1ytlN8thiI",
                name = "显示我的IP地址",
                description = "现代教育技术与信息中心",
                homePageUrl = "https://ip.nchu.edu.cn/",
            ),
        ),
        "图书馆" to listOf(
            WorkApp(
                id = "hpAuzSFQRasvPhqwaDcrvo",
                name = "座位预约",
                description = "图书馆",
                homePageUrl = "http://go.nchu.edu.cn/?id=76",
            ),
            WorkApp(
                id = "I5ofivvBTr8tZtowP1HiQo",
                name = "掌上图书馆",
                description = "图书馆",
                homePageUrl = "http://go.nchu.edu.cn/?id=20",
            ),
            WorkApp(
                id = "DY2nZJ84TWokD39ySDA0fg",
                name = "研讨室预约",
                description = "研讨室预约",
                homePageUrl = "http://go.nchu.edu.cn/?id=98",
            ),
        ),
    )
}
