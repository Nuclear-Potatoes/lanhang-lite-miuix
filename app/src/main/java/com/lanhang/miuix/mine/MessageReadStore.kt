package com.lanhang.miuix.mine

import android.content.Context
import com.lanhang.miuix.schedule.AppContext

/**
 * 「已经看过的工作圈互动消息」本机台账（第四十五轮，用户第 4 条）。
 *
 * 用户原话：「我的页收到新消息时会显示数字点，点入消息一栏后应当视为全部已读，
 * 此时回到我的页，提示点应该去除了」。
 *
 * 服务端的 `GET /message/unread` 本身没有「标记已读」的口子（官方 App 也是靠打开消息页
 * 之后不再提示），所以这里把「进过消息页时看到的那些 messageId」记在本机：
 * 角标 = 当前未读 id − 本机已看过的 id。于是
 *
 * - 进消息页 → 当次所有未读 id 记账 → 回到「我的」角标为 0；
 * - 之后**新**来的互动是新 id → 角标照样会亮（这才是我们要的语义）；
 * - 重启 App 也不复活旧角标（落 SharedPreferences），只保留最近 500 条防无限增长。
 */
object MessageReadStore {
    private const val PREF = "lanhang_message_read"
    private const val KEY = "seen_ids"
    private const val MAX_KEEP = 500

    private val seen = LinkedHashSet<String>()
    private var loaded = false

    private fun prefs() = AppContext.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            prefs().getStringSet(KEY, emptySet())?.let { seen.addAll(it) }
        }
    }

    /** 进了消息页：把这些 id 全部记为已看 */
    fun markSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ensureLoaded()
        var changed = false
        ids.forEach { id -> if (id.isNotBlank() && seen.add(id)) changed = true }
        if (!changed) return
        while (seen.size > MAX_KEEP) seen.remove(seen.first())
        runCatching { prefs().edit().putStringSet(KEY, HashSet(seen)).apply() }
    }

    /** 还没看过的条数（角标就显示它） */
    fun unseenCount(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        ensureLoaded()
        return ids.count { it.isNotBlank() && it !in seen }
    }
}
