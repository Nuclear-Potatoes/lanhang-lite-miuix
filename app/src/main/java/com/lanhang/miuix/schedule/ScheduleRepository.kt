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

    /**
     * 全学期出现过的课程名（去重、按名字排序）。
     *
     * 第二十八轮（用户：「扩展课表中课程选色的多样性……不要有课程的颜色出现重复」）：
     * 要保证「不同课不同色」，就必须先知道本学期到底有哪些课 —— 按名字 hashCode 取色相必然撞色
     * （高数B1 和 航空航天概论 就是同一档绿）。所以这里扫一遍已缓存的周文件
     * （`schedule_w{N}.json`），把课程名收齐，交给 [CourseColors] 均分色相。
     *
     * 只读缓存、不发网络请求；缓存里没有的周次直接跳过。
     */
    fun allCachedCourseNames(): List<String> {
        if (!::appDir.isInitialized) return emptyList()
        val names = sortedSetOf<String>()
        val meta = loadCached()?.data
        fun collect(d: ScheduleData?) {
            d?.schedule?.forEach { day ->
                day.periods?.forEach { period ->
                    period.courses?.forEach { course ->
                        val n = course.name?.trim().orEmpty()
                        if (n.isNotEmpty()) names.add(n)
                    }
                }
            }
        }
        collect(meta)
        val first = meta?.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val last = meta?.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: first
        if (last >= first) {
            for (w in first..last) collect(loadCachedWeek(w)?.data)
        }
        return names.toList()
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
