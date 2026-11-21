package com.lanhang.miuix.im

import android.content.Context
import com.lanhang.miuix.schedule.AppContext

/**
 * 「加好友 / 申请中 / 已添加」状态台账（第九十一轮建立，第九十二轮拆成三态）。
 *
 * 学校私有云只有 `POST /friends/invite` 等五个接口（见 `RceApi`），**没有好友列表查询接口**，
 * 所以「这个人是不是已经加过」没法每次现查。这里把两种状态分开落盘：
 * - [State.PENDING]：我发过申请、对方还没同意 → 按钮灰显「申请中」；
 * - [State.ADDED]：对方已同意 / 服务端回 `FRIEND_ALREADY_CREATED` → 按钮灰显「已添加」。
 *
 * 进他人主页时再拿 `/friends/request_list` 校准一次（state 2/3 升级成 ADDED，
 * state 0/1 记为 PENDING），覆盖以前用官方客户端加过的人。网络不通时以本地台账为准。
 *
 * 退出登录后由 [clear] 清空，避免换账号后带着上一个人的状态。
 */
object FriendStore {
    private const val PREF = "lanhang_friend_state"
    private const val KEY_ADDED = "added_uids"
    private const val KEY_PENDING = "pending_uids"

    enum class State { NONE, PENDING, ADDED }

    private fun prefs() = AppContext.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isAdded(uid: String): Boolean =
        uid.isNotBlank() && prefs().getStringSet(KEY_ADDED, emptySet()).orEmpty().contains(uid)

    fun state(uid: String): State = when {
        uid.isBlank() -> State.NONE
        isAdded(uid) -> State.ADDED
        prefs().getStringSet(KEY_PENDING, emptySet()).orEmpty().contains(uid) -> State.PENDING
        else -> State.NONE
    }

    /** 申请发出去了、还没被同意。已是好友时不能被降级成「申请中」 */
    fun markPending(uid: String) {
        if (uid.isBlank() || isAdded(uid)) return
        val current = prefs().getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        if (!current.add(uid)) return
        // putStringSet 必须给一个新集合：直接改原集合再 apply 在部分机型上不会落盘。
        prefs().edit().putStringSet(KEY_PENDING, current).apply()
    }

    /** 互为好友（或服务端说「已是好友」）：升级到 ADDED，同时从 PENDING 里摘掉 */
    fun markAdded(uid: String) {
        if (uid.isBlank()) return
        val p = prefs()
        val added = p.getStringSet(KEY_ADDED, emptySet()).orEmpty().toMutableSet()
        val pending = p.getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        added.add(uid)
        pending.remove(uid)
        p.edit().putStringSet(KEY_ADDED, added).putStringSet(KEY_PENDING, pending).apply()
    }

    fun clear() {
        prefs().edit().remove(KEY_ADDED).remove(KEY_PENDING).apply()
    }
}
