package com.lanhang.miuix.ui.miuix

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「工作」页的图标：**照着学校的原图标重画**。
 *
 * 学校（南航「蓝航」）办事大厅里每个应用都有一枚方块图标，真实形状就是这里要保留的东西——
 * 教务是一只开着「教」字的窗口、查用电是一道闪电、缴电费是闪电 + ¥、校园网是三道无线弧、
 * 座位预约是一张单人沙发、图书馆是一本带书签的书……相册/笔记本这种「随便找个线性图标代替」
 * 的做法会把辨识度做没（用户 2026-10-27 第二十轮反馈就是这个）。
 *
 * 所以这一套**只改画法，不改形状**：
 * - 画布仍是 24×24、线宽 1.9dp、圆头端点 + 圆角转折（跟 Miuix 那套视觉重量一致）；
 * - 曲线一律用贝塞尔 / 圆弧逼近，不吃直角，观感更顺；
 * - 仍然是单色描边，颜色交给外层 `Icon(tint = …)`，深浅色主题自动跟随。
 */
object SchoolIcons {

    // ---------- 基础图元 ----------

    private fun PathBuilder.rr(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        val k = rad
        moveTo(l + k, t)
        lineTo(r - k, t)
        quadTo(r, t, r, t + k)
        lineTo(r, b - k)
        quadTo(r, b, r - k, b)
        lineTo(l + k, b)
        quadTo(l, b, l, b - k)
        lineTo(l, t + k)
        quadTo(l, t, l + k, t)
        close()
    }

    private fun PathBuilder.circ(cx: Float, cy: Float, r: Float) {
        val k = 0.5523f * r
        moveTo(cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        close()
    }

    /** 圆弧：从 a0 到 a1（度，0 = 正右、顺时针），自带 moveTo */
    private fun PathBuilder.arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) {
        val start = Math.toRadians(a0.toDouble())
        val sweep = Math.toRadians((a1 - a0).toDouble())
        val k = 4.0 / 3.0 * Math.tan(sweep / 4.0)
        val x0 = cx + r * Math.cos(start)
        val y0 = cy + r * Math.sin(start)
        val x1 = cx + r * Math.cos(start + sweep)
        val y1 = cy + r * Math.sin(start + sweep)
        moveTo(x0.toFloat(), y0.toFloat())
        curveTo(
            (x0 - k * r * Math.sin(start)).toFloat(),
            (y0 + k * r * Math.cos(start)).toFloat(),
            (x1 + k * r * Math.sin(start + sweep)).toFloat(),
            (y1 - k * r * Math.cos(start + sweep)).toFloat(),
            x1.toFloat(), y1.toFloat(),
        )
    }

    private fun PathBuilder.ln(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(build).build()

    private fun svg(name: String, build: PathBuilder.() -> Unit): ImageVector = icon("School.$name") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
    }

    // ---------- 教学 / 教务 ----------

    /** 智慧教务：原图是「窗口里一个教字」——这里画成窗口 + 标题栏 + 内容行 */
    val Teach: ImageVector by lazy {
        svg("Teach") {
            rr(3.0f, 4.0f, 21.0f, 20.0f, 2.8f)
            ln(3.0f, 8.6f, 21.0f, 8.6f)
            ln(7.0f, 12.2f, 17.0f, 12.2f)
            ln(7.0f, 15.4f, 17.0f, 15.4f)
            ln(7.0f, 18.4f, 13.0f, 18.4f)
        }
    }

    /** 我的课表：原图是日历 */
    val Calendar: ImageVector by lazy {
        svg("Calendar") {
            rr(3.4f, 5.4f, 20.6f, 20.2f, 2.8f)
            ln(3.4f, 10.2f, 20.6f, 10.2f)
            ln(8.2f, 3.2f, 8.2f, 7.2f)
            ln(15.8f, 3.2f, 15.8f, 7.2f)
            ln(7.6f, 13.8f, 10.6f, 13.8f)
            ln(13.4f, 13.8f, 16.4f, 13.8f)
            ln(7.6f, 17.2f, 10.6f, 17.2f)
        }
    }

    /** 成绩：原图是 A+，画成「上扬折线 + 箭头」，一眼是「分数/绩点」 */
    val Chart: ImageVector by lazy {
        svg("Chart") {
            moveTo(4.4f, 17.4f)
            lineTo(9.6f, 12.0f)
            lineTo(13.2f, 14.8f)
            lineTo(19.4f, 7.6f)
            moveTo(15.2f, 7.4f)
            lineTo(19.6f, 7.4f)
            lineTo(19.6f, 11.8f)
        }
    }

    /** 教学质量管理平台：原图是「评」字夹板——画成夹板 + 勾 */
    val Clipboard: ImageVector by lazy {
        svg("Clipboard") {
            rr(4.6f, 4.4f, 19.4f, 20.4f, 2.6f)
            rr(9.2f, 2.4f, 14.8f, 6.0f, 1.3f)
            moveTo(8.4f, 13.4f)
            lineTo(11.0f, 16.0f)
            lineTo(16.0f, 10.4f)
        }
    }

    // ---------- 后勤 / 缴费 ----------

    /** 查用电：一道闪电（原图同形，这里把转折做圆、上下两端收窄一点） */
    val Bolt: ImageVector by lazy {
        svg("Bolt") {
            moveTo(13.4f, 3.2f)
            lineTo(6.4f, 13.4f)
            lineTo(11.2f, 13.4f)
            lineTo(10.0f, 20.8f)
            lineTo(17.4f, 10.2f)
            lineTo(12.6f, 10.2f)
            close()
        }
    }

    /** 缴电费：原图是「闪电 + ¥」 */
    val BoltYuan: ImageVector by lazy {
        svg("BoltYuan") {
            // 闪电（占左上 2/3）
            moveTo(12.4f, 2.4f)
            lineTo(5.0f, 12.6f)
            lineTo(9.6f, 12.6f)
            lineTo(8.4f, 19.0f)
            moveTo(19.0f, 11.0f)
            lineTo(15.0f, 15.2f)
            moveTo(11.0f, 15.2f)
            lineTo(15.0f, 11.0f)
            ln(15.0f, 15.2f, 15.0f, 21.2f)
            ln(11.8f, 17.0f, 18.2f, 17.0f)
            ln(11.8f, 18.8f, 18.2f, 18.8f)
        }
    }

    /** 校园一卡通 / 银行卡：卡片 + 磁条 + 芯片 */
    val Card: ImageVector by lazy {
        svg("Card") {
            rr(2.6f, 6.0f, 21.4f, 18.0f, 2.6f)
            ln(2.6f, 10.0f, 21.4f, 10.0f)
            rr(5.4f, 12.4f, 9.4f, 15.6f, 0.9f)
            ln(12.0f, 14.0f, 18.0f, 14.0f)
        }
    }

    /** 钱包（财务 / 出差报销这类） */
    val Wallet: ImageVector by lazy {
        svg("Wallet") {
            rr(2.8f, 6.4f, 21.2f, 19.2f, 2.8f)
            moveTo(2.8f, 10.4f)
            lineTo(17.4f, 10.4f)
            quadTo(21.2f, 10.4f, 21.2f, 12.8f)
            quadTo(21.2f, 15.2f, 17.4f, 15.2f)
            lineTo(15.4f, 15.2f)
            circ(17.0f, 12.8f, 0.7f)
        }
    }

    /** 一元硬币：研究生临时困难补助 / 资助 */
    val HandYuan: ImageVector by lazy {
        svg("Coin") {
            circ(12f, 12f, 8.8f)
            moveTo(9.6f, 7.4f)
            lineTo(12.0f, 10.6f)
            moveTo(14.4f, 7.4f)
            lineTo(12.0f, 10.6f)
            ln(12.0f, 10.6f, 12.0f, 16.6f)
            ln(9.4f, 11.8f, 14.6f, 11.8f)
            ln(9.4f, 13.6f, 14.6f, 13.6f)
        }
    }

    // ---------- 网络 / 信息 ----------

    /** 校园网一键认证：原图是三道无线弧 + 一点 */
    val Wifi: ImageVector by lazy {
        svg("Wifi") {
            arc(12f, 19.0f, 12.4f, 220f, 320f)
            arc(12f, 19.0f, 8.2f, 220f, 320f)
            arc(12f, 19.0f, 4.0f, 220f, 320f)
            circ(12f, 18.4f, 0.9f)
        }
    }

    /** 校园网账号设置 / 网络：地球 */
    val Globe: ImageVector by lazy {
        svg("Globe") {
            circ(12f, 12f, 8.6f)
            ln(3.4f, 12f, 20.6f, 12f)
            moveTo(12f, 3.4f)
            quadTo(6.2f, 12f, 12.0f, 20.6f)
            quadTo(17.8f, 12f, 12.0f, 3.4f)
        }
    }

    /** 高性能计算：三个节点 + 连线（原图同形） */
    val Nodes: ImageVector by lazy {
        svg("Nodes") {
            circ(5.2f, 7.0f, 2.2f)
            circ(18.8f, 12.0f, 2.2f)
            circ(5.2f, 17.0f, 2.2f)
            ln(7.4f, 7.4f, 16.6f, 11.4f)
            ln(7.4f, 16.6f, 16.6f, 12.6f)
        }
    }

    /** 校园网测速：仪表盘（原图是速度表） */
    val Gauge: ImageVector by lazy {
        svg("Gauge") {
            rr(2.8f, 3.6f, 21.2f, 20.4f, 3.0f)
            arc(12f, 13.4f, 5.4f, 200f, 340f)
            ln(12f, 13.4f, 15.6f, 9.8f)
            circ(12f, 13.4f, 0.9f)
        }
    }

    /** 显示我的 IP / 系统类：显示器 */
    val Monitor: ImageVector by lazy {
        svg("Monitor") {
            rr(2.8f, 4.6f, 21.2f, 16.6f, 2.4f)
            ln(8.6f, 20.4f, 15.4f, 20.4f)
            ln(12f, 16.6f, 12f, 20.4f)
            ln(7.4f, 8.6f, 10.6f, 8.6f)
            ln(7.4f, 12.2f, 13.4f, 12.2f)
            circ(16.8f, 8.8f, 0.7f)
        }
    }

    /** 通知服务：喇叭 */
    val Megaphone: ImageVector by lazy {
        svg("Megaphone") {
            moveTo(4.4f, 9.4f)
            lineTo(15.6f, 4.4f)
            lineTo(15.6f, 19.0f)
            lineTo(4.4f, 14.0f)
            close()
            moveTo(7.2f, 14.6f)
            lineTo(8.6f, 20.0f)
            lineTo(12.0f, 20.0f)
            lineTo(11.0f, 15.8f)
        }
    }

    // ---------- 场馆 / 生活 ----------

    /** 图书馆座位预约：原图是一张单人沙发 */
    val Seat: ImageVector by lazy {
        svg("Seat") {
            // 靠背
            rr(7.4f, 3.2f, 16.6f, 11.4f, 2.2f)
            // 座面（比靠背宽一圈，坐垫的感觉）
            rr(3.0f, 11.4f, 21.0f, 17.2f, 2.6f)
            // 两条腿
            ln(6.4f, 17.2f, 6.4f, 20.6f)
            ln(17.6f, 17.2f, 17.6f, 20.6f)
        }
    }

    /** 掌上图书馆 / 借阅：一本书 + 书签（原图同形） */
    val Book: ImageVector by lazy {
        svg("Book") {
            moveTo(4.4f, 4.4f)
            lineTo(15.6f, 4.4f)
            quadTo(19.6f, 4.4f, 19.6f, 8.4f)
            lineTo(19.6f, 19.6f)
            lineTo(8.4f, 19.6f)
            quadTo(4.4f, 19.6f, 4.4f, 15.6f)
            close()
            ln(4.4f, 15.6f, 19.6f, 15.6f)
            moveTo(10.0f, 4.4f)
            lineTo(10.0f, 10.0f)
            lineTo(12.4f, 8.2f)
            lineTo(14.8f, 10.0f)
            lineTo(14.8f, 4.4f)
        }
    }

    /** 研讨室预约：三个人（原图同形） */
    val Group: ImageVector by lazy {
        svg("Group") {
            circ(12f, 8.0f, 2.6f)
            moveTo(7.6f, 17.4f)
            quadTo(12f, 12.6f, 16.4f, 17.4f)
            circ(4.8f, 9.6f, 2.0f)
            moveTo(1.8f, 17.4f)
            quadTo(4.8f, 13.8f, 7.4f, 16.2f)
            circ(19.2f, 9.6f, 2.0f)
            moveTo(22.2f, 17.4f)
            quadTo(19.2f, 13.8f, 16.6f, 16.2f)
        }
    }

    /** 体育场馆预约：原图是弧形体育馆 */
    val Stadium: ImageVector by lazy {
        svg("Stadium") {
            arc(12f, 15.4f, 8.6f, 180f, 360f)
            ln(3.4f, 15.4f, 3.4f, 19.0f)
            ln(20.6f, 15.4f, 20.6f, 19.0f)
            ln(2.4f, 19.0f, 21.6f, 19.0f)
            moveTo(9.0f, 19.0f)
            lineTo(9.0f, 15.2f)
            quadTo(12f, 12.6f, 15.0f, 15.2f)
            lineTo(15.0f, 19.0f)
        }
    }

    // ---------- 安全 / 审批 ----------

    /** 安全一键通：盾牌 + 勾 */
    val Shield: ImageVector by lazy {
        svg("Shield") {
            moveTo(12f, 2.8f)
            lineTo(19.6f, 5.6f)
            quadTo(19.6f, 14.0f, 12.0f, 21.2f)
            quadTo(4.4f, 14.0f, 4.4f, 5.6f)
            close()
            moveTo(8.8f, 11.6f)
            lineTo(11.2f, 14.2f)
            lineTo(15.4f, 9.4f)
        }
    }

    /** 校门进出报备 / 门禁卡申请：门框 + 箭头 */
    val Door: ImageVector by lazy {
        svg("Door") {
            rr(3.2f, 2.8f, 12.2f, 21.2f, 2.2f)
            ln(7.8f, 12.0f, 7.8f, 12.0f)
            moveTo(14.4f, 12.0f)
            lineTo(21.0f, 12.0f)
            moveTo(18.4f, 9.2f)
            lineTo(21.2f, 12.0f)
            lineTo(18.4f, 14.8f)
        }
    }

    /** 门禁 / 门禁照片更新：原图是「笑脸 + 四角扫描框」 */
    val FaceGate: ImageVector by lazy {
        svg("FaceGate") {
            moveTo(3.0f, 8.0f)
            lineTo(3.0f, 4.6f)
            quadTo(3.0f, 3.2f, 4.4f, 3.2f)
            lineTo(7.8f, 3.2f)
            moveTo(16.2f, 3.2f)
            lineTo(19.6f, 3.2f)
            quadTo(21.0f, 3.2f, 21.0f, 4.6f)
            lineTo(21.0f, 8.0f)
            moveTo(3.0f, 16.0f)
            lineTo(3.0f, 19.4f)
            quadTo(3.0f, 20.8f, 4.4f, 20.8f)
            lineTo(7.8f, 20.8f)
            moveTo(16.2f, 20.8f)
            lineTo(19.6f, 20.8f)
            quadTo(21.0f, 20.8f, 21.0f, 19.4f)
            lineTo(21.0f, 16.0f)
            circ(12f, 12.0f, 4.6f)
            circ(10.1f, 10.6f, 0.85f)
            circ(13.9f, 10.6f, 0.85f)
            arc(12f, 12.6f, 2.2f, 25f, 155f)
        }
    }

    /** 休学 / 停用：人像 + ✕ */
    val PersonX: ImageVector by lazy {
        svg("PersonX") {
            circ(9.4f, 7.6f, 3.2f)
            moveTo(3.4f, 20.6f)
            quadTo(9.4f, 13.6f, 15.4f, 20.6f)
            moveTo(15.6f, 8.6f)
            lineTo(21.6f, 14.6f)
            moveTo(21.6f, 8.6f)
            lineTo(15.6f, 14.6f)
        }
    }

    /** 复学 / 通过：人像 + ✓ */
    val PersonCheck: ImageVector by lazy {
        svg("PersonCheck") {
            circ(9.4f, 7.6f, 3.2f)
            moveTo(3.4f, 20.6f)
            quadTo(9.4f, 13.6f, 15.4f, 20.6f)
            moveTo(15.4f, 12.4f)
            lineTo(17.8f, 14.8f)
            lineTo(22.0f, 9.6f)
        }
    }

    /** 摊点活动 / 旗类审批：旗子 */
    val Flag: ImageVector by lazy {
        svg("Flag") {
            ln(5.4f, 3.0f, 5.4f, 21.4f)
            moveTo(5.4f, 4.6f)
            lineTo(19.6f, 4.6f)
            lineTo(15.8f, 9.6f)
            lineTo(19.6f, 14.6f)
            lineTo(5.4f, 14.6f)
        }
    }

    /** 奖学金：学士帽（原图同形） */
    val Cap: ImageVector by lazy {
        svg("Cap") {
            moveTo(12f, 4.0f)
            lineTo(21.4f, 8.0f)
            lineTo(12f, 12.0f)
            lineTo(2.6f, 8.0f)
            close()
            moveTo(6.4f, 10.2f)
            lineTo(6.4f, 15.4f)
            quadTo(12f, 19.0f, 17.6f, 15.4f)
            lineTo(17.6f, 10.2f)
            ln(21.4f, 8.0f, 21.4f, 14.4f)
        }
    }

    /** 学生邮箱：信封 + 纸（原图同形） */
    val Mail: ImageVector by lazy {
        svg("Mail") {
            rr(2.8f, 7.4f, 21.2f, 19.6f, 2.4f)
            moveTo(3.6f, 8.6f)
            lineTo(12.0f, 14.4f)
            lineTo(20.4f, 8.6f)
            rr(13.6f, 3.0f, 20.8f, 9.4f, 1.3f)
            ln(15.8f, 5.6f, 18.6f, 5.6f)
        }
    }

    /** 学生工作 / 个人：人像 + 名牌 */
    val StudentAffairs: ImageVector by lazy {
        svg("StudentAffairs") {
            circ(8.8f, 7.4f, 3.0f)
            moveTo(3.2f, 20.0f)
            quadTo(8.8f, 13.6f, 14.4f, 20.0f)
            rr(15.0f, 11.6f, 21.2f, 19.2f, 1.4f)
            ln(17.0f, 14.4f, 19.2f, 14.4f)
            ln(17.0f, 16.6f, 18.6f, 16.6f)
        }
    }

    /** 新闻 / 稿件 / 公告：报纸（原图同形） */
    val News: ImageVector by lazy {
        svg("News") {
            rr(2.8f, 5.0f, 21.2f, 19.0f, 2.2f)
            // 左边三行正文
            ln(6.0f, 9.0f, 12.4f, 9.0f)
            ln(6.0f, 12.2f, 12.4f, 12.2f)
            ln(6.0f, 15.4f, 12.4f, 15.4f)
            // 右边一块配图
            rr(15.0f, 8.6f, 18.6f, 15.8f, 0.9f)
        }
    }

    /** 后勤报修 / 网络故障报修：扳手 */
    val Wrench: ImageVector by lazy {
        svg("Wrench") {
            // 开口的 C 形扳手头
            arc(15.4f, 8.4f, 3.9f, 340f, 650f)
            // 手柄
            moveTo(12.6f, 11.2f)
            lineTo(4.6f, 19.2f)
            quadTo(3.4f, 20.4f, 4.6f, 20.6f)
        }
    }

    /** 书记校长信箱 / 意见反馈：信箱 */
    val Mailbox: ImageVector by lazy {
        svg("Mailbox") {
            rr(3.4f, 4.6f, 20.6f, 14.2f, 2.4f)
            // 信封口，一眼不是显示器
            moveTo(4.2f, 5.6f)
            lineTo(12.0f, 10.6f)
            lineTo(19.8f, 5.6f)
            // 立柱 + 底座
            ln(12.0f, 14.2f, 12.0f, 20.6f)
            ln(6.6f, 20.6f, 17.4f, 20.6f)
        }
    }

    /** 大型仪器共享 / 机房托管：设备 + 调节滑杆 */
    val Sliders: ImageVector by lazy {
        svg("Sliders") {
            rr(2.8f, 4.4f, 16.0f, 19.6f, 2.2f)
            ln(6.2f, 8.6f, 12.6f, 8.6f)
            ln(6.2f, 12.0f, 12.6f, 12.0f)
            ln(6.2f, 15.4f, 10.8f, 15.4f)
            ln(19.2f, 3.2f, 19.2f, 20.8f)
            circ(19.2f, 8.6f, 1.9f)
        }
    }

    /** 文档库 / 知识：灯泡（原图同形） */
    val Bulb: ImageVector by lazy {
        svg("Bulb") {
            circ(12f, 9.6f, 5.6f)
            moveTo(9.6f, 14.4f)
            lineTo(9.6f, 17.4f)
            lineTo(14.4f, 17.4f)
            lineTo(14.4f, 14.4f)
            ln(10.4f, 20.2f, 13.6f, 20.2f)
            moveTo(12f, 6.6f)
            quadTo(9.4f, 8.4f, 12.0f, 11.4f)
            quadTo(14.6f, 13.0f, 12.0f, 14.2f)
        }
    }

    /** 门禁照片更新：相机 + 镜头（原图这里是「拍照」的意思） */
    val FaceCamera: ImageVector by lazy {
        svg("FaceCamera") {
            rr(2.8f, 7.0f, 21.2f, 19.6f, 2.6f)
            moveTo(8.2f, 7.0f)
            lineTo(9.6f, 4.0f)
            lineTo(14.4f, 4.0f)
            lineTo(15.8f, 7.0f)
            circ(12f, 13.2f, 3.4f)
        }
    }

    /** 实验室安全：烧瓶（原图是显微镜，瓶身在 20dp 下更清楚） */
    val Flask: ImageVector by lazy {
        svg("Flask") {
            moveTo(9.8f, 3.4f)
            lineTo(9.8f, 9.2f)
            lineTo(5.0f, 17.8f)
            quadTo(4.0f, 20.0f, 6.4f, 20.0f)
            lineTo(17.6f, 20.0f)
            quadTo(20.0f, 20.0f, 19.0f, 17.8f)
            lineTo(14.2f, 9.2f)
            lineTo(14.2f, 3.4f)
            ln(8.6f, 3.4f, 15.4f, 3.4f)
            ln(6.9f, 15.6f, 17.1f, 15.6f)
        }
    }

    /** 校园直播：电视（原图同形） */
    val Tv: ImageVector by lazy {
        svg("Tv") {
            rr(2.8f, 6.4f, 21.2f, 17.0f, 2.2f)
            ln(8.6f, 20.4f, 15.4f, 20.4f)
            ln(12f, 17.0f, 12f, 20.4f)
            moveTo(8.6f, 3.6f)
            lineTo(12f, 6.4f)
            lineTo(15.4f, 3.6f)
        }
    }
}
