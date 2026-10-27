package com.lanhang.miuix.library

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanhang.miuix.R
import com.lanhang.miuix.core.ApiException
import com.lanhang.miuix.core.TextRef
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.session.Session
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody

/**
 * 图书馆座位预约（厂商「洄图」`lib-zw.lib.nchu.edu.cn`）。
 *
 * 三条硬约定（都是调研阶段真机踩出来的，改之前先看 `docs/library-seat-reservation-research.md`）：
 *
 * 1. **登录复用蓝航统一身份**，不另建账号：`getLdapToken` → 带 `Token/Version/DeviceID` 头
 *    访问网关 `go.nchu.edu.cn/?id=76` → 302 落地后服务端种下 `AppClient/uid/auth` 等 Cookie，
 *    之后所有请求靠 CookieJar 自动带上（[ApiClient] 已持久化）。
 * 2. **每个请求都要带 `LAB_JSON=1`**，否则服务端返回 HTML 页面而不是 JSON；
 * 3. **POST 体必须是扁平表单参数**（`application/x-www-form-urlencoded`）。
 *    写成 `data[beginTime]=…` 这种嵌套形式会被服务端当成空值，
 *    报「参数错误: 预约时段为过去时间」——这条坑很隐蔽，别改回 JSON。
 *
 * 本类全是阻塞 IO，调用方必须放在 `Dispatchers.IO`。
 */
object LibrarySeatApi {
    private const val TAG = "LibrarySeatApi"

    /** 座位系统的独立站点（与蓝航 OA 不同域，Cookie 也是独立一套） */
    private const val HOST = "http://lib-zw.lib.nchu.edu.cn"

    /** 统一身份网关里「座位预约」的应用 id */
    private const val GATEWAY = "http://go.nchu.edu.cn/?id=76"

    /** `space_category[category_id]`，本校所有空间共用 591 */
    private const val CATEGORY_ID = "591"

    private val SEAT_LIST_TYPE = "ht.space.SpaceReservationListItem"
    private val BOOKING_TYPE = "ht.Seat.OrderListItem"

    /**
     * 空间列表接口失败时的兜底（2026-10-25 实测顺序与 content_id）。
     * 只在 `/Space/Category/list` 不可用时使用，避免整页不可用。
     */
    private val FALLBACK_ROOMS = listOf(
        SeatRoom("图书馆一楼", "23"),
        SeatRoom("图书馆二楼", "87"),
        SeatRoom("图书馆三楼", "88"),
        SeatRoom("图书馆四楼", "89"),
        SeatRoom("图书馆五楼", "90"),
        SeatRoom("「一站式」学生社区", "113"),
        SeatRoom("上海路校区图书馆", "112"),
    )

    /** 当前登录用户在座位系统里的 uid，`bookSeats` 的 `seatBookers` 需要它 */
    @Volatile
    private var selfUid: String? = null

    fun hasSession(): Boolean = ApiClient.allCookies().keys.any { it.contains("lib-zw") }

    /**
     * 用蓝航统一身份登录座位系统。已有会话时直接返回 true。
     * 失败返回 false（不抛），调用方决定文案。
     */
    fun ensureLogin(deviceId: String, force: Boolean = false): Boolean {
        if (!force && hasSession()) return true
        if (force) ApiClient.clearCookiesMatching("lib-zw")
        val token = runCatching { RceApi.getLdapToken() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        Session.ldapToken = token
        val request = Request.Builder()
            .url(GATEWAY)
            .header("User-Agent", MiniappApi.UA)
            .header("Token", token)
            .header("Version", Session.appVersion)
            .header("DeviceID", deviceId)
            .get()
            .build()
        return try {
            ApiClient.http.newCall(request).execute().use { resp ->
                // 落地页是 SPA 的 index.html，内容没有意义，只关心 Cookie 有没有种上
                resp.body?.string().orEmpty().length
                Log.d(TAG, "gateway code=${resp.code} final=${resp.request.url} session=${hasSession()}")
                hasSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "gateway login failed", e)
            false
        }
    }

    // ==================== 空间 / 规则 ====================

    /** 空间列表；接口异常时退回 [FALLBACK_ROOMS] */
    fun rooms(): List<SeatRoom> {
        val root = runCatching { call("/Space/Category/list") }.getOrNull() ?: return FALLBACK_ROOMS
        val items = mutableListOf<JsonObject>()
        collect(root, SEAT_LIST_TYPE, items)
        val rooms = items.mapNotNull { item ->
            val name = item.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val contentId = contentIdOf(item.pathLinkUrl()) ?: return@mapNotNull null
            SeatRoom(name, contentId, item.str("desc"))
        }
        return rooms.ifEmpty { FALLBACK_ROOMS }
    }

    /** 选座表单页：默认时间 + 规则（`range`） */
    fun form(room: SeatRoom): SeatForm {
        val root = call(
            "/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=$CATEGORY_ID" +
                "&space_category%5Bcontent_id%5D=${room.contentId}"
        )
        val data = root.obj("data") ?: throw ApiException(-1, TextRef.Res(R.string.error_library_payload))
        selfUid = data.str("uid")?.takeIf { it.isNotBlank() }
        val range = data.obj("range") ?: throw ApiException(-1, TextRef.Res(R.string.error_library_rules))
        val def = data.obj("default")
        return SeatForm(
            rules = SeatRules(
                minBeginHour = range.int("minBeginTime", 7),
                maxEndHour = range.int("maxEndTime", 23),
                minDurationHours = range.int("min_duration", 1),
                maxDurationHours = range.int("max_duration", 6),
            ),
            defaultBeginHour = def?.int("beginTime", 0) ?: 0,
            defaultDurationHours = def?.int("duration", 1) ?: 1,
        )
    }

    // ==================== 座位图 ====================

    /**
     * 查座，返回整张座位图。**只能查当天**（`advance_date` = 当天 0 点）。
     *
     * 时间校验只有一条：**`beginEpoch + durationSec > 现在`**，否则报
     * `ParamError: 预约时段为过去时间`。开始时间本身落在过去没关系——09:58 查
     * `09:00 + 1h` 照常返回座位图，这正是官方 H5 能在整点后继续约「当前这一格」的原因。
     * 时段列表怎么算见 [SeatRules.startHours]。
     */
    fun search(room: SeatRoom, beginEpoch: Long, durationSec: Int): SeatSearchResult {
        val body = FormBody.Builder()
            .add("beginTime", beginEpoch.toString())
            .add("duration", durationSec.toString())
            .add("num", "1")
            .add("space_category[category_id]", CATEGORY_ID)
            .add("space_category[content_id]", room.contentId)
            .build()
        val root = call("/Seat/Index/searchSeats", body)

        // 2026-10-27 第十八轮（用户：「图书馆预约中缺少一块区域，没办法预约该区域的座位」）：
        // 真凶在这里 —— 返回值里的 `data` 只是「系统推荐的那一间」，整层所有区域在
        // `allContent.children[]` 的 `com.CatCon` 下面：
        //    allContent.children[2].children.children[] = 每个区域一个 ht.Seat.RecommendSeatItem
        //    （roomName + seatMap.info{id,title,plan,width,height} + seatMap.POIs[]）
        // 实测「图书馆一楼」有两个区域：一楼自习区（位置上有插座）104 座、自习室（102）292 座。
        // 以前只读 `data`，于是永远只显示服务端推荐的那一个，另一个区域根本约不到。
        val areas = parseAreas(root)
        if (areas.isNotEmpty()) return SeatSearchResult(areas)

        // 兜底：老结构（只有 data 一张图）
        val data = root.obj("data")
            ?: return run {
                Log.d(TAG, "searchSeats 无数据，按时段无可约座位处理 room=${room.name} begin=$beginEpoch")
                SeatSearchResult(emptyList(), slotEmpty = true)
            }
        val single = parseSeatMap(data, room.name)
            ?: return SeatSearchResult(emptyList(), slotEmpty = true)
        return SeatSearchResult(listOf(single))
    }

    /** 从 `allContent` 里把所有区域拆出来（顺序与服务端一致） */
    private fun parseAreas(root: JsonObject): List<SeatMapData> {
        val all = root.obj("allContent") ?: return emptyList()
        val out = ArrayList<SeatMapData>()
        all.array("children").forEach { element ->
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (block.str("ui_type") != "com.CatCon") return@forEach
            // CatCon 的 children 是一个 com.BlockList 对象（不是数组），区域列表在它自己的 children 里
            val list = block.obj("children") ?: return@forEach
            list.array("children").forEach { item ->
                val node = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val seatMap = node.obj("seatMap") ?: return@forEach
                parseSeatMap(seatMap, node.str("roomName").orEmpty())?.let { out += it }
            }
        }
        return out
    }

    /** 解析一张座位图（新结构的 `seatMap` 或老结构的 `data`） */
    private fun parseSeatMap(node: JsonObject, fallbackName: String): SeatMapData? {
        val info = node.obj("info")
        val seats = node.array("POIs").mapNotNull { element ->
            val poi = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = poi.str("id") ?: return@mapNotNull null
            SeatPoi(
                id = id,
                title = poi.str("title").orEmpty(),
                x = poi.int("x", 0),
                y = poi.int("y", 0),
                w = poi.int("w", 2).coerceAtLeast(1),
                h = poi.int("h", 2).coerceAtLeast(1),
                state = poi.int("state", 2),
                hasSocket = poi.str("have_socket") == "1",
            )
        }
        if (seats.isEmpty()) return null
        return SeatMapData(
            roomName = info?.str("title")?.takeIf { it.isNotBlank() }
                ?: fallbackName.takeIf { it.isNotBlank() }
                ?: "座位区",
            planUrl = info?.str("plan")?.takeIf { it.isNotBlank() },
            width = info?.int("width", 0)?.takeIf { it > 0 } ?: seats.maxOfOrNull { it.x + it.w } ?: 0,
            height = info?.int("height", 0)?.takeIf { it > 0 } ?: seats.maxOfOrNull { it.y + it.h } ?: 0,
            seats = seats,
        )
    }

    // ==================== 预约 ====================

    /**
     * 直接下单。本馆 `max_num=1`（单人座），前端逻辑是「选中即下单」，
     * 不需要走 `lockSeats` 的 15 分钟锁座那套多人流程。
     */
    fun book(seatId: String, beginEpoch: Long, durationSec: Int): SeatBookResult {
        val uid = selfUid ?: throw ApiException(-1, TextRef.Res(R.string.error_library_uid))
        val body = FormBody.Builder()
            .add("beginTime", beginEpoch.toString())
            .add("duration", durationSec.toString())
            .add("seats[0]", seatId)
            .add("seatBookers[0]", uid)
            .build()
        val data = call("/Seat/Index/bookSeats", body).obj("data")
        if (data?.str("result") != "success") {
            throw ApiException(-1, TextRef.Raw(data?.str("msg").orEmpty().ifBlank {
                // 服务端没给原因时给个可读兜底
                "预约未成功"
            }))
        }
        return SeatBookResult(data.str("bookingId").orEmpty(), data.str("msg"))
    }

    fun myBookings(): List<SeatBooking> {
        val root = call("/Seat/Index/myBookingList")
        val items = mutableListOf<JsonObject>()
        collect(root, BOOKING_TYPE, items)
        return items.mapNotNull { item ->
            SeatBooking(
                id = item.str("id") ?: return@mapNotNull null,
                roomName = item.str("roomName").orEmpty(),
                seatNum = item.str("seatNum").orEmpty(),
                beginAt = item.str("time")?.toLongOrNull() ?: return@mapNotNull null,
                durationSec = item.str("duration")?.toIntOrNull() ?: 0,
                status = item.str("status")?.toIntOrNull() ?: -1,
                limitSignAgo = item.int("limitSignAgo", 1800),
                limitSignBack = item.int("limitSignBack", 1800),
                limitLeftBack = item.int("limitLeftBack", 3600),
                serverNow = item.str("nowTime")?.toLongOrNull()
                    ?: System.currentTimeMillis() / 1000,
            )
        }.sortedByDescending { it.beginAt }
    }

    /**
     * 我的签到码：官方 `ht.Seat.CodeSignPage`。
     *
     * 返回的是一页 SDUI 壳 + **顶层** `code`（不是 `data.code`），32 位 token，
     * **每次请求都会换一个新的**——所以界面每次显示/刷新都要重新取，缓存旧码没用。
     */
    fun signCode(): String {
        val root = call("/Seat/Index/mySignQRCode")
        return root.str("code")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(-1, TextRef.Res(R.string.error_library_payload))
    }

    /**
     * 把签到码渲染成二维码图片（服务端出 PNG）。
     *
     * **必须走 [ApiClient] 这套 OkHttp**：这个地址要会话 Cookie，不带就 302 到登录页；
     * Coil 默认的 ImageLoader 没有 CookieJar，直接拿 URL 去加载会失败。
     */
    fun signCodeImage(code: String): ByteArray {
        val deviceId = Session.deviceId(com.lanhang.miuix.schedule.AppContext.instance)
        if (!ensureLogin(deviceId)) {
            throw ApiException(-1, TextRef.Res(R.string.error_library_login))
        }
        val request = Request.Builder()
            .url("$HOST/Content/Index/createQRCodeToShow?str=$code")
            .header("User-Agent", MiniappApi.UA)
            .header("Referer", "$HOST/")
            .get()
            .build()
        return ApiClient.http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, TextRef.Res(R.string.error_library_request, resp.code))
            }
            resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: throw ApiException(-1, TextRef.Res(R.string.error_library_empty))
        }
    }

    /** 执行签到/暂离/续座/签退/取消。成功返回服务端提示（可能为空） */
    fun perform(action: SeatAction, booking: SeatBooking): String? {
        val body = when (action) {
            // 暂离的返回截止时间按服务端时间算，手机时钟偏了会把座位让出去
            SeatAction.STEP_OUT -> FormBody.Builder()
                .add("comeBackTime", (booking.serverNow + booking.limitLeftBack).toString())
                .build()

            else -> null
        }
        val path = when (action) {
            SeatAction.CHECK_IN -> "/Seat/Index/checkIn?bookingId=${booking.id}"
            SeatAction.CHECK_OUT -> "/Seat/Index/checkOut?bookingId=${booking.id}"
            SeatAction.STEP_OUT -> "/Seat/Index/stepOut?bookingId=${booking.id}"
            SeatAction.COME_BACK -> "/Seat/Index/comeBack?bookingId=${booking.id}"
            SeatAction.CANCEL -> "/Seat/Index/cancelBooking?bookingId=${booking.id}"
        }
        val data = call(path, body).obj("data")
        if (data != null && data.has("result") && data.str("result") != "success") {
            throw ApiException(-1, TextRef.Raw(data.str("msg").orEmpty()))
        }
        return data?.str("msg")
    }

    // ==================== 请求与解析 ====================

    /**
     * 发一次请求并校验信封。
     *
     * 两套返回形态：普通接口是 `{CODE, MESSAGE, DATA}`；SDUI 页面是直接把
     * `ui_type/header/content/data` 摊在顶层（没有 CODE）。后者不校验 CODE。
     */
    private fun call(path: String, body: RequestBody? = null): JsonObject {
        val deviceId = Session.deviceId(com.lanhang.miuix.schedule.AppContext.instance)
        if (!ensureLogin(deviceId)) {
            throw ApiException(-1, TextRef.Res(R.string.error_library_login))
        }
        var root = exec(path, body)
        if (isLoginLost(root)) {
            Log.w(TAG, "session lost, re-login and retry $path")
            if (!ensureLogin(deviceId, force = true)) {
                throw ApiException(-1, TextRef.Res(R.string.error_library_login))
            }
            root = exec(path, body)
        }
        val code = root.str("CODE")
        if (code != null && code != "ok") {
            throw ApiException(-1, TextRef.Raw(root.str("MESSAGE").orEmpty().ifBlank { code }))
        }
        if (root.str("ui_type") == "com.Message") {
            throw ApiException(
                -1,
                TextRef.Raw(root.str("MESSAGE").orEmpty().ifBlank { "请求失败" }),
            )
        }
        return root
    }

    private fun exec(path: String, body: RequestBody?): JsonObject {
        val separator = if (path.contains('?')) '&' else '?'
        val url = "$HOST$path${separator}LAB_JSON=1"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", MiniappApi.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$HOST/")
        if (body != null) builder.post(body) else builder.get()
        return ApiClient.http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, TextRef.Res(R.string.error_library_request, resp.code))
            }
            if (text.isBlank()) throw ApiException(-1, TextRef.Res(R.string.error_library_empty))
            try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                throw ApiException(-1, TextRef.Res(R.string.error_library_payload))
            }
        }
    }


    private fun isLoginLost(root: JsonObject): Boolean {
        if (root.str("CODE") == "NotLogin") return true
        val data = root.obj("data") ?: root.obj("DATA") ?: return false
        val login = data.get("is_login")
        return login != null && login.isJsonPrimitive && !login.asBoolean
    }

    /** 在整棵 SDUI 组件树里挑出指定 `ui_type` 的节点 */
    private fun collect(node: JsonElement, type: String, out: MutableList<JsonObject>) {
        when {
            node.isJsonObject -> {
                val obj = node.asJsonObject
                if (obj.str("ui_type") == type) {
                    out += obj
                } else {
                    obj.entrySet().forEach { collect(it.value, type, out) }
                }
            }

            node.isJsonArray -> node.asJsonArray.forEach { collect(it, type, out) }
        }
    }

    private fun contentIdOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return Regex("content_id(?:%5D|])=(\\d+)").find(url)?.groupValues?.get(1)
    }

    private fun JsonObject.pathLinkUrl(): String? =
        obj("link")?.str("url")

    // ---- Gson 取值小工具：字段可能是字符串也可能是数字，统一收敛 ----

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonObject.str(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString
    }

    private fun JsonObject.int(name: String, def: Int): Int =
        str(name)?.trim()?.toIntOrNull() ?: def
}
