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
    val ADVANCE_OPTIONS = listOf(0, 5, 10, 15, 20, 30, 45, 60)

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

    /**
     * 超级岛提醒（HyperOS 焦点通知）。
     *
     * 默认**开**：本机不支持时 [ClassReminderIsland.isSupported] 会把它挡掉，
     * 走普通通知，不会因为「开了但机器不认」而丢提醒。
     */
    var islandEnabled: Boolean
        get() = sp?.getBoolean("island_enabled", true) ?: true
        set(value) {
            sp?.edit()?.putBoolean("island_enabled", value)?.apply()
        }

    /** 次日课程提醒的提醒整点（24 小时制） */
    val NEXT_DAY_HOURS = listOf(19, 20, 21, 22, 23)

    /** 课中提醒「距下课 N 分钟开始」的 N */
    val BEFORE_END_OPTIONS = listOf(5, 10, 15)

    /** 上课勿扰档位（照 Nexio 的三档，filter 用系统的 INTERRUPTION_FILTER_*） */
    enum class DndLevel(val key: String, val filter: Int) {
        SILENT("silent", 4),      // INTERRUPTION_FILTER_ALARMS
        PRIORITY("priority", 2),  // INTERRUPTION_FILTER_PRIORITY
        TOTAL("total", 3),        // INTERRUPTION_FILTER_NONE
    }

    /** 缩略态精简口径 */
    enum class ThumbScope(val key: String) { ORIGINAL("original"), ROOM("room"), COURSE("course"), BOTH("both") }

    private fun bool(key: String, def: Boolean) = sp?.getBoolean(key, def) ?: def
    private fun putBool(key: String, value: Boolean) { sp?.edit()?.putBoolean(key, value)?.apply() }
    private fun str(key: String, def: String) = sp?.getString(key, def) ?: def
    private fun putStr(key: String, value: String) { sp?.edit()?.putString(key, value)?.apply() }

    /** 课中提醒：上课中在通知 / 超级岛里显示距下课的时间 */
    var inClassEnabled: Boolean
        get() = bool("in_class", true)
        set(value) = putBool("in_class", value)

    /** 课中提醒时机：true = 全程，false = 距下课 N 分钟才开始显示 */
    var inClassFull: Boolean
        get() = bool("in_class_full", true)
        set(value) = putBool("in_class_full", value)

    var inClassBeforeEndMinutes: Int
        get() = sp?.getInt("in_class_before_end", 10) ?: 10
        set(value) { sp?.edit()?.putInt("in_class_before_end", value.coerceIn(1, 30))?.apply() }

    /** 次日课程提醒（每晚固定时间提示第二天的课） */
    var nextDayEnabled: Boolean
        get() = bool("next_day", false)
        set(value) = putBool("next_day", value)

    var nextDayHour: Int
        get() = sp?.getInt("next_day_hour", 21) ?: 21
        set(value) { sp?.edit()?.putInt("next_day_hour", value.coerceIn(0, 23))?.apply() }

    /** 上课自动勿扰 */
    var autoDnd: Boolean
        get() = bool("auto_dnd", false)
        set(value) = putBool("auto_dnd", value)

    var dndLevel: DndLevel
        get() {
            val raw = str("dnd_level", DndLevel.SILENT.key)
            return DndLevel.entries.firstOrNull { it.key == raw } ?: DndLevel.SILENT
        }
        set(value) = putStr("dnd_level", value.key)

    /** 超级岛左侧内容：course / room */
    var islandLeft: String
        get() = str("island_left", "course")
        set(value) = putStr("island_left", value)

    /** 课中岛右侧：remain = 距下课倒计时，none = 不显示 */
    var inClassRight: String
        get() = str("island_in_class_right", "remain")
        set(value) = putStr("island_in_class_right", value)

    /** 息屏显示（aodTitle） */
    var islandAod: Boolean
        get() = bool("island_aod", true)
        set(value) = putBool("island_aod", value)

    /** 缩略态精简口径 */
    var thumbScope: ThumbScope
        get() {
            val raw = str("thumb_scope", ThumbScope.ORIGINAL.key)
            return ThumbScope.entries.firstOrNull { it.key == raw } ?: ThumbScope.ORIGINAL
        }
        set(value) = putStr("thumb_scope", value.key)

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
