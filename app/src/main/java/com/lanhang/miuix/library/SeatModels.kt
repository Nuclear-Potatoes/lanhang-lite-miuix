package com.lanhang.miuix.library

/**
 * 图书馆座位预约（洄图系统 `lib-zw.lib.nchu.edu.cn`）的数据模型。
 *
 * 服务端是 SDUI（服务端驱动 UI）协议：接口返回的是「页面组件树」，字段值大量是字符串
 * （`"x": "45"`、`"state": "1"`）。所以只在 [LibrarySeatApi] 里做一次类型收敛，
 * 之后全用这里的强类型模型，界面层不再碰 JsonObject。
 *
 * 协议细节见 `docs/library-seat-reservation-research.md`。
 */

/** 一个可预约空间（`ht.space.SpaceReservationListItem`） */
data class SeatRoom(
    val name: String,
    /** `space_category[content_id]`，如一楼 = 23 */
    val contentId: String,
    val desc: String? = null,
)

/**
 * 选座规则（`data.range`），服务端下发，客户端只做展示与约束，不写死。
 *
 * 时段算法的权威来源是官方 H5 的 `app.es6.min.js`
 * （`/rwpublic/js/app.es6.min.js`，2022-03-31 构建版），关键函数是订单页组件的
 * `_setData` / `_onStartTimeChange`。两条规则（2026-10-26 实测核对）：
 *
 * 1. **开始时间包含当前小时**：官方 `_setData` 里
 *    `var c=a.getHours(); c<t&&(c=t);` → 下界 = `max(当前小时, default.beginTime)`，
 *    再 `_renderBeginTime(c, o-i)` 生成整点列表（`o`=maxEndTime，`i`=min_duration）。
 *    所以 09:58 也能选 09:00，只要结束时间还在将来。
 *    我们先前写成 `nowHour + 1`，把当前小时整段砍掉，才出现「官方能约 09:00–10:00、我们只能 10:00 起」。
 * 2. **服务端只校验结束时间**：`searchSeats` / `bookSeats` 同一套校验，
 *    「开始 + 时长 ≤ 现在」才回 `ParamError: 预约时段为过去时间`。
 *    实测 22:03 提交 `22:00 + 1h` 正常出座位图，`21:00 + 1h` 被拒、`21:00 + 2h` 通过、
 *    `18:00 + 5h`（开始时间早 4 小时、结束 23:00）也通过 —— 开始时间在多久以前都无所谓。
 */
data class SeatRules(
    /** 最早开始小时，如 7（07:00） */
    val minBeginHour: Int,
    /** 最晚结束小时，如 23（23:00） */
    val maxEndHour: Int,
    val minDurationHours: Int,
    val maxDurationHours: Int,
) {
    /**
     * 在 [startHour] 开始的最长可选时长（受闭馆时间与 max_duration 双重限制）。
     * 对应官方 `_onStartTimeChange` 的 `t-o.key<n&&(n=t-o.key)`。
     */
    fun maxDurationAt(startHour: Int): Int =
        (maxEndHour - startHour).coerceAtMost(maxDurationHours).coerceAtLeast(0)

    /**
     * 可选的开始整点（**含当前小时**），空表示今天已经排不下一次最短时长的预约了。
     *
     * @param nowHour 当前小时（0–23）
     * @param defaultBeginHour 服务端下发的 `default.beginTime`，官方取它与当前小时的较大者
     */
    fun startHours(nowHour: Int, defaultBeginHour: Int): IntRange? {
        val earliest = maxOf(minBeginHour, nowHour, defaultBeginHour)
        val latest = maxEndHour - minDurationHours
        return if (earliest > latest) null else earliest..latest
    }

    /**
     * 在 [startHour] 开始的可选时长，空表示这个整点已经排不下一次预约。
     * 对应官方 `_renderRange(min_duration, min(max_duration, maxEndTime - startHour))`。
     */
    fun durationHours(startHour: Int): IntRange? {
        val top = maxDurationAt(startHour)
        return if (top < minDurationHours) null else minDurationHours..top
    }
}

/** 选座表单页下发的默认值与规则 */
data class SeatForm(
    val rules: SeatRules,
    val defaultBeginHour: Int,
    val defaultDurationHours: Int,
)

/**
 * 座位图上的一个座位（POI）。
 *
 * 坐标是**网格制**：`x/y` 为格子坐标、`w/h` 为占几格，与 `SeatMapData.width/height`
 * （同样是格子数）构成整张平面图的坐标系（厂商原图固定 20px/格）。
 */
data class SeatPoi(
    val id: String,
    val title: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val state: Int,
    val hasSocket: Boolean,
) {
    /** 空闲可选（state=0）。1 保留/已占、2 关闭、3 使用中、4 本地已选、5 其它占用态 */
    val isFree: Boolean get() = state == 0
}

/**
 * 一张座位图。
 *
 * [planUrl] 是平面图背景（无扩展名的图片直链），加载失败时 [SeatMapView] 会退化成
 * 纯色底 + 色块，仍然可用。
 */
data class SeatMapData(
    val roomName: String,
    val planUrl: String?,
    /** 平面图宽度，单位=格 */
    val width: Int,
    /** 平面图高度，单位=格 */
    val height: Int,
    val seats: List<SeatPoi>,
    /**
     * 该时段**整层都没有可约座位**（服务端用 `data: null` 表达，不是解析失败）。
     *
     * 实测（2026-10-26）：二楼 201 阅览室 22:00 起闭馆，`searchSeats` 只回一个
     * `ht.EmptyView`「没有符合条件的座位预约」，`data` 直接是 null；
     * 同房间 21:00 正常返回 372 个座位。一楼 23:00、三楼 20:00（已过时段）同样为 null。
     * 归类成「没座位」让用户换个时间，比报「数据无法解析」有用得多。
     */
    val slotEmpty: Boolean = false,
) {
    val freeCount: Int get() = seats.count { it.isFree }
}

/**
 * 一次查座的结果：**一层楼可能有多个区域**（2026-10-27 第十八轮）。
 *
 * 服务端在 `allContent` 里按区域下发多张座位图（图书馆一楼 = 一楼自习区 + 自习室 102），
 * 以前的实现只读了「系统推荐」的那一张，于是另一个区域根本约不到。
 */
data class SeatSearchResult(
    val areas: List<SeatMapData>,
    /** 该时段整层都没有可约座位（服务端 `data: null`） */
    val slotEmpty: Boolean = false,
) {
    val totalFree: Int get() = areas.sumOf { it.freeCount }
}

/** 一条预约记录（`ht.Seat.OrderListItem`） */
data class SeatBooking(
    val id: String,
    val roomName: String,
    val seatNum: String,
    /** 开始时间，epoch 秒 */
    val beginAt: Long,
    /** 时长，秒 */
    val durationSec: Int,
    val status: Int,
    /** 开始前多少秒内可签到 */
    val limitSignAgo: Int,
    /** 开始后多少秒内未签到视为违约 */
    val limitSignBack: Int,
    /** 暂离后多少秒内需返回 */
    val limitLeftBack: Int,
    /**
     * 服务端下发的时间戳（列表项里的 `nowTime`）。
     *
     * 签到窗口、暂离的「返回截止时间」一律按它算：手机时钟偏几分钟就会让按钮该出不出、
     * 或者把 `comeBackTime` 送成一个错的时间。
     */
    val serverNow: Long,
) {
    val endAt: Long get() = beginAt + durationSec

    /** 订单状态机，见调研报告 §9（0 待签到 / 1 使用中 / 2 暂离 / ≥3 已结束，不再显示按钮） */
    val isPendingSign: Boolean get() = status == 0
    val isInUse: Boolean get() = status == 1
    val isAway: Boolean get() = status == 2

    /** 现在能否签到：开始前 [limitSignAgo] 秒 ~ 开始后 [limitSignBack] 秒 */
    fun canCheckIn(): Boolean =
        isPendingSign && serverNow >= beginAt - limitSignAgo && serverNow <= beginAt + limitSignBack
}

/** 可对订单执行的操作，驱动「我的预约」列表里的按钮 */
enum class SeatAction { CHECK_IN, STEP_OUT, COME_BACK, CHECK_OUT, CANCEL }

/** 预约成功返回 */
data class SeatBookResult(val bookingId: String, val message: String?)
