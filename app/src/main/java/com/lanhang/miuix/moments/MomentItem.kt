package com.lanhang.miuix.moments

import com.google.gson.JsonParser
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.session.Session
import java.util.concurrent.CopyOnWriteArrayList

/** 一条动态的可变视图模型（点赞/评论本地翻转） */
class MomentItem(val feed: MomentFeed) {
    val content: FeedContent by lazy {
        val raw = feed.content
        if (raw.isNullOrBlank()) FeedContent() else try {
            JsonParser.parseString(raw).asJsonObject.let {
                FeedContent(
                    text = it.get("text")?.takeIf { e -> !e.isJsonNull }?.asString,
                    media = it.get("media")?.takeIf { e -> !e.isJsonNull }?.asJsonArray?.mapNotNull { e ->
                        try {
                            ApiClient.gson.fromJson(e, MediaItem::class.java)
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
            }
        } catch (_: Exception) {
            FeedContent()
        }
    }
    val likes = CopyOnWriteArrayList<LikeEntry>(feed.likes ?: emptyList())
    val comments = CopyOnWriteArrayList<CommentEntry>(feed.comments ?: emptyList())

    val likedByMe: Boolean get() = likes.any { it.user_id == Session.userId }
}
