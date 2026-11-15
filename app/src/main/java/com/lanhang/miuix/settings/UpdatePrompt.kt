package com.lanhang.miuix.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.miuix.showMiuixInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「检查更新」的统一出口（2026-11-15 第八十三轮）。
 *
 * 为什么要有这一层：检查更新原来只有 `MineScreen` 的关于页里那一坨（取版本号 → IO 里问 GitHub →
 * 按结果弹三种弹窗）。现在加「打开 App 自动检查一次」，如果两边各写一遍，文案、按钮、
 * 失败口径很快就会分叉 —— 用户明确要求「与关于页中的检查更新走一个渠道」，所以把整条链路
 * 收进这里，关于页和启动都只调它，`UpdateChecker` 保持纯粹的「查」。
 *
 * 三件事刻意做成「静默」：
 *  · **启动时只在新版本存在时弹窗**。GitHub 在国内经常连不上，启动时弹一句「检查失败」纯属打扰；
 *  · 同一个进程只自动查一次（[startupChecked]），`recreate()` / 切深浅色不会重复弹；
 *  · 手动检查（关于页）才回「已是最新 / 检查失败」，那是用户主动点出来的，必须有反馈。
 */
object UpdatePrompt {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 本次进程是否已经做过启动检查（旋转屏幕 / 主题切换会重建 Activity，不能重复弹） */
    @Volatile
    private var startupChecked = false

    /** 正在检查中：避免启动检查与用户手动点击同时发出两个请求 */
    @Volatile
    private var inFlight = false

    /** 读当前安装包的 versionName；读不到给空串（调用方据此跳过检查） */
    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * App 冷启动后自动检查一次。
     *
     * 延后 1.2 秒再发：启动那一瞬间课表缓存、IM 连接、通知渠道都在抢主线程和带宽，
     * 更新检查是最不重要的一件，排到首屏之后。失败/最新一律不打扰用户。
     */
    fun checkOnStartup(activity: Activity) {
        if (startupChecked) return
        startupChecked = true
        val version = appVersion(activity)
        if (version.isBlank()) return
        android.util.Log.i("UpdatePrompt", "启动检查更新 version=$version")
        activity.window?.decorView?.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            checkAndPrompt(activity, version, silent = true)
        }, 1200L)
    }

    /**
     * 查一次并按结果弹窗。
     *
     * @param silent true = 只有「有新版本」才弹（启动检查用）；false = 三种结果都给反馈（关于页用）
     * @param onChecking 检查开始/结束回调（关于页把「正在检查…」写在行尾）
     * @param onHint 结果摘要回调（关于页行尾的「有新版 x.y.z / 已是最新 / 检查失败」）
     */
    fun checkAndPrompt(
        context: Context,
        currentVersion: String,
        silent: Boolean = false,
        onChecking: (Boolean) -> Unit = {},
        onHint: (String) -> Unit = {},
    ) {
        if (inFlight) return
        inFlight = true
        onChecking(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(currentVersion) }
            inFlight = false
            onChecking(false)
            // 日志里用固定标签而不是 javaClass.simpleName：release 走 R8，类名会被混淆成 a/b/e
            val label = when (result) {
                is UpdateChecker.Result.Available -> "Available"
                UpdateChecker.Result.Latest -> "Latest"
                is UpdateChecker.Result.Failed -> "Failed"
            }
            val extra = if (result is UpdateChecker.Result.Available) {
                " latest=" + result.release.version
            } else {
                ""
            }
            android.util.Log.i("UpdatePrompt", "检查结果 " + label + " current=" + currentVersion + extra)
            when (result) {
                is UpdateChecker.Result.Available -> {
                    val release = result.release
                    onHint(context.getString(R.string.settings_check_update_available, release.version))
                    val notes = release.notes.trim()
                    val message = buildString {
                        append(
                            context.getString(
                                R.string.settings_check_update_message,
                                currentVersion,
                                release.version,
                            )
                        )
                        if (notes.isNotEmpty()) {
                            append("\n\n")
                            append(notes.take(300))
                            if (notes.length > 300) append('…')
                        }
                    }
                    showMiuixConfirm(
                        context = context,
                        title = context.getString(R.string.settings_check_update_available_title),
                        message = message,
                        confirmText = context.getString(R.string.settings_check_update_download),
                        dismissText = context.getString(R.string.settings_check_update_later),
                        onConfirm = {
                            // 优先给 APK 直链（点了就开始下），没有就退回 release 页
                            openUrl(context, release.apkUrl ?: release.pageUrl)
                        },
                    )
                }

                UpdateChecker.Result.Latest -> {
                    onHint(context.getString(R.string.settings_check_update_latest))
                    if (silent) return@launch
                    showMiuixInfo(
                        context = context,
                        title = context.getString(R.string.settings_check_update),
                        message = context.getString(
                            R.string.settings_check_update_latest_message,
                            currentVersion,
                        ),
                        confirm = context.getString(R.string.common_ok),
                    )
                }

                is UpdateChecker.Result.Failed -> {
                    onHint(context.getString(R.string.settings_check_update_failed))
                    if (silent) return@launch
                    showMiuixInfo(
                        context = context,
                        title = context.getString(R.string.settings_check_update),
                        message = context.getString(
                            R.string.settings_check_update_failed_message,
                            result.message,
                        ),
                        confirm = context.getString(R.string.common_ok),
                    )
                }
            }
        }
    }

    private fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
