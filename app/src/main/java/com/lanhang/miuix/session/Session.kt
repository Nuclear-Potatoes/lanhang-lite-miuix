package com.lanhang.miuix.session

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

object Session {
    private const val FILE = "lanhang_session"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(value) = sp.edit().putString("username", value).apply()

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(value) = sp.edit().putString("name", value).apply()

    var userId: String
        get() = sp.getString("userId", "") ?: ""
        set(value) = sp.edit().putString("userId", value).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value).apply()

    // 融云圈发表动态的可见范围（org_ids）用公司 id
    var companyId: String
        get() = sp.getString("companyId", "") ?: ""
        set(value) = sp.edit().putString("companyId", value).apply()

    // 我的资料：登录后 /staffs/{id} 拉一次，我的页与身份码共用（失败就留空，页面自己降级）
    var staffNo: String
        get() = sp.getString("staffNo", "") ?: ""
        set(value) = sp.edit().putString("staffNo", value).apply()

    var department: String
        get() = sp.getString("department", "") ?: ""
        set(value) = sp.edit().putString("department", value).apply()

    var avatarUrl: String
        get() = sp.getString("avatarUrl", "") ?: ""
        set(value) = sp.edit().putString("avatarUrl", value).apply()

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(value) = sp.edit().putString("email", value).apply()

    /** 办事大厅门户地址（来自 /configuration/all 的 features.work.url） */
    var workUrl: String
        get() = sp.getString("workUrl", "") ?: ""
        set(value) = sp.edit().putString("workUrl", value).apply()

    /** 融云 IM token（私信用；失效后由 ImRepository 重新拉） */
    var imToken: String
        get() = sp.getString("imToken", "") ?: ""
        set(value) = sp.edit().putString("imToken", value).apply()

    var ldapToken: String
        get() = sp.getString("ldapToken", "") ?: ""
        set(value) = sp.edit().putString("ldapToken", value).apply()

    var scheduleUrl: String
        get() = sp.getString("scheduleUrl", "") ?: ""
        set(value) = sp.edit().putString("scheduleUrl", value).apply()

    var gradesUrl: String
        get() = sp.getString("gradesUrl", "") ?: ""
        set(value) = sp.edit().putString("gradesUrl", value).apply()

    // 成绩页当前选中的学年学期（如 "2025-2026-1"）
    var gradesTerm: String
        get() = sp.getString("gradesTerm", "") ?: ""
        set(value) = sp.edit().putString("gradesTerm", value).apply()

    // 主题模式："light" | "dark" | "system"（默认跟随系统）
    var themeMode: String
        get() = sp.getString("themeMode", "system") ?: "system"
        set(value) = sp.edit().putString("themeMode", value).apply()

    // 界面语言："system" | "zh" | "en"（默认跟随系统语言）
    var language: String
        get() = sp.getString("language", "system") ?: "system"
        set(value) = sp.edit().putString("language", value).apply()

    var scheduleWeekAnchorAt: Long
        get() = sp.getLong("scheduleWeekAnchorAt", 0L)
        set(value) = sp.edit().putLong("scheduleWeekAnchorAt", value).apply()

    var scheduleWeekAnchorWeek: Int
        get() = sp.getInt("scheduleWeekAnchorWeek", 0)
        set(value) = sp.edit().putInt("scheduleWeekAnchorWeek", value).apply()

    /** 最近一次向上推送小部件「生成式预览」(API 35+) 的时间戳 — 本地限频用 */
    var widgetPreviewPushedAt: Long
        get() = sp.getLong("widgetPreviewPushedAt", 0L)
        set(value) = sp.edit().putLong("widgetPreviewPushedAt", value).apply()

    val isLoggedIn: Boolean get() = token.isNotEmpty()

    // 与官方 App 版本号保持一致（UA 指纹用）
    const val appVersion = "1.0.14"

    fun clear() {
        sp.edit().clear().apply()
    }

    fun deviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }
}
