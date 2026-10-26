package com.lanhang.miuix.settings

import android.content.Context
import android.os.Build
import com.lanhang.miuix.R

object Diagnostics {
    @Synchronized
    fun record(context: Context, area: String, error: Throwable) {
        runCatching {
            val file = java.io.File(context.filesDir, "diagnostics.log")
            if (file.length() > 64 * 1024) file.writeText("")
            val message = redact(context, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            file.appendText("${System.currentTimeMillis()} [$area] $message\n")
        }
    }

    fun report(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val pid = android.os.Process.myPid()
        val raw = runCatching {
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=$pid", "-t", "500", "-v", "time", "*:W")
            ).inputStream.bufferedReader().use { it.readText() }
        }.getOrElse {
            context.getString(R.string.diagnostics_log_failed, it.javaClass.simpleName)
        }

        val safeLines = raw.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { line ->
                val lower = line.lowercase()
                lower.contains("okhttp") || lower.contains("authorization") ||
                    lower.contains("moments_last") || lower.contains("cookies_debug")
            }
            .map { redact(context, it) }
            .toList()
            .takeLast(250)
        val crash = runCatching {
            context.filesDir.listFiles { _, name -> name.startsWith("crash-") }
                ?.maxByOrNull { it.name }
                ?.takeIf { it.exists() }
                ?.readText()
                ?.take(6000)
        }.getOrNull()
        val appErrors = runCatching {
            java.io.File(context.filesDir, "diagnostics.log")
                .takeIf { it.exists() }
                ?.readLines()
                .orEmpty()
                .takeLast(100)
        }.getOrDefault(emptyList())

        return buildString {
            appendLine(context.getString(R.string.diagnostics_title))
            appendLine(context.getString(R.string.diagnostics_version, version))
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(
                context.getString(
                    R.string.diagnostics_device,
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                )
            )
            appendLine(
                context.getString(
                    R.string.diagnostics_time,
                    java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US
                    ).format(java.util.Date())
                )
            )
            appendLine(context.getString(R.string.diagnostics_scope))
            appendLine(context.getString(R.string.diagnostics_app_errors))
            appErrors.forEach(::appendLine)
            // 崩溃现场（2026-10-24 加）：App 的未捕获异常会落到 filesDir/crash-*.txt，
            // 这里带进报告，用户「设置 → 诊断与日志 → 分享」就能把堆栈发出来。
            if (!crash.isNullOrBlank()) {
                appendLine(context.getString(R.string.diagnostics_crash))
                appendLine(crash)
            }
            appendLine(context.getString(R.string.diagnostics_log_begin))
            safeLines.forEach(::appendLine)
            appendLine(context.getString(R.string.diagnostics_log_end))
        }.take(48 * 1024)
    }

    private fun redact(context: Context, line: String): String {
        val hidden = context.getString(R.string.diagnostics_redacted)
        val credential = Regex(
            "(?i)(cookie|authorization|access[_-]?token|ldap[_-]?token|password|user_id)\\s*[=:]\\s*[^\\s,}]+"
        )
        return line
            .replace(credential) { match -> "${match.groupValues[1]}=$hidden" }
            .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer $hidden")
    }
}
