package com.lanhang.miuix.widget

import android.content.Context
import java.time.LocalDate

/**
 * [TodayNavCore] 的 SharedPreferences 读写壳。
 *
 * - [target] 只读：该实例当前应渲染的日期（无状态 → 今天）
 * - [shift] 前进/后退一天并持久化（锚点刷新为今天）
 * - [reset] 回到今天（删除该实例的两条 key，等价无状态）
 *
 * 写路径加锁：[shift]/[reset] 都是「读全量 → 改一条 → 清空重写全量」，
 * 快速连点两次会并发进入，后写者会用旧快照覆盖前者的推进 → 丢一次翻页。
 */
object TodayNavStore {

    private val writeLock = Any()

    fun target(context: Context, widgetId: Int, today: LocalDate = LocalDate.now()): LocalDate =
        TodayNavCore.targetDate(read(context, widgetId), today)

    fun shift(
        context: Context,
        widgetId: Int,
        deltaDays: Long,
        today: LocalDate = LocalDate.now()
    ) {
        synchronized(writeLock) {
            val entry = TodayNavCore.shift(read(context, widgetId), today.toEpochDay(), deltaDays)
            val raw = loadAll(context).toMutableMap()
            TodayNavCore.write(raw, widgetId, entry)
            saveAll(context, raw)
        }
    }

    fun reset(context: Context, widgetId: Int) {
        synchronized(writeLock) {
            val raw = loadAll(context).toMutableMap()
            TodayNavCore.delete(raw, widgetId)
            saveAll(context, raw)
        }
    }

    private fun read(context: Context, widgetId: Int): TodayNavCore.Entry? =
        TodayNavCore.read(loadAll(context), widgetId)

    private fun loadAll(context: Context): Map<String, Long> {
        val sp = context.getSharedPreferences(TodayNavCore.PREFS_NAME, Context.MODE_PRIVATE)
        return sp.all.mapNotNull { (k, v) -> if (v is Long) k to v else null }.toMap()
    }

    private fun saveAll(context: Context, data: Map<String, Long>) {
        val sp = context.getSharedPreferences(TodayNavCore.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit().clear()
        data.forEach { (k, v) -> editor.putLong(k, v) }
        editor.apply()
    }
}
