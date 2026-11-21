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
 * 手绘图标。
 *
 * Miuix 自带的 155 个图标里没有「工作 / 办事大厅」这一类（只有 `Tasks` 方框打勾、
 * `GridView` 九宫格），所以按官方那套规矩自己画：
 * - 画布 24×24，四周留 3dp 安全边；
 * - 线宽 1.9dp（跟 Miuix 那套视觉重量一致）、圆头端点、圆角转折；
 * - 只用单色描边，颜色交给 `Icon(tint = ...)`，深浅色主题自动跟随。
 */

/** 圆角矩形（用二次贝塞尔近似圆角，省掉 arcTo 的椭圆参数换算） */
private fun PathBuilder.roundedRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
) {
    val r = radius
    moveTo(left + r, top)
    lineTo(right - r, top)
    quadTo(right, top, right, top + r)
    lineTo(right, bottom - r)
    quadTo(right, bottom, right - r, bottom)
    lineTo(left + r, bottom)
    quadTo(left, bottom, left, bottom - r)
    lineTo(left, top + r)
    quadTo(left, top, left + r, top)
    close()
}

/** 提手：从包身上沿往上勾一个倒 U */
private fun PathBuilder.handle(top: Float, bottom: Float) {
    val halfWidth = 3f
    val centerX = 12f
    val radius = 2f
    moveTo(centerX - halfWidth, bottom)
    lineTo(centerX - halfWidth, top + radius)
    quadTo(centerX - halfWidth, top, centerX - halfWidth + radius, top)
    lineTo(centerX + halfWidth - radius, top)
    quadTo(centerX + halfWidth, top, centerX + halfWidth, top + radius)
    lineTo(centerX + halfWidth, bottom)
}

/** 整圆（四段三次贝塞尔，k = 0.5523 是圆弧的标准近似系数） */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    val k = 0.5523f * r
    moveTo(cx, cy - r)
    curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    close()
}

/** 圆弧（从 startDeg 到 endDeg，度，0 = 正右，顺时针） */
private fun PathBuilder.arc(cx: Float, cy: Float, r: Float, startDeg: Float, endDeg: Float) {
    val start = Math.toRadians(startDeg.toDouble())
    val sweep = Math.toRadians((endDeg - startDeg).toDouble())
    val k = 4.0 / 3.0 * Math.tan(sweep / 4.0)
    val x0 = cx + r * Math.cos(start)
    val y0 = cy + r * Math.sin(start)
    val x1 = cx + r * Math.cos(start + sweep)
    val y1 = cy + r * Math.sin(start + sweep)
    val c0x = x0 - k * r * Math.sin(start)
    val c0y = y0 + k * r * Math.cos(start)
    val c1x = x1 + k * r * Math.sin(start + sweep)
    val c1y = y1 - k * r * Math.cos(start + sweep)
    curveTo(c0x.toFloat(), c0y.toFloat(), c1x.toFloat(), c1y.toFloat(), x1.toFloat(), y1.toFloat())
}

private fun strokeIcon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

/**
 * 工作：一只公文包——包身 + 提手 + 腰线。
 * 相册（照片）、身份码（扫码框）、设置（齿轮）、关于（信息圈）都是「一个大件」的线性图标，
 * 公文包同样是「一个大件」，放一起不会抢戏。
 */
val LanhangIcons: LanhangIconSet = LanhangIconSet

object LanhangIconSet {

    /** 用电：一道闪电（工作页「查用电」这类应用用） */
    val Bolt: ImageVector by lazy {
        strokeIcon("Lanhang.Bolt") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.6f, 3.8f)
                lineTo(6.9f, 13.2f)
                lineTo(11.6f, 13.2f)
                lineTo(10.4f, 20.2f)
                lineTo(17.1f, 10.8f)
                lineTo(12.4f, 10.8f)
                close()
            }
        }
    }

    val Work: ImageVector by lazy {
        strokeIcon("Lanhang.Work") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 包身
                roundedRect(left = 3.6f, top = 8.2f, right = 20.4f, bottom = 19.4f, radius = 3f)
                // 提手
                handle(top = 4.8f, bottom = 8.2f)
                // 腰线
                moveTo(3.6f, 13.8f)
                lineTo(20.4f, 13.8f)
            }
        }
    }

    /**
     * 通讯录：一本通讯册 + 里面的人（人像头肩）。
     * 「我的」里和「工作」的公文包、身份码的扫码框同属「一个大件」，放一起不抢戏。
     */
    val Contacts: ImageVector by lazy {
        strokeIcon("Lanhang.Contacts") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                roundedRect(left = 4.2f, top = 3.4f, right = 19.8f, bottom = 20.6f, radius = 3.2f)
                circle(cx = 12f, cy = 9.3f, r = 2.5f)
                moveTo(8.2f, 16.7f)
                quadTo(12f, 13.4f, 15.8f, 16.7f)
            }
        }
    }

    /** 组织架构：上方一个部门、下方两个，中间竖线 + 横线连起来 */
    val Org: ImageVector by lazy {
        strokeIcon("Lanhang.Org") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                roundedRect(left = 8.9f, top = 3.2f, right = 15.1f, bottom = 7.8f, radius = 1.8f)
                roundedRect(left = 2.7f, top = 16.2f, right = 9.3f, bottom = 20.8f, radius = 1.8f)
                roundedRect(left = 14.7f, top = 16.2f, right = 21.3f, bottom = 20.8f, radius = 1.8f)
                moveTo(12f, 7.8f)
                lineTo(12f, 12.1f)
                moveTo(6f, 12.1f)
                lineTo(18f, 12.1f)
                moveTo(6f, 12.1f)
                lineTo(6f, 16.2f)
                moveTo(18f, 12.1f)
                lineTo(18f, 16.2f)
            }
        }
    }

    /** 单个人：头 + 肩（通讯录成员用） */
    val Person: ImageVector by lazy {
        strokeIcon("Lanhang.Person") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                circle(cx = 12f, cy = 8.4f, r = 3.3f)
                moveTo(5.4f, 20.4f)
                quadTo(12f, 13.6f, 18.6f, 20.4f)
            }
        }
    }

    /** 消息：一个带小尾巴的对话气泡 */
    val Message: ImageVector by lazy {
        strokeIcon("Lanhang.Message") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.6f, 4.6f)
                lineTo(16.6f, 4.6f)
                quadTo(20.8f, 4.6f, 20.8f, 8.8f)
                lineTo(20.8f, 13.4f)
                quadTo(20.8f, 17.4f, 16.6f, 17.4f)
                lineTo(12.6f, 17.4f)
                lineTo(8.4f, 20.6f)
                lineTo(8.4f, 17.4f)
                lineTo(7.2f, 17.4f)
                quadTo(3.2f, 17.4f, 3.2f, 13.4f)
                lineTo(3.2f, 8.8f)
                quadTo(3.2f, 4.6f, 7.2f, 4.6f)
                close()
            }
        }
    }

    /** 右箭头（列表行用，比 Miuix 的 ChevronForward 细一点） */
    val Chevron: ImageVector by lazy {
        strokeIcon("Lanhang.Chevron") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.8f, 5.6f)
                lineTo(16.2f, 12f)
                lineTo(9.8f, 18.4f)
            }
        }
    }

    /**
     * 首页：一栋房子的轮廓（屋顶 + 左右外墙 + 门）。
     *
     * 2026-10-26 用户要求「通讯录左上角加一个 home 键」——Miuix 的 155 个图标里没有房子，
     * 按本文件的老规矩手绘：24×24、线宽 1.9、圆头端点，摆在小标题栏里和返回箭头同重量。
     */
    val Home: ImageVector by lazy {
        strokeIcon("Lanhang.Home") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 屋顶
                moveTo(3.9f, 11.3f)
                lineTo(12f, 4.3f)
                lineTo(20.1f, 11.3f)
                // 左右外墙 + 地面
                moveTo(5.9f, 9.9f)
                lineTo(5.9f, 18.9f)
                lineTo(18.1f, 18.9f)
                lineTo(18.1f, 9.9f)
                // 门
                moveTo(10.2f, 18.9f)
                lineTo(10.2f, 14.3f)
                lineTo(13.8f, 14.3f)
                lineTo(13.8f, 18.9f)
            }
        }
    }

    /**
     * 表情：一张笑脸（私信输入条的表情开关）。
     *
     * Miuix 那 155 个图标里没有「表情」这一类（只有 `Community` 那种抽象社群圈），
     * 所以继续按本文件那套手绘规矩自己画：圆脸 + 两只眼睛 + 一道笑弧。
     *
     * 2026-10-26 第五轮（用户反馈「图标只有一只眼睛」）：旧版把眼睛和笑弧画在同一个 path 里，
     * 弧线开头没有 `moveTo`，会从**右眼末端**拉一条斜线到弧的起点把右眼糊掉。
     * 现在眼睛单独一个 path（两条等长竖点）、笑弧单独一个 path 且自带起点，
     * 两眼左右对称（±2.9）、笑弧半径收到 4.4 不压眼睛。
     */
    val Emoji: ImageVector by lazy {
        strokeIcon("Lanhang.Emoji") {
            // 脸
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                circle(cx = 12f, cy = 12f, r = 8.3f)
            }
            // 两只眼睛：短竖点（跟脸同一条 path 会被 close() 连出杂线，所以单独画）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.1f, 9.3f)
                lineTo(9.1f, 10.9f)
                moveTo(14.9f, 9.3f)
                lineTo(14.9f, 10.9f)
            }
            // 笑弧（自带起点，别让上一段线的终点连过来）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f + 4.4f * 0.866f, 12f + 4.4f * 0.5f)
                arc(cx = 12f, cy = 12f, r = 4.4f, startDeg = 30f, endDeg = 150f)
            }
        }
    }

    /** 退格：表情面板右下角那一格（删掉草稿里的最后一个字符） */
    /** 加号（私信输入条左侧的「+」附件入口） */
    val Plus: ImageVector by lazy {
        strokeIcon("Lanhang.Plus") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 5.2f)
                lineTo(12f, 18.8f)
                moveTo(5.2f, 12f)
                lineTo(18.8f, 12f)
            }
        }
    }

    /**
     * 键盘：圆角外框 + 两排按键盘点 + 底部空格条（私信输入条“切回键盘”用）。
     *
     * 2026-11-15 第八十六轮（用户：「键盘的图标与主流图标相差甚远，连接网络图标矢量库获得键盘的图标」）：
     * 原来这里借用的是 `Backspace`（退格键，左边带一个尖角），确实不是键盘的样子。
     * 现在按 **Lucide** 的 `keyboard`（ISC，24×24、stroke 2、圆头圆角）的几何重画：
     * 外框 `rect(2,4,20,16,r2)`、第一排 4 个键点、第二排 3 个键点、空格条 `7→17`。
     * 坐标系整体收进本项目那套「四周留 3dp 安全边」的口径（外框 2.9~21.1 / 5.4~18.6）。
     * 键点用**实心小圆**而不是 Lucide 的 `h.01` 零长度线段：零长度子路径是否出点依赖
     * Skia 的 cap 处理，实心圆是确定能画出来的（同文件 Emoji 的教训）。
     */
    val Keyboard: ImageVector by lazy {
        strokeIcon("Lanhang.Keyboard") {
            // 外框
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                roundedRect(left = 2.9f, top = 5.4f, right = 21.1f, bottom = 18.6f, radius = 2.6f)
            }
            // 键点（两排 + 空格条）。颜色交给 Icon(tint = ...)，所以这里也用可着色的黑
            path(fill = SolidColor(Color.Black)) {
                // 第一排 4 个
                circle(cx = 6.5f, cy = 8.7f, r = 0.98f)
                circle(cx = 10.2f, cy = 8.7f, r = 0.98f)
                circle(cx = 13.8f, cy = 8.7f, r = 0.98f)
                circle(cx = 17.5f, cy = 8.7f, r = 0.98f)
                // 第二排 3 个（错开半格）
                circle(cx = 8.4f, cy = 12.0f, r = 0.98f)
                circle(cx = 12.0f, cy = 12.0f, r = 0.98f)
                circle(cx = 15.6f, cy = 12.0f, r = 0.98f)
            }
            // 空格条
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7.6f, 15.3f)
                lineTo(16.4f, 15.3f)
            }
        }
    }

    /**
     * 更多：横排三个实心圆点（微信 / QQ 聊天页右上角那个「…」，第九十二轮）。
     * 实心而非零长度描边线段：Skia 对零长 stroke 的 cap 处理不可靠（同文件 Keyboard 的教训）。
     */
    val More: ImageVector by lazy {
        strokeIcon("Lanhang.More") {
            path(fill = SolidColor(Color.Black)) {
                circle(cx = 5.4f, cy = 12f, r = 1.75f)
                circle(cx = 12f, cy = 12f, r = 1.75f)
                circle(cx = 18.6f, cy = 12f, r = 1.75f)
            }
        }
    }

    /** 复制：前后两张纸（长按消息菜单用，第八十八轮） */
    val Copy: ImageVector by lazy {
        strokeIcon("Lanhang.Copy") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 后面的纸（右上）
                moveTo(9.6f, 3.4f)
                lineTo(16.4f, 3.4f)
                quadTo(20.2f, 3.4f, 20.2f, 7.0f)
                lineTo(20.2f, 14.4f)
                quadTo(20.2f, 17.0f, 17.4f, 17.0f)
                // 前面的纸
                roundedRect(left = 3.6f, top = 7.6f, right = 14.6f, bottom = 20.4f, radius = 2.4f)
            }
        }
    }

    /** 多选：圆圈打勾 */
    val CheckCircle: ImageVector by lazy {
        strokeIcon("Lanhang.CheckCircle") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                circle(cx = 12f, cy = 12f, r = 8.6f)
                moveTo(7.6f, 12.4f)
                lineTo(10.6f, 15.4f)
                lineTo(16.6f, 8.8f)
            }
        }
    }

    /** 删除：垃圾桶（桶身 + 盖 + 提手 + 两条竖线） */
    val Trash: ImageVector by lazy {
        strokeIcon("Lanhang.Trash") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 桶身
                moveTo(5.6f, 7.6f)
                lineTo(6.6f, 19.4f)
                quadTo(6.8f, 20.6f, 8.2f, 20.6f)
                lineTo(15.8f, 20.6f)
                quadTo(17.2f, 20.6f, 17.4f, 19.4f)
                lineTo(18.4f, 7.6f)
                // 盖
                moveTo(3.6f, 7.6f)
                lineTo(20.4f, 7.6f)
                // 提手
                moveTo(9.2f, 7.6f)
                lineTo(9.2f, 5.2f)
                quadTo(9.2f, 3.6f, 10.8f, 3.6f)
                lineTo(13.2f, 3.6f)
                quadTo(14.8f, 3.6f, 14.8f, 5.2f)
                lineTo(14.8f, 7.6f)
                // 桶里两条竖线
                moveTo(10.0f, 11.2f)
                lineTo(10.0f, 16.8f)
                moveTo(14.0f, 11.2f)
                lineTo(14.0f, 16.8f)
            }
        }
    }

    /** 截图：两个对角的取景框（QQ 多选栏的「截图」） */
    val Crop: ImageVector by lazy {
        strokeIcon("Lanhang.Crop") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 左上 + 右下的角
                moveTo(3.8f, 9.2f)
                lineTo(3.8f, 5.4f)
                quadTo(3.8f, 3.8f, 5.4f, 3.8f)
                lineTo(9.2f, 3.8f)
                moveTo(20.2f, 14.8f)
                lineTo(20.2f, 18.6f)
                quadTo(20.2f, 20.2f, 18.6f, 20.2f)
                lineTo(14.8f, 20.2f)
                // 左下 / 右上各补一角，四个角凑成取景框
                moveTo(3.8f, 14.8f)
                lineTo(3.8f, 18.6f)
                quadTo(3.8f, 20.2f, 5.4f, 20.2f)
                lineTo(9.2f, 20.2f)
                moveTo(14.8f, 3.8f)
                lineTo(18.6f, 3.8f)
                quadTo(20.2f, 3.8f, 20.2f, 5.4f)
                lineTo(20.2f, 9.2f)
            }
        }
    }

    val Backspace: ImageVector by lazy {
        strokeIcon("Lanhang.Backspace") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 键盘轮廓（左边一个尖角）
                moveTo(9.4f, 5.6f)
                lineTo(18.6f, 5.6f)
                quadTo(20.4f, 5.6f, 20.4f, 7.4f)
                lineTo(20.4f, 16.6f)
                quadTo(20.4f, 18.4f, 18.6f, 18.4f)
                lineTo(9.4f, 18.4f)
                lineTo(3.6f, 12f)
                close()
                // 叉
                moveTo(11.6f, 9.6f)
                lineTo(16.2f, 14.4f)
                moveTo(16.2f, 9.6f)
                lineTo(11.6f, 14.4f)
            }
        }
    }
}
