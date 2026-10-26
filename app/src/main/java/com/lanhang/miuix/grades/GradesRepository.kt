package com.lanhang.miuix.grades

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.lanhang.miuix.R
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.ApiException
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import java.io.File

/**
 * 成绩数据仓库（与 ScheduleRepository 同思路）：
 * - miniapp 会话复用课表那套握手（同一站点 id=78，state=/score）
 * - 缓存 filesDir/grades_semesters.json 与 grades_<term>.json
 */
object GradesRepository {
    private const val TAG = "GradesRepo"
    private val gson = Gson()
    private lateinit var semestersFile: File
    private lateinit var cacheDir: File

    fun init(context: Context) {
        cacheDir = context.filesDir
        semestersFile = File(context.filesDir, "grades_semesters.json")
    }

    private fun scoresFile(term: String) = File(cacheDir, "grades_$term.json")

    // ---- 学期列表 ----

    fun loadCachedSemesters(): List<GradeSemester>? {
        if (!semestersFile.exists()) return null
        return try {
            gson.fromJson(semestersFile.readText(), XnxqListResponse::class.java)?.data
        } catch (e: Exception) {
            Log.e(TAG, "loadCachedSemesters failed: $e")
            null
        }
    }

    suspend fun refreshSemesters(): List<GradeSemester> {
        ensureSession()
        val raw = MiniappApi.getXnxqListRaw()
        val resp = gson.fromJson(raw, XnxqListResponse::class.java)
        if (resp?.code != 0 || resp.data == null) {
            throw ApiException(
                resp?.code ?: -1,
                resp?.msg ?: AppContext.instance.getString(R.string.error_grades_terms)
            )
        }
        semestersFile.writeText(raw)
        return resp.data
    }

    // ---- 某学期成绩 ----

    fun loadCachedScores(term: String): List<GradeItem>? {
        val f = scoresFile(term)
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(), ScoreResponse::class.java)?.data
        } catch (e: Exception) {
            Log.e(TAG, "loadCachedScores failed: $e")
            null
        }
    }

    suspend fun refreshScores(term: String): List<GradeItem> {
        ensureSession()
        val raw = MiniappApi.getStudentScoreRaw(term)
        val resp = gson.fromJson(raw, ScoreResponse::class.java)
        if (resp?.code != 0 || resp.data == null) {
            throw ApiException(
                resp?.code ?: -1,
                resp?.msg ?: AppContext.instance.getString(R.string.error_grades_scores)
            )
        }
        scoresFile(term).writeText(raw)
        Log.d(TAG, "refreshed scores $term: ${resp.data.size} items")
        return resp.data
    }

    private suspend fun ensureSession() {
        if (!hasMiniappSession()) {
            val ok = MiniappApi.handshake(Session.deviceId(AppContext.instance))
            if (!ok) throw ApiException(-1, AppContext.instance.getString(R.string.error_relogin))
        }
    }

    private fun hasMiniappSession(): Boolean {
        val cookies = ApiClient.allCookies()
        return cookies.keys.any { it.contains("miniapp") || it.contains("nchu.edu.cn") }
    }
}
