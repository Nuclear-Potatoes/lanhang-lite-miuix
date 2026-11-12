package com.lanhang.miuix.settings

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「检查更新」（2026-11-03 第六十轮，用户第 3 条「参考上游项目，做一下检查更新功能」）。
 *
 * 上游 lanhang-lite 的发版方式是 GitHub Releases：Action 打好 APK、写 tag、附上 SHA256。
 * 本项目 fork 之后沿用同一套（`Nuclear-Potatoes/lanhang-lite-miuix`），所以检查更新就是
 * 拉一次 GitHub 的 `releases/latest`，把 `tag_name` 里的版本号和本机 `versionName` 比一比。
 *
 * 设计上的三个取舍：
 *  · **不引新依赖**：OkHttp + Gson 项目里本来就有（IM 与课表接口在用），够用就不再拉别的库；
 *  · **不自动下载、不自动安装**：Android 装 APK 要 `REQUEST_INSTALL_PACKAGES` 还要用户逐次点
 *    「仍要安装」，与其做一个半残的静默更新，不如把「下载页」交给浏览器 —— 和上游一致；
 *  · **同步阻塞**：这里只发一次请求、只读一次 JSON，没有任何回调嵌套，
 *    放进 `Dispatchers.IO` 里跑最省事。调用方负责切线程（见 `MineScreen` 的 `onCheckUpdate`）。
 *
 * 网络不通、被墙、GitHub 限流（403）都只返回 [Result.Failed]，绝不抛异常给 UI 层，
 * 也绝不把「查不到」说成「已是最新」—— 这两件事对用户的意义完全不同。
 */
object UpdateChecker {

    private const val OWNER = "Nuclear-Potatoes"
    private const val REPO = "lanhang-lite-miuix"

    /** 最新 release 的 API；没有发过 release 时 GitHub 返回 404，按「已是最新」处理 */
    private const val LATEST_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 兜底入口：API 挂了也能让用户自己去看 */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** 一条 release 里我们真正要用的字段（其余 JSON 一概不解析） */
    data class Release(
        /** 原始 tag，形如 `v1.1.21` */
        val tag: String,
        /** 去掉前缀 v 的版本号，形如 `1.1.21` */
        val version: String,
        /** 发版标题；没写标题时退回 tag */
        val title: String,
        /** release 正文（更新说明），可能很长，弹窗里截断展示 */
        val notes: String,
        /** 附件里的 APK 直链；Action 没传 APK 时为 null */
        val apkUrl: String?,
        /** release 的网页地址，永远可用 */
        val pageUrl: String,
    )

    sealed interface Result {
        /** 有新版 */
        data class Available(val release: Release) : Result

        /** 已经是最新（或远端还没有任何 release） */
        data object Latest : Result

        /** 查不到 —— 网络异常 / 服务器错误 / 返回的 JSON 不像 release */
        data class Failed(val message: String) : Result
    }

    /**
     * 查一次最新版本。**必须在线程外调用**（内部是阻塞 IO）。
     *
     * @param currentVersion 本机的 `versionName`，例如 `1.1.20`
     */
    fun check(currentVersion: String): Result {
        val request = Request.Builder()
            .url(LATEST_API)
            // GitHub 对没有 User-Agent 的请求直接 403
            .header("User-Agent", "lanhang-miuix/$currentVersion")
            .header("Accept", "application/vnd.github+json")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    // 仓库还没发过 release：GitHub 给 404，这不是错误
                    response.code == 404 -> Result.Latest
                    response.code == 403 -> Result.Failed("请求太频繁，稍后再试")
                    !response.isSuccessful -> Result.Failed("服务器返回 ${response.code}")
                    else -> parse(currentVersion, response.body?.string().orEmpty())
                }
            }
        }.getOrElse { error ->
            Result.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络连接失败")
        }
    }

    /** 把 release JSON 变成 [Result]；字段缺失一律走兜底，不抛异常 */
    private fun parse(currentVersion: String, body: String): Result {
        if (body.isBlank()) return Result.Failed("返回内容为空")
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return Result.Failed("返回内容无法解析")
        val tag = root.get("tag_name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        if (tag.isBlank()) return Result.Failed("没有找到版本号")
        val title = root.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty().ifBlank { tag }
        val notes = root.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val pageUrl = root.get("html_url")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            .ifBlank { RELEASES_PAGE }
        val apkUrl = root.getAsJsonArray("assets")
            ?.asSequence()
            ?.mapNotNull { element ->
                val asset = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val assetName = asset.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val downloadUrl = asset.get("browser_download_url")
                    ?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                    downloadUrl
                } else {
                    null
                }
            }
            ?.firstOrNull()

        val release = Release(
            tag = tag,
            version = tag.trimStart('v', 'V'),
            title = title,
            notes = notes,
            apkUrl = apkUrl,
            pageUrl = pageUrl,
        )
        return if (isNewer(release.version, currentVersion)) {
            Result.Available(release)
        } else {
            Result.Latest
        }
    }

    /**
     * [latest] 是否比 [current] 新。逐段比数字，缺的段按 0 算。
     *
     * 不能直接比字符串：`1.1.9` > `1.1.20` 会判断成「前者更新」，而实际相反。
     * 也刻意不引入 `BigDecimal` 之类的 —— 版本号就是点分隔的整数，够用了。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 抽出版本号里的前四段数字（`v1.1.21` → `[1, 1, 21]`） */
    private fun numbers(version: String): List<Int> = Regex("\\d+")
        .findAll(version)
        .mapNotNull { it.value.toIntOrNull() }
        .take(4)
        .toList()
}
