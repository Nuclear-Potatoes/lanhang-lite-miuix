package com.lanhang.miuix.moments

import com.lanhang.miuix.net.MomentsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拉某一串动态（公共时间线 / 某个人的时间线）。
 *
 * 协议依据官方 App：
 *  · 公共流  GET /timeline?from_feed_id=&size=
 *  · 个人流  GET /timeline/{user_id}?from_feed_id=&size=
 * 拿到 id 列表后统一走 POST /feed/batch 取内容（带评论与点赞）。
 *
 * 我的「相册」、我的「个人主页」、他人的「个人主页」全都走这里；
 * 「工作圈」Tab 自己的翻页逻辑仍留在 MomentsFragment（它还要管发表）。
 */
object MomentFeedSource {

    /** [userId] 为空 = 公共时间线 */
    suspend fun load(
        userId: String?,
        fromFeedId: String?,
        size: Int = MomentsApi.PAGE_SIZE,
    ): List<MomentItem> = withContext(Dispatchers.IO) {
        val ids = if (userId.isNullOrEmpty()) {
            MomentsApi.getTimeLine(fromFeedId, size)
        } else {
            MomentsApi.getUserTimeLine(userId, fromFeedId, size)
        }
        if (ids.isEmpty()) {
            emptyList()
        } else {
            val feeds = MomentsApi.getFeeds(ids)
            // 姓名/头像走 POST /staffs/batch。除了作者，点赞和评论的人也要解析，
            // 否则个人主页、相册里全是「用户 赞了」「用户 回复 用户」。
            UserInfoCache.resolve(
                feeds.flatMap { feed ->
                    val list = ArrayList<String>()
                    feed.user_id?.takeIf { it.isNotEmpty() }?.let(list::add)
                    feed.likes.orEmpty().forEach { like ->
                        like.user_id?.takeIf { it.isNotEmpty() }?.let(list::add)
                    }
                    feed.comments.orEmpty().forEach { comment ->
                        comment.user_id?.takeIf { it.isNotEmpty() }?.let(list::add)
                        // reply_to 有时是用户 id，有时是昵称；只把纯数字的当 id 去查
                        comment.reply_to?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                            ?.let(list::add)
                    }
                    list
                }
            )
            feeds.map { MomentItem(it) }
        }
    }
}

/** 点赞 / 评论 / 删除：三个宿主（工作圈、相册、个人主页）共用，避免三份实现漂移 */
object MomentActions {

    /** 返回操作后的点赞状态 */
    suspend fun toggleLike(item: MomentItem): Boolean = withContext(Dispatchers.IO) {
        val feedId = item.feed.feed_id
        if (feedId.isNullOrEmpty()) {
            item.likedByMe
        } else if (item.likedByMe) {
            MomentsApi.cancelLike(feedId)
            false
        } else {
            MomentsApi.like(feedId)
            true
        }
    }

    suspend fun comment(item: MomentItem, replyTo: String?, text: String) {
        val feedId = item.feed.feed_id
        if (!feedId.isNullOrEmpty()) MomentsApi.postComment(feedId, text, replyTo)
    }

    suspend fun deleteComment(item: MomentItem, comment: CommentEntry) {
        val feedId = item.feed.feed_id
        val commentId = comment.comment_id
        if (!feedId.isNullOrEmpty() && !commentId.isNullOrEmpty()) {
            MomentsApi.deleteComment(feedId, commentId)
        }
    }

    suspend fun deleteFeed(item: MomentItem) {
        val feedId = item.feed.feed_id
        if (!feedId.isNullOrEmpty()) MomentsApi.deleteFeed(feedId)
    }
}
