package com.lanhang.miuix.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.MainActivity
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 「小米超级岛」（HyperOS 焦点通知）版的上课提醒 —— 移植自 Nexio 课程表的
 * `IslandNotificationHelper`（只取上课提醒真正用到的那一套：课前倒计时 → 已上课 → 下课收起）。
 *
 * ## 它到底是怎么变成岛的
 *
 * 小米的岛不走任何 SDK：给一条普通通知，在 `notification.extras` 里塞两样东西即可 ——
 *  - `miui.focus.param`：一段 JSON，描述卡片上的标题/正文/倒计时(timerInfo)/
 *    大岛模板(`param_island.bigIslandArea.templateNo = 2`)/小岛图标引用；
 *  - `miui.focus.pics`：一个 Bundle，装 `miui.focus.pic_small(_dark)` /
 *    `miui.focus.pic_app_icon(_dark)` 四个 Icon，JSON 里那句 `"pic": "miui.focus.pic_small"`
 *    就是按这几个 key 去取图。
 *
 * 倒计时不用自己刷：`timerInfo.timerType = -1` + `timerWhen = 上课时刻` 交给系统 Chronometer 走。
 *
 * ## 什么时候能用
 *
 * 只有系统属性 `persist.sys.feature.island = true` 的机器（HyperOS 2 及以上）才认这套 extras，
 * 其他机器上它就是一条**普通通知**（不会崩、不会丢）。所以我们先在
 * [isSupported] 判一下，不支持就走 [ClassReminderNotify] 的老路。
 *
 * ## 为什么「已上课」要换一个通知 id
 *
 * 同一个 id 更新时岛框架当成静默替换，Chronometer 会卡在 00:00。所以课前倒计时
 * ([ID_COUNTDOWN]) 和「已上课」([ID_STARTED]) 是两条独立通知，互不覆盖。
 */
object ClassReminderIsland {

    private const val TAG = "ClassReminderIsland"

    private const val CHANNEL_ID = "class_reminder_island"

    /** 课前倒计时（大岛 templateNo=2 + timerInfo 正计时/倒计时） */
    const val ID_COUNTDOWN = 5003

    /** 已上课（同模板，但 timerType 换成计时，且是**另一个 id**） */
    const val ID_STARTED = 5004

    /** 官方必选：运营场景标识 */
    private const val BUSINESS_TAG = "course_reminder"

    /** 官方 sequence：多次更新不乱序 */
    private val sequence = AtomicLong(System.currentTimeMillis() / 1000L)

    /**
     * 本机是否支持超级岛。
     *
     * 读的是 MIUI/HyperOS 的 `persist.sys.feature.island`。反射在部分版本会被拦，
     * 一律 runCatching：读不到 = 不支持，绝不因此让提醒整条失效。
     */
    fun isSupported(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        method.invoke(null, "persist.sys.feature.island", false) as Boolean
    }.getOrDefault(false)

    /**
     * 课前提醒：画一张带倒计时的岛。
     *
     * @return true = 已经按岛发出；false = 本机不支持（调用方退回普通通知）
     */
    fun notifyPreClass(
        context: Context,
        courseName: String,
        section: String,
        room: String,
        teacher: String,
        startMillis: Long,
        endMillis: Long,
        test: Boolean = false,
    ): Boolean {
        if (!isSupported()) return false
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val content = contentOf(section, room, teacher)
        val title = courseName
        val id = if (test) ID_COUNTDOWN + 100 else ID_COUNTDOWN
        val json = buildParams(
            counting = true,
            courseName = courseName,
            content = content,
            room = room,
            timerWhen = startMillis,
            now = now,
            islandLeft = leftOf(courseName, room),
            aodTitle = aodOf(courseName, room),
        )
        return post(app, id, title, content, json)
    }

    /**
     * 到点了：把岛切成「已上课」，并在 [endMillis] 之后由调用方的边界闹钟收起来。
     */
    fun notifyStarted(
        context: Context,
        courseName: String,
        section: String,
        room: String,
        teacher: String,
        startMillis: Long,
        endMillis: Long,
        test: Boolean = false,
    ): Boolean {
        if (!isSupported()) return false
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val content = contentOf(section, room, teacher)
        val id = if (test) ID_STARTED + 100 else ID_STARTED
        // 课中岛：右侧可以选「距下课倒计时」（倒计时到 endMillis）或什么都不显示
        val remain = ClassReminderStore.inClassRight == "remain"
        val json = buildParams(
            counting = remain,
            courseName = courseName,
            content = content,
            room = room,
            timerWhen = if (remain) endMillis else 0L,
            now = now,
            islandLeft = leftOf(courseName, room),
            aodTitle = aodOf(courseName, room),
        )
        // 课前那条先撤掉，免得两张卡同时在岛里排队
        runCatching { nm(app)?.cancel(if (test) ID_COUNTDOWN + 100 else ID_COUNTDOWN) }
        return post(app, id, courseName, content, json)
    }

    /** 下课（或关掉提醒）：把这两条岛都收起来 */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val manager = nm(app) ?: return
        runCatching {
            manager.cancel(ID_COUNTDOWN)
            manager.cancel(ID_STARTED)
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun nm(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(context: Context) {
        val manager = nm(context) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.class_reminder_island_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.class_reminder_channel_desc)
                enableVibration(true)
            }
        )
    }

    /** 正文：节次 ｜ 地点 ｜ 教师，缺哪段就不拼哪段 */
    private fun contentOf(section: String, room: String, teacher: String): String =
        listOf(section, room, teacher).filter { it.isNotBlank() }.joinToString(" ｜ ")

    /** 缩略态放不下完整课名，取前 6 个字（和 Nexio 一致的做法） */
    private fun shortOf(name: String): String = if (name.length <= 6) name else name.take(6)

    /** 岛的左侧内容：按设置显示课程名或地点（第三十三轮新增，照 Nexio 的「超级岛左侧」） */
    private fun leftOf(courseName: String, room: String): String =
        if (ClassReminderStore.islandLeft == "room" && room.isNotBlank()) room else courseName

    /**
     * 息屏显示用的短标题。
     *
     * 「缩略态精简」照着 Nexio 给四档：原始 / 精简地点 / 精简课程 / 两者。
     * 精简不调用任何模型，只用确定性规则（去楼栋前缀、取尾号），可复现、可解释。
     */
    private fun aodOf(courseName: String, room: String): String {
        if (!ClassReminderStore.islandAod) return ""
        return when (ClassReminderStore.thumbScope) {
            ClassReminderStore.ThumbScope.ORIGINAL -> shortOf(courseName)
            ClassReminderStore.ThumbScope.ROOM -> shortOf(abbreviate(room))
            ClassReminderStore.ThumbScope.COURSE -> shortOf(abbreviate(courseName))
            ClassReminderStore.ThumbScope.BOTH -> shortOf(abbreviate(courseName) + " " + abbreviate(room))
        }
    }

    /** 确定性精简：「综合楼南310」→「综南310」，「明德楼A栋-A200」→「明德A200」 */
    private fun abbreviate(text: String): String {
        var out = text
        listOf("教学楼", "综合楼", "实验楼", "图书馆", "体育馆", "阶梯教室", "多媒体教室", "教室")
            .forEach { out = out.replace(it, it.take(1)) }
        out = out.replace("-", "").replace("栋", "").replace(" ", "")
        return out.ifBlank { text }
    }

    private fun post(context: Context, id: Int, title: String, content: String, param: String): Boolean {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_class_reminder)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_REMINDER)
        val pics = Bundle().apply {
            val icon = Icon.createWithResource(context, R.drawable.ic_class_reminder)
            putParcelable("miui.focus.pic_app_icon", icon)
            putParcelable("miui.focus.pic_app_icon_dark", icon)
            putParcelable("miui.focus.pic_small", icon)
            putParcelable("miui.focus.pic_small_dark", icon)
        }
        builder.addExtras(Bundle().apply { putBundle("miui.focus.pics", pics) })
        val notification = builder.build()
        notification.extras.putString("miui.focus.param", param)
        return runCatching {
            nm(context)?.notify(id, notification)
            true
        }.getOrElse {
            Log.w(TAG, "island notify failed: ${it.message}")
            false
        }
    }

    /**
     * 完整的一段 `miui.focus.param`。
     *
     * 结构照 Nexio 抄（它是在真机上验证过的）：
     * `param_v2` → baseInfo（卡片标题/正文/配色）+ hintInfo（倒计时区）+ param_island（大岛/小岛）。
     */
    private fun buildParams(
        counting: Boolean,
        courseName: String,
        content: String,
        room: String,
        timerWhen: Long,
        now: Long,
        islandLeft: String,
        aodTitle: String,
    ): String {
        val paramV2 = JSONObject().apply {
            put("business", BUSINESS_TAG)
            put("protocol", 1)
            // 课前弹悬浮窗（用户能一眼看到），「已上课」只在岛里更新，不再打扰
            put("enableFloat", counting)
            put("islandFirstFloat", !counting)
            put("updatable", true)
            put("outEffectSrc", "")
            put("reopen", "reopen")
            put("sequence", sequence.incrementAndGet())
            put("aodTitle", aodTitle)

            put(
                "baseInfo",
                JSONObject().apply {
                    put("type", 2)
                    put("title", courseName)
                    put("content", content)
                    put("subTitle", "")
                    put("extraTitle", "")
                    put("specialTitle", "")
                    put("subContent", "")
                    put("picFunction", "")
                    put("showDivider", true)
                    put("showContentDivider", true)
                    put("colorTitle", "#111111")
                    put("colorTitleDark", "#ffffff")
                    put("colorContent", "#333333")
                    put("colorContentDark", "#cccccc")
                }
            )

            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "")
            })

            put(
                "hintInfo",
                JSONObject().apply {
                    put("type", 2)
                    // 参考大岛：底部左侧是标签 + 倒计时（标签「即将上课」/「已上课」）
                    put("title", if (counting) "即将上课" else "已上课")
                    put("content", "")
                    put(
                        "timerInfo",
                        JSONObject().apply {
                            if (counting) {
                                // -1 = 倒计时到 timerWhen；正计时用 0
                                put("timerType", -1)
                                put("timerWhen", timerWhen)
                                put("timerTotal", 0L)
                                put("timerSystemCurrent", now)
                            } else {
                                put("timerType", 0)
                                put("timerWhen", 0L)
                                put("timerTotal", 0L)
                                put("timerSystemCurrent", 0L)
                            }
                        }
                    )
                    // 底部右侧：标签「地点」+ 教室（参考图里就是这两列）
                    put("subContent", "")
                    put("subTitle", if (room.isBlank()) "" else "地点")
                    put("extraTitle", room)
                    put("colorContent", "#666666")
                    put("colorContentDark", "#aaaaaa")
                    put("colorTitle", "#222222")
                    put("colorTitleDark", "#eeeeee")
                    put("colorSubContent", "#666666")
                    put("colorSubContentDark", "#aaaaaa")
                    put("colorSubTitle", "#222222")
                    put("colorSubTitleDark", "#eeeeee")
                }
            )

            put(
                "param_island",
                JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandTimeout", 3600)
                    put(
                        "bigIslandArea",
                        JSONObject().apply {
                            put("templateNo", 2)
                            put(
                                "imageTextInfoLeft",
                                JSONObject().apply {
                                    put("type", 1)
                                    put(
                                        "textInfo",
                                        JSONObject().apply {
                                            put("title", islandLeft)
                                            put("content", "")
                                            put("showHighlightColor", false)
                                            put("narrowFont", false)
                                        }
                                    )
                                }
                            )
                            if (counting) {
                                // 右侧就是那块倒计时，交给系统的 Chronometer
                                put(
                                    "sameWidthDigitInfo",
                                    JSONObject().apply {
                                        put("content", "")
                                        put("showHighlightColor", false)
                                        put(
                                            "timerInfo",
                                            JSONObject().apply {
                                                put("timerType", -1)
                                                put("timerWhen", timerWhen)
                                                put("timerTotal", 0L)
                                                put("timerSystemCurrent", now)
                                            }
                                        )
                                    }
                                )
                            }
                            put(
                                "textInfo",
                                JSONObject().apply {
                                    put("frontTitle", "")
                                    put("title", "")
                                    put("content", "")
                                    put("showHighlightColor", false)
                                    put("narrowFont", false)
                                }
                            )
                        }
                    )
                    put(
                        "smallIslandArea",
                        JSONObject().apply {
                            put(
                                "picInfo",
                                JSONObject().apply {
                                    put("type", 1)
                                    put("pic", "miui.focus.pic_small")
                                    put("picDark", "miui.focus.pic_small_dark")
                                }
                            )
                        }
                    )
                }
            )
        }
        return JSONObject().apply { put("param_v2", paramV2) }.toString()
    }
}
