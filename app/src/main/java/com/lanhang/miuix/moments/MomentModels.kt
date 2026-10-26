package com.lanhang.miuix.moments

data class AuthResult(val authorization: String? = null)

// GET /timeline?from_feed_id=&size= → {feed_ids:[...]}
data class TimeLineResult(val feed_ids: List<String>? = null)

// POST /feed 成功 → result 里是新建动态的 feed_id
data class FeedIdRepo(
    val feed_id: String? = null,
    val create_dt: Long? = null,
    val update_dt: Long? = null
)

data class MomentFeed(
    val feed_id: String? = null,
    val user_id: String? = null,
    val type: Int? = null,
    val content: String? = null,
    val create_dt: Long? = null,
    val org_ids: List<String>? = null,
    val likes: List<LikeEntry>? = null,
    val comments: List<CommentEntry>? = null
)

// content 字段是 JSON 字符串，需二次解析
data class FeedContent(
    val text: String? = null,
    val media: List<MediaItem>? = null
)

data class MediaItem(
    val type: Int? = null,
    val url: String? = null,
    val thumbnail: String? = null,
    val digest: String? = null
) {
    val displayUrl: String? get() = thumbnail?.takeIf { it.isNotBlank() } ?: url
}

data class LikeEntry(
    val like_id: String? = null,
    val feed_id: String? = null,
    val user_id: String? = null,
    val create_dt: Long? = null
)

data class CommentEntry(
    val comment_id: String? = null,
    val feed_id: String? = null,
    val user_id: String? = null,
    val type: Int? = null,
    val content: String? = null,
    val reply_to: String? = null,
    val create_dt: Long? = null
)
