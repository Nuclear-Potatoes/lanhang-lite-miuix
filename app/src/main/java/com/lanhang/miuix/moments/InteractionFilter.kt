package com.lanhang.miuix.moments

import com.lanhang.miuix.net.InteractionMessage
import com.lanhang.miuix.net.MomentsApi
import com.lanhang.miuix.session.Session

/**
 * 「消息」只提示与我自己的帖子有关的互动（第二十四轮第 4 条）。
 *
 * 用户原话：「消息栏中只提示与自己帖子有关的消息，如果是我点赞/评论了别人的动态，又有人评论了这条动态，
 * 则不应该提示我，只提示我 与我的帖子有关的动态。」
 *
 * 根因是融云圈 `/message/history` 的口径：它会把**你参与过**的帖子上的新互动也推给你
 * （我赞过 / 评论过的别人的动态，被别人再评论一次，我也会收到一条）。所以判断依据只有一个：
 * 这条互动所在帖子的作者是不是我（`feed.user_id == Session.userId`）。
 *
 * 取不到帖子信息时（接口失败、帖子已删）**原样返回**：宁可多显示一条，也不能把真消息吞掉。
 */
object InteractionFilter {

    /** 这批互动涉及的帖子（拉不到就返回空表，调用方据此判断「不知道就别过滤」） */
    fun feedsOf(raw: List<InteractionMessage>): List<MomentFeed> {
        val ids = raw.map { it.feedId }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return emptyList()
        return runCatching { MomentsApi.getFeeds(ids) }.getOrDefault(emptyList())
    }

    /** 只保留我自己的帖子；[feeds] 为空表示「不知道帖子是谁的」，此时不做任何过滤 */
    fun keepMine(raw: List<InteractionMessage>, feeds: List<MomentFeed>): List<InteractionMessage> {
        if (feeds.isEmpty()) return raw
        val me = Session.userId
        if (me.isEmpty()) return raw
        val mine = feeds.filter { it.user_id == me }.mapNotNull { it.feed_id }.toSet()
        return raw.filter { it.feedId in mine }
    }

    /** 未读数也按同一口径算，否则角标会比列表里能看到的条数多 */
    fun unreadCountMine(unread: List<InteractionMessage>): Int {
        if (unread.isEmpty()) return 0
        val feeds = feedsOf(unread)
        return keepMine(unread, feeds).size
    }
}
