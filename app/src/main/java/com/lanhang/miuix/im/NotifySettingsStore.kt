package com.lanhang.miuix.im

import android.content.Context
import android.content.SharedPreferences
import com.lanhang.miuix.R
import java.util.Calendar
import java.util.Locale

/**
 * 私信通知的三组设置（第六十三轮第 4 条「参考原版lanhang的通知代码，重做通知功能」）。
 *
 * 原版蓝航的做法（`cn.edu.nchu.nahang.ui.me.SetNotificationActivity` +
 * `SetNotificationQuiteHoursActivity`）是直接把设置交给融云：
 *  - 「接收新消息通知」关掉 = `RongIM.setNotificationQuietHours("00:00:00", 1439)`（全天静音）；
 *  - 「通知显示消息详情」= `RongIMClient.setPushContentShowStatus`；
 *  - 「免打扰时段」= 起止时间换算成分钟跨度后交给 `setNotificationQuietHours(start, span)`。
 *
 * 本项目**没有**融云推送通道（`app_key` 为空，见 IM 只做会话不做推送的既有结论），
 * 通知是本机 `NotificationManager` 自己弹的（[ImNotify]），所以那三个融云 API 在本项目里
 * 不会生效。这里改成自己存、自己在 [ImNotify.showMessage] 里判，行为对用户一致：
 *  - 总开关关掉 → 一条都不弹；
 *  - 免打扰时段内 → 一条都不弹（跨零点也正确）；
 *  - 详情关掉 → 只弹「新消息 / 你收到一条新消息」，不暴露发送人和内容。
 */
object NotifySettingsStore {

    private const val FILE = "notify_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DETAIL = "detail"
    private const val KEY_QUIET = "quiet"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

    /** 免打扰默认 23:00 → 07:00（原版的默认值也是「夜里静音」这个语义） */
    const val DEFAULT_QUIET_START = 23 * 60
    const val DEFAULT_QUIET_END = 7 * 60

    private const val MINUTES_PER_DAY = 1440

    private var sp: SharedPreferences? = null

    /** 在 `App.onCreate` 里调一次（Application Context，不会泄漏） */
    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ 三项设置

    /** 接收新消息通知。**默认开**：通知本来就是用户装 IM 想要的，关掉才需要主动操作 */
    var enabled: Boolean
        get() = sp?.getBoolean(KEY_ENABLED, true) ?: true
        set(value) {
            sp?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /** 通知显示消息详情。**默认开**；关掉后通知里不出现发送人与正文 */
    var showDetail: Boolean
        get() = sp?.getBoolean(KEY_DETAIL, true) ?: true
        set(value) {
            sp?.edit()?.putBoolean(KEY_DETAIL, value)?.apply()
        }

    /** 免打扰时段开关。**默认关**：悄悄把通知吞掉比漏一条消息更让人困惑 */
    var quietEnabled: Boolean
        get() = sp?.getBoolean(KEY_QUIET, false) ?: false
        set(value) {
            sp?.edit()?.putBoolean(KEY_QUIET, value)?.apply()
        }

    /** 免打扰开始时刻（当天第几分钟，0..1439） */
    var quietStart: Int
        get() = sp?.getInt(KEY_QUIET_START, DEFAULT_QUIET_START) ?: DEFAULT_QUIET_START
        set(value) {
            sp?.edit()?.putInt(KEY_QUIET_START, value.coerceIn(0, MINUTES_PER_DAY - 1))?.apply()
        }

    /** 免打扰结束时刻（当天第几分钟，0..1439） */
    var quietEnd: Int
        get() = sp?.getInt(KEY_QUIET_END, DEFAULT_QUIET_END) ?: DEFAULT_QUIET_END
        set(value) {
            sp?.edit()?.putInt(KEY_QUIET_END, value.coerceIn(0, MINUTES_PER_DAY - 1))?.apply()
        }

    // ------------------------------------------------------------------ 判定

    /** 现在（本机时间）是当天第几分钟 */
    fun currentMinute(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /**
     * 这一分钟是否落在免打扰时段内。
     *
     * 跨零点必须算对：23:00 → 07:00 这种区间不是 `now in start..end`，
     * 而是「从 start 顺时针走到 end 的那一段」。算法是看 now 相对 start 的前进量
     * 是否小于整段跨度 —— 这样 23:30 与 06:30 都命中，12:00 不命中。
     *
     * 起止相同时判定为「未设置」，返回 false（原版同样要求 `0 < span < 1440`）。
     */
    fun inQuietHours(nowMinute: Int): Boolean {
        if (!enabled || !quietEnabled) return false
        val start = quietStart
        val end = quietEnd
        if (start == end) return false
        val span = ((end - start) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        if (span <= 0 || span >= MINUTES_PER_DAY) return false
        val offset = ((nowMinute - start) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return offset < span
    }

    /** 把「当天第几分钟」格式化成 `HH:mm` */
    fun format(minute: Int): String {
        val m = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 设置页「通知」那一行的副标题 */
    fun summary(context: Context): String {
        if (!enabled) return context.getString(R.string.notify_settings_summary_off)
        if (!ImNotify.hasPermission(context)) {
            return context.getString(R.string.notify_settings_summary_no_permission)
        }
        if (quietEnabled && quietStart != quietEnd) {
            return context.getString(
                R.string.notify_settings_summary_quiet,
                format(quietStart),
                format(quietEnd),
            )
        }
        return context.getString(R.string.notify_settings_summary_on)
    }
}
