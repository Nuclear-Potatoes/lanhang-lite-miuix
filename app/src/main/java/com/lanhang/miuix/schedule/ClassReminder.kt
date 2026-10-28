package com.lanhang.miuix.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lanhang.miuix.R
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.MainActivity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒的调度中心（移植自上游 `ClassReminder` + `ClassReminderNotify` + `ClassReminderReceiver`）。
 *
 * ## 为什么是「窗口预排 + 全量对账」而不是「只挂一个链式闹钟」
 *
 * 只挂一个闹钟（触发后再排下一个）最省事，但它是**单点**：哪一次重排抛异常，整条链就断了，
 * 而用户看不到任何异常 —— 直到某天发现「提醒一直没响」。
 *
 * 这里一次排好未来 [ClassReminderPlan.WINDOW_DAYS] 天所有课的闹钟，每个闹钟带**自己的**
 * requestCode；每次 [rearm] 都重算整个窗口，把「码段内、这次不需要」的槽位全部撤销。于是：
 *  - 某一条排失败只影响那一节，其他课照响；
 *  - 换课表 / 改提前量 / 关开关之后，旧闹钟靠同一次对账精确撤销，不会留孤儿；
 *  - 每天 00:05 一次全窗口重同步（[ACTION_RESYNC]）把窗口往前滚，即使某天所有闹钟都失败也能自愈。
 *
 * ## 什么时候必须重排（漏一个就静默失效）
 *
 * 开机、应用更新（`MY_PACKAGE_REPLACED`，**最容易漏**：不加的话每次发版用户的提醒会静默消失
 * 到下次打开 App）、改系统时间/时区、课表刷新成功、设置变更、App 启动。
 * 见 [ClassReminderReceiver] 与 `App.onCreate`。
 */
object ClassReminder {

    private const val TAG = "ClassReminder"

    /** 槽位闹钟的 action（[ClassReminderReceiver] 收） */
    const val ACTION_ALARM = "com.lanhang.miuix.schedule.CLASS_REMINDER_ALARM"

    /** 每日全窗口重同步 */
    const val ACTION_RESYNC = "com.lanhang.miuix.schedule.CLASS_REMINDER_RESYNC"

    /** 测试提醒（现在立刻发，不再等 60 秒） */
    const val ACTION_TEST = "com.lanhang.miuix.schedule.CLASS_REMINDER_TEST"

    /** 上课那一刻：把超级岛从「倒计时」切成「已上课」 */
    const val ACTION_CLASS_STARTED = "com.lanhang.miuix.schedule.CLASS_REMINDER_STARTED"

    /** 下课那一刻：把超级岛收起来 */
    const val ACTION_CLASS_ENDED = "com.lanhang.miuix.schedule.CLASS_REMINDER_ENDED"

    const val EXTRA_KEY = "slot_key"

    /**
     * 槽位码段起点。必须与 [ClassReminderPlan.MAX_SLOTS] 配合：对账时会把这个区间整个扫一遍，
     * 撤销不在新计划里的闹钟。不要与小组件的 4500/4510、互动消息的 4610 重叠。
     */
    private const val RC_SLOT_BASE = 5000

    private const val RC_RESYNC = 4900
    private const val RC_TEST = 4899

    /** 上课 / 下课边界闹钟的 requestCode 段（与 5000 槽位段、4500 小组件段都不重叠） */
    private const val RC_BOUNDARY_BASE = 5300

    /** 普通通知的 id 段（超级岛用的是 5003 / 5004，别撞） */
    private const val NOTIFY_ID_BASE = 4600

    /** 00:05 重同步：多数人还没起床，顺带把新的一天排进来 */
    private val RESYNC_TIME: LocalTime = LocalTime.of(0, 5)

    private val dateTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

    /**
     * 全量重排。**幂等**，可以在任意入口反复调用。
     *
     * 关闭状态 / 未登录 / 没有课表缓存 / 没有通知权限 → 一律撤销全部闹钟（不空转）。
     * 注意「撤销闹钟」不等于「改用户开关」：开关始终反映用户意图。
     */
    @Synchronized
    fun rearm(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        ClassReminderNotify.ensureChannel(app)
        if (!ClassReminderStore.enabled || !Session.isLoggedIn || !ClassReminderNotify.canNotify(app)) {
            cancelArmed(app, manager)
            return
        }
        val desired = ClassReminderPlan.upcoming(
            now = System.currentTimeMillis(),
            advanceMinutes = ClassReminderStore.advanceMinutes,
        )
        // 先把这次不需要的槽位全撤掉（含改提前量之后挪位的、换课表之后消失的）
        for (index in 0 until ClassReminderPlan.MAX_SLOTS) {
            if (index >= desired.size) cancelSlot(app, manager, index)
        }
        desired.forEachIndexed { index, slot -> setSlot(app, manager, index, slot) }
        armResync(app, manager)
        Log.d(TAG, "armed ${desired.size} slots, advance=${ClassReminderStore.advanceMinutes}")
    }

    /** 关开关 / 退出登录：撤掉全部闹钟与重同步心跳，已弹出的通知留给用户自己滑掉 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        cancelArmed(app, manager)
        runCatching { manager.cancel(resyncPending(app)) }
    }

    /**
     * 闹钟真的响了（[ACTION_ALARM]）。
     *
     * 这里必须**再校验一遍**，不能信任几天前排下去的那条：课表换了、提醒关了、课被撤销了，
     * 闹钟本身都不知道。校验口径：
     *  - 课程仍存在（重新按当天数据算一遍，找不到就是作废）；
     *  - 现在还没上课（已经上课了就不发，迟到补发只会干扰课堂），
     *    唯一例外是提前量 0 —— 它的提醒时刻**就是**上课时刻，必须容许几秒钟误差，否则永远不会响。
     */
    fun handleAlarm(context: Context, key: String?) {
        val app = context.applicationContext
        if (!ClassReminderStore.enabled) return
        val parsed = key?.let(ClassReminderPlan::parseKey) ?: return
        val (date, period) = parsed
        val slot = ClassReminderPlan.slotsOn(date, ClassReminderStore.advanceMinutes)
            .firstOrNull { it.period == period } ?: return
        val now = System.currentTimeMillis()
        val advance = ClassReminderStore.advanceMinutes
        if (advance > 0 && now >= slot.startAt) {
            Log.d(TAG, "skip late alarm for ${slot.key}")
            return
        }
        showSlot(app, slot)
        // 课前那条发出去之后，把「上课」「下课」两个边界也挂上：
        // 上课 → 岛切成「已上课」，下课 → 把岛收起来（Nexio 的岛就是这么收尾的）
        armBoundaries(app, slot)
    }

    /**
     * 测试提醒：**立刻**发一条。
     *
     * 2026-10-28 第三十二轮（用户：「点开发一条测试提醒之后应立即弹出测试通知」）：
     * 旧版是排一个 60 秒后的闹钟再让广播发通知 —— 点完什么反应都没有，看着就像功能坏了。
     * 现在直接走和真实提醒**完全相同**的发布路径（有超级岛走岛，没有走普通通知），
     * 只是内容换成一条「测试课程」，不再经过闹钟。
     */
    fun scheduleTest(context: Context) {
        showTestNow(context)
    }

    /** 立即发一条（测试按钮、[ACTION_TEST] 广播都走这里） */
    fun showTestNow(context: Context) {
        val app = context.applicationContext
        ClassReminderNotify.ensureChannel(app)
        val courseName = app.getString(R.string.class_reminder_test_title)
        val body = app.getString(R.string.class_reminder_test_body)
        val startAt = System.currentTimeMillis() + 10 * 60_000L
        val island = ClassReminderStore.islandEnabled && ClassReminderIsland.notifyPreClass(
            context = app,
            courseName = courseName,
            section = "",
            room = app.getString(R.string.class_reminder_test_room),
            teacher = "",
            startMillis = startAt,
            endMillis = startAt + 45 * 60_000L,
            test = true,
        )
        if (!island) ClassReminderNotify.show(app, TEST_NOTIFICATION_ID, courseName, body)
    }

    private const val TEST_NOTIFICATION_ID = 4899

    // ------------------------------------------------------------------ 发布与边界

    /** 一节课要展示的要素（课名 / 节次 / 地点 / 教师 / 起止时刻） */
    private class SlotInfo(
        val courseName: String,
        val section: String,
        val room: String,
        val teacher: String,
        val startAt: Long,
        val endAt: Long,
    )

    private fun infoOf(context: Context, slot: ClassReminderPlan.Slot): SlotInfo {
        val courseName = slot.courses.joinToString(" / ") { it.name }
            .ifBlank { context.getString(R.string.class_reminder_channel) }
        val room = slot.courses.mapNotNull { it.room.takeIf { r -> r.isNotBlank() } }
            .distinct().joinToString(" / ")
        val teacher = slot.courses.mapNotNull { it.teacher.takeIf { t -> t.isNotBlank() } }
            .distinct().joinToString(" / ")
        val zone = ZoneId.systemDefault()
        val endAt = PeriodClock.end(slot.period)
            ?.let { slot.date.atTime(it).atZone(zone).toInstant().toEpochMilli() }
            ?: (slot.startAt + 45 * 60_000L)
        return SlotInfo(
            courseName = courseName,
            section = context.getString(R.string.class_reminder_period, slot.period),
            room = room,
            teacher = teacher,
            startAt = slot.startAt,
            endAt = endAt,
        )
    }

    /** 有超级岛就按岛发（带倒计时），没有就发普通通知 —— 两条路内容一致 */
    private fun showSlot(app: Context, slot: ClassReminderPlan.Slot) {
        val info = infoOf(app, slot)
        val island = ClassReminderStore.islandEnabled && ClassReminderIsland.notifyPreClass(
            context = app,
            courseName = info.courseName,
            section = info.section,
            room = info.room,
            teacher = info.teacher,
            startMillis = info.startAt,
            endMillis = info.endAt,
        )
        if (!island) {
            ClassReminderNotify.show(app, NOTIFY_ID_BASE + slot.period, title(app, slot), body(slot))
        }
    }

    /** 上课 / 下课两个边界闹钟（只在岛模式下有意义，普通通知下它们也不会造成副作用） */
    private fun armBoundaries(app: Context, slot: ClassReminderPlan.Slot) {
        if (!ClassReminderStore.islandEnabled) return
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val info = infoOf(app, slot)
        val rc = RC_BOUNDARY_BASE + (slot.period - 1) * 2
        setAlarm(app, manager, rc, info.startAt, boundaryPending(app, rc, ACTION_CLASS_STARTED, slot.key))
        setAlarm(app, manager, rc + 1, info.endAt, boundaryPending(app, rc + 1, ACTION_CLASS_ENDED, slot.key))
    }

    /** 到点了：岛从「倒计时」换成「已上课」 */
    fun handleClassStarted(context: Context, key: String?) {
        val app = context.applicationContext
        if (!ClassReminderStore.islandEnabled) return
        val parsed = key?.let(ClassReminderPlan::parseKey) ?: return
        val (date, period) = parsed
        val slot = ClassReminderPlan.slotsOn(date, 0).firstOrNull { it.period == period } ?: return
        val info = infoOf(app, slot)
        ClassReminderIsland.notifyStarted(
            context = app,
            courseName = info.courseName,
            section = info.section,
            room = info.room,
            teacher = info.teacher,
            startMillis = info.startAt,
            endMillis = info.endAt,
        )
    }

    /** 下课：把岛收起来 */
    fun handleClassEnded(context: Context, key: String?) {
        val app = context.applicationContext
        if (!ClassReminderStore.islandEnabled) return
        ClassReminderIsland.cancelAll(app)
    }

    private fun boundaryPending(app: Context, rc: Int, action: String, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            rc,
            Intent(app, ClassReminderReceiver::class.java).setAction(action).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ------------------------------------------------------------------ 通知文案

    private fun title(context: Context, slot: ClassReminderPlan.Slot): String =
        slot.courses.joinToString(" / ") { it.name }
            .ifBlank { context.getString(R.string.class_reminder_channel) }

    /** 第二行：`10/28 10:00 · 第 3 节 · B408`（地点只在有值时出现） */
    private fun body(slot: ClassReminderPlan.Slot): String {
        val start = dateTimeFormat.format(
            Instant.ofEpochMilli(slot.startAt).atZone(ZoneId.systemDefault())
        )
        val room = slot.courses.mapNotNull { it.room.takeIf { r -> r.isNotBlank() } }
            .distinct().joinToString(" / ")
        return buildString {
            append(start).append(" · 第 ").append(slot.period).append(" 节")
            if (room.isNotEmpty()) append(" · ").append(room)
        }
    }

    // ------------------------------------------------------------------ 闹钟落盘

    private fun setSlot(app: Context, manager: AlarmManager, index: Int, slot: ClassReminderPlan.Slot) {
        setAlarm(app, manager, RC_SLOT_BASE + index, slot.triggerAt, alarmPending(app, RC_SLOT_BASE + index, slot.key))
    }

    private fun cancelSlot(app: Context, manager: AlarmManager, index: Int) {
        // PendingIntent 的匹配只看 (action, data, type, class, categories)，**不看 extras**，
        // 所以撤销时不带 key 也能命中同 requestCode 那一个。
        runCatching { manager.cancel(alarmPending(app, RC_SLOT_BASE + index, "")) }
    }

    private fun setAlarm(app: Context, manager: AlarmManager, rc: Int, at: Long, pending: PendingIntent) {
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // 没有精确闹钟权限时退回「大概准时」：晚几分钟也比不响好
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { Log.w(TAG, "set alarm $rc failed: ${it.message}") }
    }

    private fun cancelArmed(app: Context, manager: AlarmManager) {
        for (index in 0 until ClassReminderPlan.MAX_SLOTS) cancelSlot(app, manager, index)
    }

    private fun armResync(app: Context, manager: AlarmManager) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        var at = now.toLocalDate().atTime(RESYNC_TIME).atZone(zone)
        if (!at.isAfter(now)) at = at.plusDays(1)
        setAlarm(app, manager, RC_RESYNC, at.toInstant().toEpochMilli(), resyncPending(app))
    }

    private fun alarmPending(app: Context, rc: Int, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            rc,
            Intent(app, ClassReminderReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun resyncPending(app: Context): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            RC_RESYNC,
            Intent(app, ClassReminderReceiver::class.java).setAction(ACTION_RESYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun testPending(app: Context, at: Long): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            RC_TEST,
            Intent(app, ClassReminderReceiver::class.java).setAction(ACTION_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 通知渠道与权限判定（上课提醒专用一个渠道，用户可以在系统里单独关掉） */
object ClassReminderNotify {

    private const val CHANNEL_ID = "class_reminder"

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.class_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.class_reminder_channel_desc)
                enableVibration(true)
            }
        )
    }

    fun show(context: Context, id: Int, title: String, body: String) {
        if (!canNotify(context)) return
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_class_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

/**
 * 上课提醒的广播入口。
 *
 * 清单里注册了开机、应用更新、改时间/时区这几条系统广播 —— 它们全是「重排」的触发点，
 * 少一条就会出现「重启后提醒静默消失」。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        ClassReminderStore.init(app)
        when (action) {
            ClassReminder.ACTION_ALARM -> {
                ClassReminder.handleAlarm(app, intent.getStringExtra(ClassReminder.EXTRA_KEY))
                // 响过一次就把窗口往后滚一格，顺手补上新排进来的那节课
                ClassReminder.rearm(app)
            }

            ClassReminder.ACTION_TEST -> ClassReminder.showTestNow(app)
            ClassReminder.ACTION_CLASS_STARTED ->
                ClassReminder.handleClassStarted(app, intent.getStringExtra(ClassReminder.EXTRA_KEY))
            ClassReminder.ACTION_CLASS_ENDED ->
                ClassReminder.handleClassEnded(app, intent.getStringExtra(ClassReminder.EXTRA_KEY))
            ClassReminder.ACTION_RESYNC -> ClassReminder.rearm(app)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> ClassReminder.rearm(app)
        }
    }
}
