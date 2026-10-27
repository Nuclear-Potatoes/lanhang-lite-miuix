package com.lanhang.miuix.im

import com.lanhang.miuix.ui.miuix.pressableRow
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.lanhang.miuix.moments.ImagePreviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.lanhang.miuix.R
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.ui.MainActivity
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.LanhangIcons
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 私信（融云 IM：单聊 + 群聊）。
 *
 * 两个页面都在「我的」这一栈里（见 [com.lanhang.miuix.mine.MinePage]）：
 * 会话列表 + 聊天页。UI 全是 Miuix 自绘，**没有用融云 IMKit 的 Activity**，
 * 所以观感和其它页面一致（顶栏还是外壳那一套）。
 *
 * 2026-10-26 用户要求「IM 里也要有群聊」：会话列表把 [ImConversation.isGroup] 的也列出来，
 * 群会话页在别人的气泡上方显示发送者名字。
 * ⚠️ IMLib 2.9 的客户端 SDK 没有 `createGroup`（建群属于服务端 / Server API，要学校融云账号的
 * app secret），所以这里只提供「加入已有群（群 id）」——不做假入口。
 */
@Composable
fun ImConversationsScreen(
    modifier: Modifier = Modifier,
    /** 滚动位置由外面的「我的」页面栈持有（用户第九轮第 3 条） */
    listState: LazyListState = rememberLazyListState(),
    onOpenChat: (ImConversation) -> Unit,
    /** 点头像 / 姓名 → 这个人的个人主页（群聊没有群资料页，不假装有） */
    onOpenProfile: (String) -> Unit = {},
) {
    LaunchedEffect(Unit) { ImRepository.ensureStarted() }

    // 第一次进私信时申请通知权限（Android 13+）；拒绝了也只是没有通知，聊天不受影响
    val context = LocalContext.current
    LaunchedEffect(Unit) { context.findActivity()?.ensureNotifyPermission() }

    // 每 20 秒静默刷一次会话列表（私信不是实时页面，别做长连接之外的额外开销）
    LaunchedEffect(ImRepository.status) {
        if (ImRepository.status == ImRepository.Status.CONNECTED) {
            while (true) {
                kotlinx.coroutines.delay(20_000)
                ImRepository.refreshConversations()
            }
        }
    }

    val conversations = ImRepository.conversations

    var joining by remember { mutableStateOf(false) }
    var groupId by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        ImStatusBar(
            onRefresh = { ImRepository.ensureStarted() },
            joining = joining,
            onJoinGroup = { joining = !joining },
        )

        ImRepository.notice?.let { text ->
            NoticeBar(text = text, onClose = { ImRepository.clearNotice() })
        }

        if (joining) {
            JoinGroupCard(
                groupId = groupId,
                groupName = groupName,
                onGroupId = { groupId = it },
                onGroupName = { groupName = it },
                onJoin = {
                    ImRepository.joinGroup(groupId, groupName)
                    joining = false
                    groupId = ""
                    groupName = ""
                },
                onCancel = { joining = false },
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            when {
                conversations.isEmpty() && ImRepository.loadingConversations -> item("loading") {
                    ImStateBox(stringResource(R.string.im_loading), null) {}
                }

                conversations.isEmpty() -> item("empty") {
                    ImStateBox(
                        message = stringResource(R.string.im_empty),
                        action = null,
                        hint = stringResource(R.string.im_empty_hint),
                    ) {}
                }

                else -> {
                    items(
                        conversations,
                        key = { (if (it.isGroup) "g:" else "p:") + it.peerId },
                    ) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { onOpenChat(conversation) },
                            // 单聊的头像 / 姓名点进去是这个人的主页；群聊没有群资料页，保持原样
                            onOpenProfile = if (conversation.isGroup) {
                                null
                            } else {
                                { onOpenProfile(conversation.peerId) }
                            },
                        )
                    }
                    item("tail") { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }
}

/** 加入群聊：内联表单（不用对话框，免得跟 Miuix 的弹层宿主耦合） */
@Composable
private fun JoinGroupCard(
    groupId: String,
    groupName: String,
    onGroupId: (String) -> Unit,
    onGroupName: (String) -> Unit,
    onJoin: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.im_join_group),
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = groupId,
                onValueChange = onGroupId,
                label = stringResource(R.string.im_join_group_id),
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = groupName,
                onValueChange = onGroupName,
                label = stringResource(R.string.im_join_group_name),
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.im_join_group_tip),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = stringResource(R.string.common_cancel), onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.im_join_group_do),
                    onClick = onJoin,
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

/** 一次性提示条（加入群聊成功/失败），点右侧「关闭」清掉 */
@Composable
private fun NoticeBar(text: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.common_close),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onClose)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: ImConversation,
    onClick: () -> Unit,
    /** 头像 / 姓名点击（null = 这行没有个人主页可去，比如群聊） */
    onOpenProfile: (() -> Unit)? = null,
) {
    // 读一下版本号：批量接口刚把姓名拿回来时，这一行要立刻重绘
    // （2026-10-26 第七轮：之前只能等 20 秒轮询，用户看到的就是「名字一直不出来」）
    UserInfoCache.revision
    val brief = if (conversation.isGroup) null else UserInfoCache.get(conversation.peerId)
    val name = if (conversation.isGroup) {
        conversation.title
    } else {
        brief?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.moments_anonymous)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressableRow(onClick = onClick, corner = 16.dp)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conversation.isGroup) {
                GroupAvatar()
            } else {
                LanhangAvatar(
                    url = brief?.portrait_url?.takeIf { it.isNotBlank() },
                    name = name,
                    size = 42.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(
                            if (onOpenProfile != null) Modifier.clickable(onClick = onOpenProfile)
                            else Modifier
                        ),
                )
            }
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
                            .then(
                                if (onOpenProfile != null) Modifier.clickable(onClick = onOpenProfile)
                                else Modifier
                            ),
                    )
                    if (conversation.isGroup) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.im_group),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = shortTime(conversation.time),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = previewText(conversation),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conversation.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = if (conversation.unread > 99) "99+"
                                else conversation.unread.toString(),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 会话列表那一行的小字：群聊要带「谁说的」，否则一屏消息不知道谁发的 */
@Composable
private fun previewText(conversation: ImConversation): String {
    // 群聊：没有任何消息 / 最后一条解不出来时也要有一句人话（2026-10-27 修「私信里没有群聊」）
    val body = conversation.preview.ifBlank {
        if (conversation.isGroup) {
            androidx.compose.ui.res.stringResource(R.string.im_preview_empty_group)
        } else {
            androidx.compose.ui.res.stringResource(R.string.im_preview_unsupported)
        }
    }
    if (!conversation.isGroup) return body
    val sender = conversation.previewSenderId
    if (sender.isBlank()) return body
    val name = UserInfoCache.get(sender)?.name?.takeIf { it.isNotBlank() }
        ?: if (sender == com.lanhang.miuix.session.Session.userId) {
            "我"
        } else if (sender.length > 4) {
            "成员 · ${sender.takeLast(4)}"
        } else {
            "成员"
        }
    return "$name：$body"
}

/** 群聊头像：圆角方块 + 通讯录图标（和单聊的圆形人像区分开） */
@Composable
private fun GroupAvatar() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LanhangIcons.Contacts,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 聊天页：文本消息（发/收/历史），单聊与群聊共用 */
@Composable
fun ImChatScreen(
    peerId: String,
    peerName: String,
    modifier: Modifier = Modifier,
    /** 滚动位置由外面的「我的」页面栈持有（用户第九轮第 3 条） */
    listState: LazyListState = rememberLazyListState(),
    /** 点别人的头像 / 姓名 → 那个人的个人主页（群聊里每个人的 senderId 都不同） */
    onOpenProfile: (String) -> Unit = {},
    /** 点自己的头像 → 我的个人主页 */
    onOpenSelf: () -> Unit = {},
) {
    // 群/单聊由会话列表决定（从别人主页直接点「发私信」进来的一定是单聊）
    val isGroup = ImRepository.isGroup(peerId)

    LaunchedEffect(peerId, isGroup) {
        ImRepository.ensureStarted()
        ImRepository.loadMessages(peerId, isGroup)
    }

    // 正在看这个会话：进来清掉它的通知，期间不弹新通知；离开时解除标记
    val chatContext = LocalContext.current
    DisposableEffect(peerId) {
        ImNotify.activePeerId = peerId
        ImNotify.cancel(chatContext, peerId)
        onDispose {
            if (ImNotify.activePeerId == peerId) ImNotify.activePeerId = null
        }
    }
    val messages = ImRepository.thread(peerId)
    var draft by remember(peerId) { mutableStateOf("") }
    // 表情面板是否展开（切会话时收起）
    var emojiOpen by remember(peerId) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    // 键盘一露头就收起表情面板：两者**互斥**，同屏时输入区会被顶掉半个屏幕
    // （用户 2026-10-26 第五轮：「发消息时所有元素被抬到不正常的高度」）
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) { if (imeVisible) emojiOpen = false }

    // ---- 富消息（2026-10-26 第六轮）：图片 / 视频 / 文件 / 语音 / 位置
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「+」面板是否展开（与表情、键盘三者互斥）
    var attachOpen by remember(peerId) { mutableStateOf(false) }
    // 语音模式：输入框换成「按住 说话」
    var voiceMode by remember(peerId) { mutableStateOf(false) }
    var recording by remember(peerId) { mutableStateOf(false) }
    var hint by remember(peerId) { mutableStateOf<String?>(null) }
    val recorder = remember(peerId) { ImMedia.VoiceRecorder(context) }
    DisposableEffect(peerId) { onDispose { recorder.cancel() } }
    // 提示语两秒后自己消失
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2200)
            hint = null
        }
    }
    fun say(text: String) { hint = text }
    // 键盘一露头就把附件面板收掉（键盘 / 表情 / 附件三选一，同屏时输入区会被顶掉半屏）
    LaunchedEffect(imeVisible) { if (imeVisible) attachOpen = false }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                ImMedia.copyToCache(
                    context, picked,
                    "img_${System.currentTimeMillis()}." + (ImMedia.queryName(context, picked)?.substringAfterLast('.', "")?.ifBlank { "jpg" } ?: "jpg"),
                )
            }
            if (file == null) say(context.getString(R.string.im_attach_failed))
            else ImRepository.sendImage(peerId, Uri.fromFile(file), isGroup)
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (file, info) = withContext(Dispatchers.IO) {
                val copied = ImMedia.copyToCache(context, picked, "video_${System.currentTimeMillis()}.mp4")
                copied to ImMedia.videoInfo(context, picked)
            }
            if (file == null) {
                say(context.getString(R.string.im_attach_failed))
                return@launch
            }
            if (info.second <= 0) {
                say(context.getString(R.string.im_attach_failed))
                return@launch
            }
            ImRepository.sendVideo(
                peerId = peerId,
                video = Uri.fromFile(file),
                thumb = info.first?.let { Uri.fromFile(it) },
                durationSec = info.second,
                isGroup = isGroup,
            )
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val name = ImMedia.queryName(context, picked) ?: "file_${System.currentTimeMillis()}"
                val size = ImMedia.querySize(context, picked)
                ImMedia.copyToCache(context, picked, name)?.let { Triple(it, name, size) }
            }
            if (prepared == null) {
                say(context.getString(R.string.im_attach_failed))
                return@launch
            }
            ImRepository.sendFile(peerId, Uri.fromFile(prepared.first), prepared.second, prepared.third, isGroup)
        }
    }
    // 位置：先要定位权限，拿不到上次已知点就直说，不编坐标
    fun sendLocationNow() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { ImMedia.currentLocation(context) }
            if (found == null) say(context.getString(R.string.im_location_failed))
            else ImRepository.sendLocation(peerId, found.first.latitude, found.first.longitude, found.second, isGroup)
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sendLocationNow() else say(context.getString(R.string.im_location_permission))
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceMode = true else say(context.getString(R.string.im_record_permission))
    }
    fun ensurePermission(permission: String, request: androidx.activity.result.ActivityResultLauncher<String>, onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) onGranted()
        else request.launch(permission)
    }

    // 新消息进来 / 首次加载完成后滚到底：第一次是「瞬移」（页面刚进来，
    // 播动画会看到列表从顶上滑下来），之后才用动画跟着新消息走
    var jumpedToBottom by remember(peerId) { mutableStateOf(false) }
    LaunchedEffect(messages.size, ImRepository.status) {
        if (messages.isEmpty()) return@LaunchedEffect
        val last = messages.size - 1
        if (jumpedToBottom) listState.animateScrollToItem(last) else listState.scrollToItem(last)
        jumpedToBottom = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (ImRepository.status != ImRepository.Status.CONNECTED) {
            ImStatusBar(onRefresh = { ImRepository.ensureStarted() })
        }

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ImStateBox(
                    message = when {
                        ImRepository.status != ImRepository.Status.CONNECTED ->
                            stringResource(R.string.im_loading)
                        isGroup -> stringResource(R.string.im_group_empty)
                        else -> stringResource(R.string.im_thread_empty)
                    },
                    action = null,
                ) {}
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                items(messages, key = { it.key }) { message ->
                    MessageBubble(
                        message = message,
                        peerId = peerId,
                        peerName = peerName,
                        isGroup = isGroup,
                        onOpenProfile = onOpenProfile,
                        onOpenSelf = onOpenSelf,
                        onHint = { say(it) },
                    )
                }
            }
        }

        // 输入条：贴底、跟着键盘上移。表情面板 / 「+」面板都开在输入条**上方**，
        // 三者（键盘、表情、附件）互斥——同屏时输入区会被顶掉半个屏幕（第五轮用户反馈）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            hint?.let { text ->
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
            if (emojiOpen) {
                EmojiPanel(
                    onPick = { draft += it },
                    onBackspace = { draft = draft.dropLastEmoji() },
                )
            }
            if (attachOpen) {
                AttachPanel(
                    onImage = {
                        attachOpen = false
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onVideo = {
                        attachOpen = false
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onFile = {
                        attachOpen = false
                        runCatching { filePicker.launch(arrayOf("*/*")) }
                            .onFailure { say(context.getString(R.string.im_attach_failed)) }
                    },
                    onLocation = {
                        attachOpen = false
                        ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION, locationPermission) {
                            sendLocationNow()
                        }
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 「+」：附件面板
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (attachOpen) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable {
                            if (attachOpen) {
                                attachOpen = false
                            } else {
                                keyboard?.hide()
                                emojiOpen = false
                                attachOpen = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = LanhangIcons.Plus,
                        contentDescription = stringResource(R.string.im_attach),
                        tint = if (attachOpen) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (emojiOpen) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable {
                            attachOpen = false
                            if (emojiOpen) {
                                // 收起面板 → 把键盘叫回来（微信同款：面板和键盘互相顶替）
                                emojiOpen = false
                                keyboard?.show()
                            } else {
                                keyboard?.hide()
                                voiceMode = false
                                emojiOpen = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = LanhangIcons.Emoji,
                        contentDescription = stringResource(R.string.im_emoji),
                        tint = if (emojiOpen) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (voiceMode) {
                    // 按住说话：手指抬起就发，太短（<1 秒）丢弃并提示
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (recording) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MiuixTheme.colorScheme.surfaceContainerHigh
                            )
                            .pointerInput(peerId) {
                                detectTapGestures(
                                    onPress = {
                                        if (!recorder.start()) {
                                            say(context.getString(R.string.im_record_failed))
                                            return@detectTapGestures
                                        }
                                        recording = true
                                        tryAwaitRelease()
                                        recording = false
                                        val result = recorder.stop()
                                        if (result == null) {
                                            say(context.getString(R.string.im_voice_too_short))
                                        } else {
                                            ImRepository.sendVoice(
                                                peerId = peerId,
                                                uri = Uri.fromFile(result.first),
                                                durationSec = result.second,
                                                isGroup = isGroup,
                                            )
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (recording) R.string.im_voice_release else R.string.im_voice_hold
                            ),
                            style = MiuixTheme.textStyles.body1,
                            color = if (recording) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = stringResource(R.string.im_input_hint),
                            minLines = 1,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (voiceMode && draft.isBlank()) {
                    // 语音模式下的右侧按钮 = 切回键盘
                    TextButton(
                        text = stringResource(R.string.im_keyboard),
                        onClick = {
                            voiceMode = false
                            keyboard?.show()
                        },
                    )
                } else if (draft.isBlank()) {
                    TextButton(
                        text = stringResource(R.string.im_voice),
                        onClick = {
                            keyboard?.hide()
                            emojiOpen = false
                            attachOpen = false
                            ensurePermission(Manifest.permission.RECORD_AUDIO, micPermission) {
                                voiceMode = true
                            }
                        },
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.im_send),
                        onClick = {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                ImRepository.sendText(peerId, body, isGroup)
                                draft = ""
                                emojiOpen = false
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 表情面板（2026-10-26 第四轮，用户要求「群聊与私信都可以发表情，参考原蓝航」）。
 *
 * 口径说明：原版蓝航用的是融云 IMKit 自带的表情面板 + `rc_emoji_*` 位图，那套资源属于 IMKit；
 * 本项目只接 IMLib、界面全自绘，所以这里换成**系统 Unicode 表情**——落到的还是普通文本消息
 * （`TextMessage`），对方用官方客户端照样能看见，不需要额外贴图协议，也没有版权资源问题。
 *
 * 面板本身是 8 列宫格 + 右下角退格，尺寸跟输入框对齐，展开时输入框不会被 pushing 出屏幕。
 */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = remember { IM_EMOJI.chunked(EMOJI_COLUMNS) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(EMOJI_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBackspace),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = LanhangIcons.Backspace,
                        contentDescription = stringResource(R.string.im_emoji_backspace),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** 每行几个表情 */
private const val EMOJI_COLUMNS = 8

/** 常用表情（微信那套常用顺序的一小份，够聊天用；纯 Unicode 文本，不依赖任何图片资源） */
private val IM_EMOJI: List<String> = listOf(
    "😀", "😄", "😁", "😆", "😅", "😂", "🙂", "😊",
    "😍", "🥰", "😘", "😜", "🤪", "😳", "😏", "😒",
    "😔", "😟", "😭", "😢", "😤", "😡", "🤔", "🤗",
    "😴", "😪", "🤤", "😷", "🤒", "🥵", "🥶", "😱",
    "👍", "👎", "👌", "✌️", "🤝", "🙏", "💪", "👏",
    "🎉", "🎂", "🌹", "❤️", "💔", "🔥", "⭐", "☀️",
    "🌙", "☕", "🍚", "🍜", "🍻", "⚽", "🏀", "🚀",
)

/** 删掉草稿里最后一个字符（表情是代理对，按码点删，否则会留下半个字符） */
private fun String.dropLastEmoji(): String {
    if (isEmpty()) return this
    val end = offsetByCodePoints(length, -1)
    return substring(0, end)
}

@Composable
private fun MessageBubble(
    message: ImMessage,
    peerId: String,
    peerName: String,
    isGroup: Boolean = false,
    onOpenProfile: (String) -> Unit = {},
    onOpenSelf: () -> Unit = {},
    onHint: (String) -> Unit = {},
) {
    val mine = message.mine
    // 群聊：发送者优先按消息里的 senderId 查（每个人都不一样）；单聊只有对方一个人
    val senderId = message.senderId.takeIf { it.isNotBlank() } ?: peerId
    UserInfoCache.revision
    val brief = if (mine) null else UserInfoCache.get(senderId)
    val name = when {
        brief?.name?.isNotBlank() == true -> brief.name
        // 群里查不到名字时**不要**拿群名当人名（会显示成「群聊 · xxxx 说：」）
        isGroup -> if (senderId.length > 4) "成员 · ${senderId.takeLast(4)}" else "群成员"
        peerName.isNotBlank() -> peerName
        else -> stringResource(R.string.moments_anonymous)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            LanhangAvatar(
                url = brief?.portrait_url?.takeIf { it.isNotBlank() },
                name = name,
                size = 34.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    // 点头像 → 这个人的个人主页（2026-10-26 第四轮：所有头像/姓名都要能点进主页）
                    .clickable { onOpenProfile(senderId) },
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 268.dp),
        ) {
            // 群聊里别人的消息要标出发送者，否则分不清谁说的；名字本身也能点进主页
            if (!mine && isGroup) {
                Text(
                    text = name,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 4.dp, bottom = 2.dp)
                        .clickable { onOpenProfile(senderId) },
                )
            }
            val bubbleColor = if (mine) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.surfaceContainerHigh
            // 图片气泡自己带圆角和尺寸，不再套内边距（否则四周会多出一圈色边）
            if (message.kind == ImMessage.KIND_IMAGE) {
                BubbleBody(message = message, mine = mine, onHint = onHint)
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(bubbleColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BubbleBody(message = message, mine = mine, onHint = onHint)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = when (message.state) {
                    ImMessage.STATE_SENDING -> stringResource(R.string.im_sending)
                    ImMessage.STATE_FAILED -> stringResource(R.string.im_send_failed)
                    else -> clockTime(message.time)
                },
                style = MiuixTheme.textStyles.footnote2,
                color = if (message.state == ImMessage.STATE_FAILED) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
        if (mine) {
            Spacer(Modifier.width(8.dp))
            LanhangAvatar(
                url = com.lanhang.miuix.session.Session.avatarUrl.takeIf { it.isNotBlank() },
                name = com.lanhang.miuix.session.Session.name.ifBlank {
                    stringResource(R.string.im_self)
                },
                size = 34.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    // 自己的头像 → 我的个人主页
                    .clickable(onClick = onOpenSelf),
            )
        }
    }
}

/**
 * 气泡正文：按 [ImMessage.kind] 分支（文本 / 图片 / 视频 / 文件 / 语音 / 位置）。
 *
 * 图片点开进应用内预览；视频、文件、位置交给系统（OpenGL 播放器 / 文件查看器 / 地图）；
 * 语音在应用内播放，同一时间只播一条（状态在 [ImMedia.Player]）。
 */
@Composable
private fun BubbleBody(message: ImMessage, mine: Boolean, onHint: (String) -> Unit = {}) {
    val context = LocalContext.current
    val textColor = if (mine) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    when (message.kind) {
        ImMessage.KIND_IMAGE -> AsyncImage(
            model = message.thumb.ifBlank { message.uri },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(176.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    val url = message.uri.ifBlank { message.thumb }
                    if (url.isNotBlank()) {
                        runCatching { ImagePreviewActivity.start(context, listOf(url), 0) }
                    }
                },
        )

        ImMessage.KIND_VIDEO -> Box(
            modifier = Modifier
                .width(176.dp)
                .height(118.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.06f))
                .clickable {
                    if (message.uri.isNotBlank()) {
                        ImMedia.openFile(
                            context = context,
                            uri = message.uri,
                            name = "video_${message.key.takeLast(6)}.mp4",
                            onError = onHint,
                        )
                    }
                },
        ) {
            if (message.thumb.isNotBlank()) {
                AsyncImage(
                    model = message.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "▶", color = Color.White, fontSize = 15.sp)
            }
            if (message.duration > 0) {
                Text(
                    text = "${message.duration}s",
                    style = MiuixTheme.textStyles.footnote2,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        ImMessage.KIND_FILE -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                if (message.uri.isNotBlank()) {
                    ImMedia.openFile(
                        context = context,
                        uri = message.uri,
                        name = message.name.ifBlank { "file" },
                        onError = onHint,
                    )
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (mine) Color.White.copy(alpha = 0.22f)
                        else MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.name.substringAfterLast('.', "?").take(4).uppercase(Locale.US),
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (mine) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = message.name.ifBlank { stringResource(R.string.im_file) },
                    style = MiuixTheme.textStyles.body1,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Text(
                    text = ImMedia.formatSize(message.size).ifBlank { stringResource(R.string.im_file_tap_open) },
                    style = MiuixTheme.textStyles.footnote2,
                    color = textColor.copy(alpha = 0.72f),
                )
            }
        }

        ImMessage.KIND_VOICE -> {
            val playing = ImMedia.Player.playingKey == message.key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    if (message.uri.isNotBlank()) ImMedia.Player.toggle(context, message.key, message.uri)
                },
            ) {
                Text(text = if (playing) "■" else "▶", color = textColor, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                // 时长越长条越宽（微信那种），60 秒封顶
                Box(
                    modifier = Modifier
                        .width((28 + message.duration.coerceIn(1, 60) * 2.4f).dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(textColor.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${message.duration}″",
                    style = MiuixTheme.textStyles.body1,
                    color = textColor,
                )
            }
        }

        ImMessage.KIND_LOCATION -> Column(
            modifier = Modifier.clickable {
                ImMedia.openLocation(context, message.lat, message.lng, message.poi)
            },
        ) {
            Text(
                text = message.poi.ifBlank { stringResource(R.string.im_location) },
                style = MiuixTheme.textStyles.body1,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Text(
                text = String.format(Locale.US, "%.5f, %.5f", message.lat, message.lng),
                style = MiuixTheme.textStyles.footnote2,
                color = textColor.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        else -> Text(
            text = message.text,
            style = MiuixTheme.textStyles.body1,
            color = textColor,
        )
    }
}

/** 「+」附件面板：图片 / 视频 / 文件 / 位置四项（语音走输入条右侧的麦克风） */
@Composable
private fun AttachPanel(
    onImage: () -> Unit,
    onVideo: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
) {
    val entries = listOf(
        stringResource(R.string.im_attach_image) to onImage,
        stringResource(R.string.im_attach_video) to onVideo,
        stringResource(R.string.im_attach_file) to onFile,
        stringResource(R.string.im_attach_location) to onLocation,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            entries.forEach { (label, action) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = action)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label.take(1),
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 顶部一行状态：连接中 / 失败重试 / 加入群聊 / 刷新 */
@Composable
private fun ImStatusBar(
    onRefresh: () -> Unit,
    joining: Boolean = false,
    onJoinGroup: (() -> Unit)? = null,
) {
    val status = ImRepository.status
    val text = when (status) {
        ImRepository.Status.CONNECTED -> ImRepository.error ?: stringResource(R.string.im_connected)
        ImRepository.Status.CONNECTING -> stringResource(R.string.im_connecting)
        ImRepository.Status.FAILED -> ImRepository.error ?: stringResource(R.string.im_failed)
        else -> stringResource(R.string.im_connecting)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onJoinGroup != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (joining) R.string.common_cancel else R.string.im_join_group
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable(onClick = onJoinGroup)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.im_refresh),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                .clickable(onClick = onRefresh)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** 空态 / 加载态 / 错误态的居中卡片 */
@Composable
private fun ImStateBox(message: String, action: String?, hint: String? = null, onAction: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(text = action, onClick = onAction)
            }
        }
    }
}

/* ------------------------------------------------------------------ 时间 */

private fun clockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** 会话列表用：今天只给时分，昨天写「昨天」，更早给月-日 */
private fun shortTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return clockTime(millis)
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天"
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
}

/** 从 Compose 的 Context 里捞出宿主 Activity（申请权限要用） */
internal fun Context.findActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return (current as? Activity) as? MainActivity
}
