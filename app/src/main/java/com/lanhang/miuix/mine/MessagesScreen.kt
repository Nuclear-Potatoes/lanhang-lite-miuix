package com.lanhang.miuix.mine

import com.lanhang.miuix.ui.miuix.pressableRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.lanhang.miuix.R
import com.lanhang.miuix.moments.FeedContent
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.net.InteractionMessage
import com.lanhang.miuix.net.MomentsApi
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.LanhangIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息（互动消息）。
 *
 * 官方客户端的「消息」Tab 是融云 IMKit 的会话列表（私信/群聊/系统），
 * 那套要走 `AuthTask.refreshToken` 拿 IM token + `io.rong.imkit`，本项目不做 IM；
 * 但**工作圈里的互动（谁评论了你、谁赞了你）是同一套 moments 服务给出的**，
 * 官方 `MomentHistoryMsgActivity` 就是用它做的，所以这里按那套协议原样移植：
 *
 * - `GET /message/history?from_message_id=&size=20`  → 倒序的互动记录（type 1=评论 / 2=赞）
 * - `GET /message/unread`                            → 未读记录（用来点小红点）
 * - `GET /message/unread_count`                      → 未读数（「我的」列表上的角标）
 *
 * 读和展示是纯本地行为：**不写任何数据回学校服务器**（官方的「已读」是清本地库，
 * 对应的删除接口同时会把记录从历史里抹掉，误判就会丢消息，所以不做）。
 */
@Composable
fun MessagesScreen(
    modifier: Modifier = Modifier,
    /** 滚动位置由外面的「我的」页面栈持有（用户第九轮第 3 条） */
    listState: LazyListState = rememberLazyListState(),
    /** 点某条互动的正文/摘要 → 切到工作圈那条动态 */
    onOpenMoments: (String) -> Unit = {},
    /** 点互动人的头像或名字 → 进他的个人主页 */
    onOpenProfile: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<InteractionMessage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(FILTER_ALL) }
    var unread by remember { mutableStateOf<Set<String>>(emptySet()) }
    var excerpt by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(revision) {
        messages = null
        error = null
        more = true
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val list = MomentsApi.getHistoryMessages(null, PAGE_SIZE)
                val feedIds = list.map { it.feedId }.filter { it.isNotEmpty() }.distinct()
                val texts = if (feedIds.isEmpty()) {
                    emptyMap()
                } else {
                    runCatching { MomentsApi.getFeeds(feedIds) }.getOrDefault(emptyList())
                        .associate { feed -> feed.feed_id.orEmpty() to feedText(feed.content) }
                }
                UserInfoCache.resolve(list.map { it.userId } + list.mapNotNull { it.replyTo })
                val unreadIds = runCatching {
                    MomentsApi.getUnreadMessages().map { it.messageId }.toSet()
                }.getOrDefault(emptySet())
                Triple(list, texts, unreadIds)
            }
        }
        result.onSuccess { (list, texts, unreadIds) ->
            messages = list
            excerpt = texts
            unread = unreadIds
            more = list.size >= PAGE_SIZE
        }
        result.onFailure { error = it.message ?: context.getString(R.string.messages_load_failed) }
    }

    /** 触底加载下一页（from_message_id 游标） */
    suspend fun loadMore() {
        if (loadingMore || !more) return
        val last = messages?.lastOrNull()?.messageId ?: return
        loadingMore = true
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val list = MomentsApi.getHistoryMessages(last, PAGE_SIZE)
                val feedIds = list.map { it.feedId }.filter { it.isNotEmpty() }.distinct()
                val texts = if (feedIds.isEmpty()) {
                    emptyMap()
                } else {
                    runCatching { MomentsApi.getFeeds(feedIds) }.getOrDefault(emptyList())
                        .associate { feed -> feed.feed_id.orEmpty() to feedText(feed.content) }
                }
                UserInfoCache.resolve(list.map { it.userId })
                list to texts
            }
        }
        result.onSuccess { (list, texts) ->
            messages = (messages ?: emptyList()) + list
            excerpt = excerpt + texts
            more = list.size >= PAGE_SIZE
        }
        loadingMore = false
    }

    val shown = remember(messages, filter) {
        messages.orEmpty().filter {
            filter == FILTER_ALL || (filter == FILTER_COMMENT && it.isComment) ||
                (filter == FILTER_LIKE && !it.isComment)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill(stringResourceSafe(R.string.messages_filter_all), filter == FILTER_ALL) {
                filter = FILTER_ALL
            }
            FilterPill(stringResourceSafe(R.string.messages_filter_comment), filter == FILTER_COMMENT) {
                filter = FILTER_COMMENT
            }
            FilterPill(stringResourceSafe(R.string.messages_filter_like), filter == FILTER_LIKE) {
                filter = FILTER_LIKE
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResourceSafe(R.string.messages_refresh),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable { revision++ }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            when {
                error != null -> item("error") {
                    MessageStateBox(
                        message = error!!,
                        action = stringResourceSafe(R.string.contacts_retry),
                    ) { revision++ }
                }

                messages == null -> item("loading") {
                    MessageStateBox(
                        message = stringResourceSafe(R.string.messages_loading),
                        action = null,
                    ) {}
                }

                shown.isEmpty() -> item("empty") {
                    MessageStateBox(
                        message = stringResourceSafe(R.string.messages_empty),
                        action = null,
                    ) {}
                }

                else -> {
                    items(shown, key = { it.messageId }) { message ->
                        MessageRow(
                            message = message,
                            unread = unread.contains(message.messageId),
                            excerpt = excerpt[message.feedId],
                            onOpen = { onOpenMoments(message.feedId) },
                            onOpenProfile = { onOpenProfile(message.userId) },
                        )
                    }
                    if (more && filter == FILTER_ALL) {
                        item("more") {
                            LaunchedEffect(messages?.size) { loadMore() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = stringResourceSafe(R.string.contacts_loading_more),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    item("tail") { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }

}

/* ------------------------------------------------------------------ 单行 */

@Composable
private fun MessageRow(
    message: InteractionMessage,
    unread: Boolean,
    excerpt: String?,
    onOpen: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val author = UserInfoCache.get(message.userId)
    val name = author?.name?.takeIf { it.isNotBlank() }
        ?: stringResourceSafe(R.string.moments_anonymous)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressableRow(onClick = { onOpen() }, corner = 16.dp)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            LanhangAvatar(
                url = author?.portrait_url?.takeIf { it.isNotBlank() },
                name = name,
                size = 40.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenProfile),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenProfile),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (message.isComment) {
                            stringResourceSafe(R.string.messages_action_comment)
                        } else {
                            stringResourceSafe(R.string.messages_action_like)
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (unread) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = relativeTime(message.createDt),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                val comment = message.commentContent?.takeIf { it.isNotBlank() }
                if (comment != null) {
                    Text(
                        text = comment,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }

                if (!excerpt.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = excerpt,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 小件 */

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (selected) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceContainerHighest
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun MessageStateBox(message: String, action: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = LanhangIcons.Message,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
            if (action != null) {
                Text(
                    text = action,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .clickable { onClick() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 动态正文（`content` 是 JSON 字符串，只取 text） */
private fun feedText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching {
        val obj = JsonParser.parseString(raw).asJsonObject
        obj.get("text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    }.getOrDefault("")
}

@Composable
private fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> stringResourceSafe(R.string.messages_just_now)
        diff < 3_600_000L -> stringResourceSafe(R.string.messages_minutes_ago, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResourceSafe(R.string.messages_hours_ago, (diff / 3_600_000L).toInt())
        diff < 172_800_000L -> stringResourceSafe(R.string.messages_yesterday)
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ms))
    }
}

@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String =
    if (args.isEmpty()) androidx.compose.ui.res.stringResource(id)
    else androidx.compose.ui.res.stringResource(id, *args)

private const val PAGE_SIZE = 20
private const val FILTER_ALL = 0
private const val FILTER_COMMENT = 1
private const val FILTER_LIKE = 2
