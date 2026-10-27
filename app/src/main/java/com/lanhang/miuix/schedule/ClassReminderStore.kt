package com.lanhang.miuix.schedule

import android.content.Context
import android.content.SharedPreferences
import com.lanhang.miuix.R

/**
 * 上课提醒的用户设置（移植自上游 `ClassReminderStore`）。
 *
 * 只存**用户意图**（开不开、提前几分钟），不存「已排了哪些闹钟」——
 * requestCode 是窗口内序号（见 [ClassReminder]），每次重排按码段整扫即可完成对账，
 * 再加一份账本反而多一个会和系统对不上的状态。
 */
object ClassReminderStore {

    /** 提前量选项（分钟）。0 = 上课那一刻提醒，只给「就在教学楼上课」的人用，所以不做默认 */
    val ADVANCE_OPTIONS = listOf(0, 5, 10, 15, 20, 30)

    private const val DEFAULT_ADVANCE = 15

    private const val FILE = "lanhang_class_reminder"

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /**
     * 总开关。**始终反映用户意图**：没有通知权限时也保持打开（页面上给一行红字），
     * 不要「偷偷弹回去」—— 那会让人觉得「点了没反应」。
     */
    var enabled: Boolean
        get() = sp?.getBoolean("enabled", false) ?: false
        set(value) {
            sp?.edit()?.putBoolean("enabled", value)?.apply()
        }

    /** 提前几分钟。破损值（被别的版本写坏 / 手工改过）回落到默认，不让界面显示一个不存在的选项 */
    var advanceMinutes: Int
        get() {
            val raw = sp?.getInt("advance_minutes", DEFAULT_ADVANCE) ?: DEFAULT_ADVANCE
            return if (raw in 0..60) raw else DEFAULT_ADVANCE
        }
        set(value) {
            sp?.edit()?.putInt("advance_minutes", value.coerceIn(0, 60))?.apply()
        }

    /** 是否已经跳过一次精确闹钟授权页（避免每次进页面都把用户弹出去） */
    var exactAsked: Boolean
        get() = sp?.getBoolean("exact_asked", false) ?: false
        set(value) {
            sp?.edit()?.putBoolean("exact_asked", value)?.apply()
        }

    /** `15 分钟前` / `上课时` */
    fun advanceLabel(context: Context): String {
        val minutes = advanceMinutes
        return if (minutes <= 0) {
            context.getString(R.string.class_reminder_advance_zero)
        } else {
            context.getString(R.string.class_reminder_advance_value, minutes)
        }
    }

    /** 设置页那一行的副标题：`已开启 · 15 分钟前` / `已开启 · 缺少通知权限` / `已关闭` */
    fun summary(context: Context): String {
        if (!enabled) return context.getString(R.string.class_reminder_summary_off)
        if (!ClassReminderNotify.canNotify(context)) {
            return context.getString(R.string.class_reminder_summary_blocked)
        }
        return context.getString(R.string.class_reminder_summary_on, advanceLabel(context))
    }
}
