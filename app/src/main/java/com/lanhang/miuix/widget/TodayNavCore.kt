package com.lanhang.miuix.widget

import java.time.LocalDate

/**
 * 「今日课程」小部件按天导航的纯逻辑核心（无 Android 依赖，可直接 JVM 单测）。
 *
 * 语义参考 Sleepy（github.com/lingion/sleepy）`TodayDateNavCore`，本项目独立实现：
 * 每个 appWidgetId 存一对 (selectedEpochDay, anchorEpochDay)：
 * - `anchor == 今天` → 用户处于导航态，渲染 `selected`
 * - `anchor != 今天`（跨天失效）→ 回到今天，避免隔天还停在昨天
 * - 偏移钳制在 ±[MAX_ABS_OFFSET_DAYS] 天，防止连点把日期推到荒谬位置
 */
internal object TodayNavCore {

    /** SharedPreferences 文件名（与 [TodayNavStore] 保持一致） */
    const val PREFS_NAME = "widget_today_nav"

    private const val KEY_SEL_PREFIX = "today_nav_sel_"
    private const val KEY_ANCHOR_PREFIX = "today_nav_anchor_"

    /** 相对今天的最大偏移天数（前/后对称），约一个学期 */
    const val MAX_ABS_OFFSET_DAYS = 92L

    data class Entry(val selectedEpochDay: Long, val anchorEpochDay: Long)

    fun keySel(widgetId: Int): String = "$KEY_SEL_PREFIX$widgetId"

    fun keyAnchor(widgetId: Int): String = "$KEY_ANCHOR_PREFIX$widgetId"

    /** 实际该渲染的日期：锚点有效取 selected，失效（跨天）取今天 */
    fun resolveTargetEpochDay(entry: Entry?, todayEpochDay: Long): Long =
        if (entry != null && entry.anchorEpochDay == todayEpochDay) {
            entry.selectedEpochDay
        } else {
            todayEpochDay
        }

    /**
     * 从当前状态沿 deltaDays（前一天 -1 / 后一天 +1）推进。
     * 锚点失效的 entry 视为无状态、以今天为基准；锚点一律刷新为今天。
     */
    fun shift(entry: Entry?, todayEpochDay: Long, deltaDays: Long): Entry {
        val base = resolveTargetEpochDay(entry, todayEpochDay)
        val offset = (base - todayEpochDay + deltaDays)
            .coerceIn(-MAX_ABS_OFFSET_DAYS, MAX_ABS_OFFSET_DAYS)
        return Entry(selectedEpochDay = todayEpochDay + offset, anchorEpochDay = todayEpochDay)
    }

    /** 读取；sel 或 anchor 缺一视为无状态（读侧容错） */
    fun read(raw: Map<String, Long>, widgetId: Int): Entry? {
        val sel = raw[keySel(widgetId)] ?: return null
        val anchor = raw[keyAnchor(widgetId)] ?: return null
        return Entry(sel, anchor)
    }

    fun write(raw: MutableMap<String, Long>, widgetId: Int, entry: Entry) {
        raw[keySel(widgetId)] = entry.selectedEpochDay
        raw[keyAnchor(widgetId)] = entry.anchorEpochDay
    }

    fun delete(raw: MutableMap<String, Long>, widgetId: Int) {
        raw.remove(keySel(widgetId))
        raw.remove(keyAnchor(widgetId))
    }

    fun targetDate(entry: Entry?, today: LocalDate): LocalDate =
        LocalDate.ofEpochDay(resolveTargetEpochDay(entry, today.toEpochDay()))
}
