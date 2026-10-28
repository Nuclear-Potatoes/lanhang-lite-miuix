package com.lanhang.miuix.schedule

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 上课自动勿扰（移植自 Nexio 的 `ClassDndHelper`，只保留「上课开、下课恢复」这一条主线）。
 *
 * 用的是系统公开的 `setInterruptionFilter`，**不需要 Shizuku**，但需要用户在系统里
 * 授予「勿扰权限」（`ACCESS_NOTIFICATION_POLICY`，只能由用户手动开，代码弹不出来）。
 * 没有权限就什么都不做，也不改任何设置 —— 绝不假装成功。
 *
 * 「原来的档位」只在第一次进入勿扰时记一次，下课后原样恢复；
 * 连上两节课时第二节课开始不会把「上节课前的档位」覆盖成「勿扰」。
 */
object ClassDnd {

    private const val TAG = "ClassDnd"
    private const val FILE = "lanhang_class_dnd"
    private const val KEY_PREV = "previous_filter"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun nm(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    fun canControl(context: Context): Boolean =
        nm(context)?.isNotificationPolicyAccessGranted == true

    /** 跳到系统的「勿扰权限」页（唯一能让用户开这个权限的入口） */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 上课：切到设定的档位。返回是否真的生效 */
    fun apply(context: Context, level: ClassReminderStore.DndLevel): Boolean {
        val manager = nm(context) ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        return runCatching {
            if (!sp(context).contains(KEY_PREV)) {
                sp(context).edit().putInt(KEY_PREV, manager.currentInterruptionFilter).apply()
            }
            manager.setInterruptionFilter(level.filter)
            Log.d(TAG, "apply ${level.key}")
            true
        }.getOrElse {
            Log.w(TAG, "apply failed: ${it.message}")
            false
        }
    }

    /** 下课：恢复上课前的档位 */
    fun restore(context: Context) {
        val manager = nm(context) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val prefs = sp(context)
        if (!prefs.contains(KEY_PREV)) return
        val previous = prefs.getInt(KEY_PREV, NotificationManager.INTERRUPTION_FILTER_ALL)
        runCatching { manager.setInterruptionFilter(previous) }
        prefs.edit().remove(KEY_PREV).apply()
        Log.d(TAG, "restore $previous")
    }
}
