package com.lanhang.miuix.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class ScheduleWidgetProvider : AppWidgetProvider() {

    /** 排版变体：false=全周课表（4×4），true=课表·小（2×2，今日列表紧凑档） */
    open val isSmall: Boolean = false

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        ScheduleWidgetUpdater.updateAll(context)
        ScheduleWidgetUpdater.scheduleMidnight(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        ScheduleWidgetUpdater.updateWidget(context, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleWidgetUpdater.updateAll(context)
        ScheduleWidgetUpdater.scheduleMidnight(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 本组件最后一个实例移除时，另一组件（课表·小）可能还有实例在用午夜闹钟
        if (ScheduleWidgetUpdater.activeCount(context) == 0) {
            ScheduleWidgetUpdater.cancelMidnight(context)
            ScheduleWidgetUpdater.cancelTick(context)
        }
    }

    /** 实例被移除时清掉它的翻天状态（按 id 隔离，避免残留 key 影响新实例） */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) TodayNavStore.reset(context, id)
        // 没有实例了就别再排分钟心跳
        if (ScheduleWidgetUpdater.activeCount(context) == 0) {
            ScheduleWidgetUpdater.cancelTick(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ScheduleWidgetUpdater.ACTION_MIDNIGHT -> ScheduleWidgetUpdater.updateAll(context)
            // 分钟心跳：只重绘，不推生成式预览（系统侧限频）
            ScheduleWidgetUpdater.ACTION_TICK ->
                ScheduleWidgetUpdater.updateAll(context, pushPreview = false)
            Intent.ACTION_BOOT_COMPLETED -> {
                ScheduleWidgetUpdater.updateAll(context)
                ScheduleWidgetUpdater.scheduleMidnight(context)
            }
            ScheduleWidgetUpdater.ACTION_PREV_DAY,
            ScheduleWidgetUpdater.ACTION_NEXT_DAY,
            ScheduleWidgetUpdater.ACTION_RESET_DAY -> handleNav(context, intent)
        }
    }

    /** 翻天点击：goAsync 里推进状态并重绘（跨周可能补拉一次数据，失败也不影响已渲染结果） */
    private fun handleNav(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduleWidgetUpdater.handleNavAction(context, intent)
            } catch (e: Throwable) {
                Log.e(TAG, "nav action failed: ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ScheduleWidget"
    }
}
