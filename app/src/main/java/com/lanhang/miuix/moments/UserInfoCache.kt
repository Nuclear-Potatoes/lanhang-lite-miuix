package com.lanhang.miuix.moments

import com.lanhang.miuix.R
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.net.StaffBrief
import com.lanhang.miuix.schedule.AppContext
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作圈 / 私信共用的用户信息缓存：userId → 姓名/头像，批量解析走主 API POST /staffs/batch（code 10000）。
 *
 * 2026-10-26 第七轮：
 *  - **落盘**。之前只在内存，进程一重启就全丢，第一次进私信要等批量接口回来才有名字
 *    （用户报「姓名显示不出来」）。现在改过的资料写进 `filesDir/staff_cache.json`，
 *    下次启动先在后台读回来。
 *  - **可观察**。[revision] 是个 Compose 状态，每次资料变化 +1；列表里读一下它，
 *    批量接口一回来姓名立刻重绘，不用再等 20 秒轮询。
 */
object UserInfoCache {
    private val cache = ConcurrentHashMap<String, StaffBrief>()

    /** 资料版本号：Compose 侧读它就能在「名字/头像刚回来」时自动重绘 */
    var revision by mutableStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * **必须回主线程**再加。
     *
     * `merge()` 的调用方多半在后台（`withContext(Dispatchers.IO)` 里的批量接口、
     * `preload()` 的读盘线程）。Compose 的 snapshot 状态一律在主线程改，省得跨线程写状态表。
     */
    private fun bump() {
        val run = Runnable { revision++ }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private var loadStarted = false
    private val saving = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun file(): File = File(AppContext.instance.filesDir, "staff_cache.json")

    /**
     * 资料缓存**手写 JSON 映射**，不要用 Gson 反射。
     *
     * 坑（2026-10-26 第七轮）：Gson 是按**字段名**写盘的，而 R8 每次构建都会重命名字段。
     * 上一次构建写下的 `staff_cache.json`，下一次构建的 APK 就认不出键名了，`String` 字段反成 null，
     * 界面一读就崩在 `Parameter specified as non-null is null: method kotlin.text.StringsKt.…`。
     * 手写映射用固定键名，混淆/压缩/换版本都不会坏文件。
     */
    private fun encode(list: List<StaffBrief>): String {
        val arr = JsonArray()
        list.forEach { b ->
            val o = JsonObject()
            o.addProperty("id", b.id)
            o.addProperty("name", b.name)
            o.addProperty("portrait_url", b.portrait_url)
            o.addProperty("depart_name", b.depart_name)
            o.addProperty("duty_name", b.duty_name)
            o.addProperty("company_id", b.company_id)
            arr.add(o)
        }
        return arr.toString()
    }

    private fun decode(text: String): List<StaffBrief> = runCatching {
        val arr = JsonParser.parseString(text).takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        arr.mapNotNull { e ->
            runCatching {
                val o = e.asJsonObject
                StaffBrief(
                    id = o.nullableStr("id"),
                    name = o.nullableStr("name"),
                    portrait_url = o.nullableStr("portrait_url"),
                    depart_name = o.nullableStr("depart_name"),
                    duty_name = o.nullableStr("duty_name"),
                    company_id = o.nullableStr("company_id"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.nullableStr(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    /** 后台把落盘的资料读回内存（只做一次，不占主线程） */
    fun preload() {
        if (loadStarted) return
        loadStarted = true
        Thread {
            runCatching {
                val f = file()
                if (!f.exists()) return@runCatching
                val list = decode(f.readText())
                list.forEach { brief ->
                    val id = brief.id?.takeIf { it.isNotBlank() } ?: return@forEach
                    // 注意：不写进 `fetched`，这样连接成功后还会去批量接口拿最新的
                    if (cache[id] == null) merge(id, brief)
                }
                bump()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 合并 400ms 内的多次变化再写一次盘，避免批量解析时写几百次 */
    private fun saveSoon() {
        if (!saving.compareAndSet(false, true)) return
        Thread {
            runCatching { Thread.sleep(600) }
            saving.set(false)
            runCatching { file().writeText(encode(cache.values.toList())) }
        }.apply { isDaemon = true }.start()
    }

    fun get(id: String?): StaffBrief? {
        preload()
        return id?.let { cache[it] }
    }

    /** 已经从批量接口拿到过的人（避免同一批数据反复请求） */
    private val fetched = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 已知资料时直接落缓存（我的资料 / 他人主页头部取到的 StaffInfo 都会塞进来）。
     *
     * **只能补，不能盖空**：`/staffs/{id}` 常常没有 portrait_url、duty_name，
     * 如果整条覆盖，列表里刚拿到的证件照和「学生」就会被清掉，
     * 表现就是「个人主页头像变成姓氏首字」。
     */
    fun put(brief: StaffBrief) {
        val id = brief.id?.takeIf { it.isNotEmpty() } ?: return
        merge(id, brief)
    }

    private fun merge(id: String, incoming: StaffBrief) {
        val old = cache[id]
        cache[id] = if (old == null) {
            incoming
        } else {
            StaffBrief(
                id = id,
                name = incoming.name?.takeIf { it.isNotBlank() } ?: old.name,
                portrait_url = incoming.portrait_url?.takeIf { it.isNotBlank() } ?: old.portrait_url,
                depart_name = incoming.depart_name?.takeIf { it.isNotBlank() } ?: old.depart_name,
                duty_name = incoming.duty_name?.takeIf { it.isNotBlank() } ?: old.duty_name,
                company_id = incoming.company_id?.takeIf { it.isNotBlank() } ?: old.company_id,
            )
        }
        bump()
        saveSoon()
    }

    fun nameOf(id: String?): String = get(id)?.name?.takeIf { it.isNotBlank() }
        ?: AppContext.instance.getString(R.string.moments_anonymous)

    suspend fun resolve(ids: Collection<String>) {
        preload()
        // 只请求「还没从批量接口拿过」的人；之前从 /staffs/{id} 塞进来的半条资料照样要补全
        val missing = ids.filter { it.isNotEmpty() && !fetched.contains(it) }.distinct()
        if (missing.isEmpty()) return
        try {
            withContext(Dispatchers.IO) {
                val data = RceApi.staffsBatch(missing)?.data ?: return@withContext
                data.forEach { brief ->
                    val id = brief.id?.takeIf { it.isNotEmpty() } ?: return@forEach
                    merge(id, brief)
                    fetched.add(id)
                }
            }
        } catch (_: Exception) {
        }
    }
}
