package com.lanhang.miuix.im

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.net.FriendRequest
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.ui.LoadingBlock
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.pressableRow
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.miuix.showMiuixInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「新的朋友」—— 好友申请列表（第六十三轮第 3 条）。
 *
 * 用户原话：「原版蓝航有好友申请功能，加入这个功能并做到私信页面中」。
 * 官方对应页面是 `cn.edu.nchu.nahang.ui.contact.NewFriendsListActivity`：
 * 进去先 `clearFriendRequestUnreadCount`，然后展示申请列表，每条可以接受或删除。
 *
 * 接口全在 [RceApi] 里（`/friends/request_list`、`/friends/accept/{requestId}`、
 * `/friends/request_list/{requestId}`、`/friends/clear_unread`），这里只做界面。
 *
 * 几个容易踩的点：
 * 1. 接受 / 删除传的是 **requestId**，不是 uid；
 * 2. 清未读是 **PUT**，不是 POST；
 * 3. 接受成功后会话不会自己冒出来，要顺手让 [ImRepository] 刷一次好友与会话列表。
 */
@Composable
fun FriendRequestsScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    /** 正在处理的 requestId（处理中把「接受」按钮禁掉，避免连点重复提交） */
    var acting by remember { mutableStateOf<String?>(null) }

    /** 拉一次列表；进页面与下拉失败后手动重试都走这里 */
    suspend fun reload() {
        loading = true
        val result = withContext(Dispatchers.IO) { RceApi.friendRequests(0L) }
        requests = result.data
        errorText = RceApi.lastFriendRequestError
        loading = false
        // 官方页面是「进来就清未读」，这里照做：拉到了申请就把未读清零，
        // 否则私信页那个红点会一直挂着（清未读失败不影响列表展示，故忽略返回值）。
        if (result.data.isNotEmpty()) {
            withContext(Dispatchers.IO) { RceApi.clearFriendUnread() }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun accept(request: FriendRequest) {
        val id = request.requestId ?: return
        if (acting != null) return
        acting = id
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RceApi.acceptFriend(id) }
            acting = null
            if (ok.isSuccess) {
                // 本地先摘掉，界面立刻有反馈；再让 IM 侧刷一次，好友关系才是真的通了
                requests = requests.filterNot { it.requestId == id }
                withContext(Dispatchers.IO) { ImRepository.refreshConversations() }
            } else {
                val msg = ok.exceptionOrNull()?.message.orEmpty()
                showMiuixInfo(
                    context = context,
                    title = context.getString(R.string.friend_requests_failed),
                    message = msg.ifBlank { context.getString(R.string.common_retry_later) },
                    confirm = context.getString(R.string.common_ok),
                )
            }
        }
    }

    fun confirmDelete(request: FriendRequest) {
        val id = request.requestId ?: return
        if (acting != null) return
        showMiuixConfirm(
            context = context,
            title = context.getString(R.string.friend_requests_delete_title),
            message = request.name?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.friend_requests_delete_message, it) }
                ?: context.getString(R.string.friend_requests_delete_message_generic),
            confirmText = context.getString(R.string.common_delete),
            dismissText = context.getString(R.string.common_cancel),
            onConfirm = {
                acting = id
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { RceApi.deleteFriendRequest(id) }
                    acting = null
                    if (ok.isSuccess) {
                        requests = requests.filterNot { it.requestId == id }
                    } else {
                        val msg = ok.exceptionOrNull()?.message.orEmpty()
                        showMiuixInfo(
                            context = context,
                            title = context.getString(R.string.friend_requests_failed),
                            message = msg.ifBlank { context.getString(R.string.common_retry_later) },
                            confirm = context.getString(R.string.common_ok),
                        )
                    }
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        errorText?.let { text ->
            // 拉取失败只提示一行，不挡住整个页面：列表可能是上一次的缓存内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.friend_requests_load_failed, text),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            when {
                loading && requests.isEmpty() -> item("loading") {
                    LoadingBlock(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        label = stringResource(R.string.im_loading),
                    )
                }

                requests.isEmpty() -> item("empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.friend_requests_empty),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.friend_requests_empty_hint),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                else -> {
                    items(
                        requests,
                        key = { it.requestId ?: it.uid ?: it.hashCode().toString() },
                    ) { request ->
                        RequestRow(
                            request = request,
                            busy = acting == request.requestId,
                            onAccept = { accept(request) },
                            onDelete = { confirmDelete(request) },
                        )
                    }
                    item("tail") { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }
}

/** 一条好友申请：头像 + 姓名 + 验证语 + 时间，右侧「接受」 */
@Composable
private fun RequestRow(
    request: FriendRequest,
    busy: Boolean,
    onAccept: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = request.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.moments_anonymous)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 点整行 = 删除这条申请（和官方一致：不接受就删掉，不留垃圾）
                .pressableRow(onClick = onDelete, corner = 16.dp)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanhangAvatar(
                url = request.portraitUrl?.takeIf { it.isNotBlank() },
                name = name,
                size = 42.dp,
                modifier = Modifier.clip(CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = shortTime(request.createDt),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                // `content` 声明在 engine 模块，属于跨模块 public API 属性，Kotlin 不允许对它 smart cast；
                // 先落成局部变量再判空（否则报 "Smart cast to 'String' is impossible"）。
                val contentText = request.content.orEmpty()
                if (contentText.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = contentText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (request.pending) {
                TextButton(
                    text = stringResource(R.string.friend_requests_accept),
                    onClick = { if (!busy) onAccept() },
                    enabled = !busy,
                )
            } else {
                Text(
                    text = stateLabel(request.state),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 申请状态文案（照官方 `FriendStatus` 枚举：0 邀请 / 1 被邀请 / 2 接受 / 3 已同意 / 4 超时） */
private fun stateLabel(state: Int): String = when (state) {
    FriendRequest.STATE_ACCEPT, FriendRequest.STATE_ACCEPTED -> "已添加"
    FriendRequest.STATE_TIME_OUT -> "已过期"
    else -> "待处理"
}

/** 申请时间：今天只显示时分，其余显示月日 */
private fun shortTime(millis: Long): String {
    if (millis <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd"
    return runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis)) }
        .getOrDefault("")
}
