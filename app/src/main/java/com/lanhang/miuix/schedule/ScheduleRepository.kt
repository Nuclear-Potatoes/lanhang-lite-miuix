package com.lanhang.miuix.schedule

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.lanhang.miuix.R
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.ApiException
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.widget.ScheduleWidgetUpdater
import java.io.File

/**
 * 课表数据仓库：
 * - 首次加载：优先读本地缓存，无缓存则网络拉取
 * - 刷新：网络拉取并覆盖缓存
 */
object ScheduleRepository {
    private const val TAG = "ScheduleRepo"
    private val gson = Gson()
    private lateinit var cacheFile: File
    private lateinit var appDir: File

    fun init(context: Context) {
        appDir = context.filesDir
        cacheFile = File(appDir, "schedule_cache.json")
    }

    /** 读本地缓存；null 表示无缓存或解析失败 */
    fun loadCached(): ScheduleResponse? {
        if (!cacheFile.exists()) return null
        return try {
            val json = cacheFile.readText()
            gson.fromJson(json, ScheduleResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "loadCached failed: $e")
            null
        }
    }

    /** 读某周缓存；null 表示无缓存或解析失败 */
    fun loadCachedWeek(week: Int): ScheduleResponse? {
        val f = File(appDir, "schedule_w$week.json")
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(), ScheduleResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "loadCachedWeek $week failed: $e")
            null
        }
    }

    fun cacheLastModified(): Long {
        if (!::cacheFile.isInitialized) return 0L
        return if (cacheFile.exists()) cacheFile.lastModified() else 0L
    }

    /**
     * 网络拉取。week=null 表示不带周次参数（拿当前周次元信息），
     * 带周次则对齐官方 App 的 GetSchedule&week=N 请求。
     */
    suspend fun refresh(week: Int? = null, updateWidget: Boolean = true): ScheduleResponse {
        // 确保 miniapp 会话有效
        if (!hasMiniappSession()) {
            val ok = MiniappApi.handshake(Session.deviceId(AppContext.instance))
            if (!ok) {
                throw ApiException(-1, AppContext.instance.getString(R.string.error_schedule_login))
            }
        }
        val raw = MiniappApi.getScheduleRaw(week)
        val resp = gson.fromJson(raw, ScheduleResponse::class.java)
        if (resp?.code != 0 || resp.data == null) {
            throw ApiException(
                resp?.code ?: -1,
                resp?.msg ?: AppContext.instance.getString(R.string.error_schedule_request)
            )
        }
        if (week == null) {
            cacheFile.writeText(raw)
            val currentWeek = resp.data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 0
            if (currentWeek > 0) {
                Session.scheduleWeekAnchorAt = System.currentTimeMillis()
                Session.scheduleWeekAnchorWeek = currentWeek
            }
        } else {
            File(appDir, "schedule_w$week.json").writeText(raw)
        }
        Log.d(TAG, "refreshed week=$week ${raw.length} bytes")
        if (updateWidget) ScheduleWidgetUpdater.updateAll(AppContext.instance)
        return resp
    }

    /** 简易判断是否已有 miniapp 会话 Cookie */
    private fun hasMiniappSession(): Boolean {
        val cookies = ApiClient.allCookies()
        return cookies.keys.any { it.contains("miniapp") || it.contains("nchu.edu.cn") }
    }
}

/** 供 Repository 获取全局 Context（App 初始化时注入） */
object AppContext {
    lateinit var instance: Context
        private set

    fun init(context: Context) {
        instance = context.applicationContext
    }
}
