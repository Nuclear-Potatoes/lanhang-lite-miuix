package com.lanhang.miuix

import android.app.Application
import com.lanhang.miuix.grades.GradesRepository
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.net.MomentsApi
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.schedule.ScheduleRepository
import com.lanhang.miuix.session.AppLanguage
import com.lanhang.miuix.session.Session

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        ApiClient.init(this)
        MiniappApi.init(this)
        MomentsApi.init(this)
        Session.init(this)
        // 旧版用 AppCompat 的 per-app language 会把语言写进系统设置，
        // 它的优先级高于我们 wrap 的语言，所以这里清一次，避免「跟随系统」被系统记录盖掉
        if (!androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            )
        }
        // 语言必须在任何 Activity 创建前 prime（见 LocaleHelper：不用系统的 per-app locale）
        AppLanguage.apply(Session.language)
        applyTheme()
        AppContext.init(this)
        ScheduleRepository.init(this)
        GradesRepository.init(this)
    }

    /**
     * 未捕获异常落盘：`filesDir/crash-<时间>.txt`（只留最近 5 个）。
     * adb 取：`adb shell run-as com.lanhang.miuix cat files/crash-<时间>.txt`
     * 2026-10-24 加：切页/对话框崩溃现场靠它定位，别再靠猜。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = android.util.Log.getStackTraceString(throwable)
                android.util.Log.e("LanhangCrash", text)
                val name = "crash-" + System.currentTimeMillis() + ".txt"
                val dir = filesDir
                java.io.File(dir, name).writeText(text, Charsets.UTF_8)
                // 同时写一份到 /sdcard/Android/data/<包名>/files/，用户在文件管理器里能直接拿到
                runCatching {
                    getExternalFilesDir(null)?.let { ext -> java.io.File(ext, name).writeText(text, Charsets.UTF_8) }
                }
                dir.listFiles { _, name -> name.startsWith("crash-") }
                    ?.sortedByDescending { it.name }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun applyTheme() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (Session.themeMode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
