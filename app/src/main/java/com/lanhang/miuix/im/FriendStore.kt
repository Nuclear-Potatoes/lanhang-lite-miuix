package com.lanhang.miuix.im

import android.content.Context
import com.lanhang.miuix.schedule.AppContext

/**
 * 「加好友 / 已添加」状态台账（第九十一轮）。
 *
 * 学校私有云只有 `POST /friends/invite` 等五个接口（见 `RceApi`），**没有好友列表查询接口**，
 * 所以「这个人是不是已经加过」没法每次现查。这里把「已经发过申请 / 已是好友」的 uid 落盘，
 * 进他人主页时读出来直接把按钮置灰；另外服务端回 `FRIEND_ALREADY_CREATED` 时也补记一笔。
 *
 * 退出登录后由 [clear] 清空，避免换账号后带着上一个人的状态。
 */
object FriendStore {
    private const val PREF = "lanhang_friend_state"
    private const val KEY_ADDED = "added_uids"

    private fun prefs() = AppContext.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isAdded(uid: String): Boolean =
        uid.isNotBlank() && prefs().getStringSet(KEY_ADDED, emptySet()).orEmpty().contains(uid)

    fun markAdded(uid: String) {
        if (uid.isBlank()) return
        val current = prefs().getStringSet(KEY_ADDED, emptySet()).orEmpty().toMutableSet()
        if (!current.add(uid)) return
        // putStringSet 必须给一个新集合：直接改原集合再 apply 在部分机型上不会落盘。
        prefs().edit().putStringSet(KEY_ADDED, current).apply()
    }

    fun clear() {
        prefs().edit().remove(KEY_ADDED).apply()
    }
}
