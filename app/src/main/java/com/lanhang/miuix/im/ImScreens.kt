package com.lanhang.miuix.im

import com.lanhang.miuix.ui.miuix.pressableRow
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.lanhang.miuix.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.ui.MainActivity
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.LanhangIcons
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.LoadingBlock
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Location
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Send

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
    /** 列表顶部「新的朋友」入口 → 好友申请列表（第六十三轮第 3 条） */
    onOpenFriendRequests: () -> Unit = {},
) {
    LaunchedEffect(Unit) { ImRepository.ensureStarted() }

    // 第一次进私信时申请通知权限（Android 13+）；拒绝了也只是没有通知，聊天不受影响
    val context = LocalContext.current
    LaunchedEffect(Unit) { context.findActivity()?.ensureNotifyPermission() }

    // 2026-11-15 第八十三轮：这里的 20 秒轮询撤了 —— 自动刷新收进 ImRepository 的心跳
    // （前台每 12s 对齐一次会话与当前会话，带失败重连与卡死自救，见 setForeground / heartbeatTick）。
    // 页面自己再轮一份只会让同一秒发两个请求，还会在页面不可见时白跑。

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
            // 第六十三轮第 3 条（用户：「原版蓝航有好友申请功能，加入这个功能并做到私信页面中」）：
            // 会话列表最上面固定一条「新的朋友」，点进去是好友申请页。
            // 官方原版也是把它放在私信会话列表顶部（NewFriendsListActivity）。
            item("newFriends") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressableRow(onClick = onOpenFriendRequests, corner = 16.dp)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.im_new_friends),
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.im_new_friends_summary),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "›",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            when {
                // 用户第 11 条：私信列表加载中统一成转圈 + 文案
                conversations.isEmpty() && ImRepository.loadingConversations -> item("loading") {
                    LoadingBlock(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        label = stringResource(R.string.im_loading),
                    )
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
@OptIn(ExperimentalFoundationApi::class)
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
        // 第八十三轮：告诉仓库「这条会话正在被看着」，心跳会顺带把它对齐
        //（融云的消息回调漏一条时，聊天页 12 秒内也会自己补上）
        ImRepository.setActiveChat(peerId, isGroup)
        ImNotify.cancel(chatContext, peerId)
        onDispose {
            ImRepository.setActiveChat(null, false)
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

    // ---- 2026-11-15 第八十八轮：长按气泡菜单（复制 / 多选 / 删除）+ 多选批量删除 / 导出长图
    var menuMessage by remember(peerId) { mutableStateOf<ImMessage?>(null) }
    var menuBounds by remember(peerId) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var selectMode by remember(peerId) { mutableStateOf(false) }
    var selectedKeys by remember(peerId) { mutableStateOf<Set<String>>(emptySet()) }
    val hostView = LocalView.current
    var chatTopPx by remember { mutableStateOf(0f) }

    fun exitSelection() {
        selectMode = false
        selectedKeys = emptySet()
    }

    fun toggleSelect(key: String) {
        selectedKeys = selectedKeys.toMutableSet().apply { if (!add(key)) remove(key) }
    }

    /** QQ 的「删除」是删本机，不是撤回；条数在弹窗里写清楚，避免误删 */
    fun requestDelete(keys: Set<String>) {
        if (keys.isEmpty()) return
        val count = keys.size
        showMiuixConfirm(
            context = chatContext,
            title = chatContext.getString(R.string.im_delete_title),
            message = chatContext.getString(R.string.im_delete_message, count),
            confirmText = chatContext.getString(R.string.common_delete),
            dismissText = chatContext.getString(R.string.common_cancel),
        ) {
            ImRepository.deleteMessages(peerId, keys)
            exitSelection()
            say(chatContext.getString(R.string.im_deleted, count))
        }
    }



    // ---- 第八十九轮：QQ 式截图模式（点起止消息选范围 → 只渲染范围内的消息成长图）
    var shotMode by remember(peerId) { mutableStateOf(false) }
    var shotStart by remember(peerId) { mutableStateOf<Int?>(null) }
    var shotEnd by remember(peerId) { mutableStateOf<Int?>(null) }
    var hideNames by remember(peerId) { mutableStateOf(false) }
    var shotBusy by remember(peerId) { mutableStateOf(false) }
    var shotSheetFile by remember(peerId) { mutableStateOf<java.io.File?>(null) }
    var shotPreviewBmp by remember(peerId) { mutableStateOf<android.graphics.Bitmap?>(null) }

    /** 完整范围（起止都选了）：[min, max] 闭区间下标（局部变量不能带 getter，用函数） */
    fun shotRange(): IntRange? {
        val a = shotStart; val b = shotEnd
        return if (a != null && b != null) minOf(a, b)..maxOf(a, b) else null
    }

    fun exitShotMode() {
        shotMode = false
        shotStart = null
        shotEnd = null
        shotSheetFile = null
        shotPreviewBmp = null
    }

    /** QQ 的交互：第一次点 = 起点，第二次点 = 终点；已完整时再点 = 从新消息重新开始 */
    fun pickShotRange(idx: Int) {
        if (shotStart == null || (shotStart != null && shotEnd != null)) {
            shotStart = idx
            shotEnd = null
        } else if (idx != shotStart) {
            shotEnd = idx
        }
    }

    /**
     * 生成范围内消息的长图。preview=true 用应用内浮层预览；否则弹 QQ 式底部弹层
     * （发送给好友 = 系统分享 / 保存到手机 = MediaStore）。
     */
    fun generateShot(preview: Boolean) {
        val range = shotRange() ?: return
        if (shotBusy) return
        val activity = chatContext as? Activity
        if (activity == null || hostView.width <= 0) {
            say(chatContext.getString(R.string.im_shot_failed))
            return
        }
        // messages 是快照列表，直接按下标取（范围就是按当前列表下标记的）
        val picked = messages.slice(range)
        if (picked.isEmpty()) return
        scope.launch {
            shotBusy = true
            say(chatContext.getString(R.string.im_shot_generating))
            val bmp = ImShot.renderLongImage(
                activity = activity,
                widthPx = hostView.width,
                owner = hostView,
            ) {
                Column(
                    modifier = Modifier
                        .background(MiuixTheme.colorScheme.background)
                        .padding(vertical = 10.dp),
                ) {
                    picked.forEach { m ->
                        MessageBubble(
                            message = m,
                            peerId = peerId,
                            peerName = peerName,
                            // 隐藏昵称：非群聊本来就没有名字，这里把群标记也关掉
                            isGroup = isGroup && !hideNames,
                        )
                    }
                }
            }
            shotBusy = false
            if (bmp == null) {
                say(chatContext.getString(R.string.im_shot_failed))
                return@launch
            }
            if (preview) {
                shotPreviewBmp = bmp
            } else {
                val file = ImShot.saveShareFile(chatContext, bmp, "lanhang_chat_" + System.currentTimeMillis())
                if (file == null) say(chatContext.getString(R.string.im_shot_failed))
                else shotSheetFile = file
            }
        }
    }

    // 多选 / 截图 / 菜单开着的时候按返回 = 先收掉它们，而不是直接退出会话
    androidx.activity.compose.BackHandler(enabled = selectMode || shotMode || menuMessage != null) {
        when {
            menuMessage != null -> menuMessage = null
            shotMode -> exitShotMode()
            else -> exitSelection()
        }
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { chatTopPx = it.positionInRoot().y },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 截图模式顶栏（QQ 同款：标题「截图」+ 副标题「点击起止消息可选择范围」+ 右侧「清空选择」）
            if (shotMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_cancel),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { exitShotMode() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.im_shot_title),
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.im_shot_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.im_shot_clear),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { shotStart = null; shotEnd = null }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            // 多选顶栏（QQ 同款：左边「取消」，中间「已选择 N 条消息」）
            if (selectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_cancel),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { exitSelection() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.im_selected_count, selectedKeys.size),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(40.dp))
                }
            }
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
                itemsIndexed(messages, key = { _, m -> m.key }) { index, message ->
                    // 记住气泡在窗口里的位置：长按菜单要贴着这条气泡弹（第八十八轮）
                    var itemBounds by remember(message.key) {
                        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                    }
                    val dimmed = shotMode && index !in (shotRange() ?: IntRange.EMPTY)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { itemBounds = it.boundsInRoot() }
                            .combinedClickable(
                                onClick = {
                                    when {
                                        selectMode -> toggleSelect(message.key)
                                        shotMode -> pickShotRange(index)
                                    }
                                },
                                onLongClick = {
                                    if (!selectMode && !shotMode) {
                                        menuMessage = message
                                        menuBounds = itemBounds
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectMode) {
                            SelectDot(
                                checked = message.key in selectedKeys,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            MessageBubble(
                                message = message,
                                peerId = peerId,
                                peerName = peerName,
                                isGroup = isGroup,
                                onOpenProfile = onOpenProfile,
                                onOpenSelf = onOpenSelf,
                                onHint = { say(it) },
                                interactive = !selectMode && !shotMode,
                                onLongPress = if (selectMode || shotMode) null else {
                                    { menuMessage = message; menuBounds = itemBounds }
                                },
                            )
                            // QQ 式截图：范围外的消息整条灰化，选中的保持高亮
                            if (dimmed) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 输入条：贴底、跟着键盘上移。表情面板 / 「+」面板都开在输入条**上方**，
        // 三者（键盘、表情、附件）互斥——同屏时输入区会被顶掉半个屏幕（第五轮用户反馈）。
        if (shotMode) {
            // 截图模式底栏（QQ 同款）：预览 / 隐藏昵称 / 完成
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShotTextAction(
                    label = stringResource(R.string.im_shot_preview),
                    enabled = shotRange() != null && !shotBusy,
                    onClick = { generateShot(preview = true) },
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { hideNames = !hideNames }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.im_shot_hide_names),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    SelectDot(checked = hideNames)
                }
                ShotTextAction(
                    label = stringResource(R.string.im_shot_done),
                    enabled = shotRange() != null && !shotBusy,
                    primary = true,
                    onClick = { generateShot(preview = false) },
                )
            }
        } else if (selectMode) {
            // 多选底栏（QQ 同款）：截图 = 进入 QQ 式范围截图（点起止消息选范围后再生成）；删除 = 批量删本机记录
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SelectionAction(
                    icon = LanhangIcons.Crop,
                    label = stringResource(R.string.im_action_capture),
                    enabled = selectedKeys.isNotEmpty(),
                    onClick = {
                        // 第八十九轮：按 QQ 的做法，多选里点「截图」进入截图模式，点起止消息选范围
                        exitSelection()
                        shotStart = null
                        shotEnd = null
                        shotMode = true
                    },
                )
                SelectionAction(
                    icon = LanhangIcons.Trash,
                    label = stringResource(R.string.common_delete),
                    enabled = selectedKeys.isNotEmpty(),
                    onClick = { requestDelete(selectedKeys) },
                )
            }
        } else {
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
                                        // 2026-10-31 第五十六轮（用户：「长按松手后自动闪退」）：
                                        // 整条「按下录音 → 松手发送」链路都跑在手势协程里，任何一处抛异常
                                        // 都会冒到主线程把进程打死。这里逐段兜住：失败只提示，不再闪退。
                                        val started = runCatching { recorder.start() }
                                            .onFailure { android.util.Log.e("ImVoice", "start 抛异常", it) }
                                            .getOrDefault(false)
                                        if (!started) {
                                            say(context.getString(R.string.im_record_failed))
                                            return@detectTapGestures
                                        }
                                        recording = true
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            recording = false
                                        }
                                        val result = runCatching { recorder.stop() }
                                            .onFailure { android.util.Log.e("ImVoice", "stop 抛异常", it) }
                                            .getOrNull()
                                        if (result == null) {
                                            say(context.getString(R.string.im_voice_too_short))
                                        } else {
                                            val ok = runCatching {
                                                ImRepository.sendVoice(
                                                    peerId = peerId,
                                                    uri = Uri.fromFile(result.first),
                                                    durationSec = result.second,
                                                    isGroup = isGroup,
                                                )
                                            }.onFailure {
                                                android.util.Log.e("ImVoice", "sendVoice 抛异常", it)
                                            }.isSuccess
                                            if (!ok) say(context.getString(R.string.im_voice_too_short))
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
                    IconCircle(
                        icon = LanhangIcons.Keyboard,
                        description = stringResource(R.string.im_keyboard),
                        onClick = {
                            voiceMode = false
                            keyboard?.show()
                        },
                    )
                } else if (draft.isBlank()) {
                    IconCircle(
                        icon = MiuixIcons.Mic,
                        description = stringResource(R.string.im_voice),
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
                    IconCircle(
                        icon = MiuixIcons.Send,
                        description = stringResource(R.string.im_send),
                        primary = true,
                        onClick = {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                // 2026-11-15 第八十七轮：断网时消息没入队，草稿必须留着 ——
                                // 否则用户打的一段字点一下就凭空消失（只留下顶上一条连接提示）。
                                if (ImRepository.sendText(peerId, body, isGroup)) {
                                    draft = ""
                                    emojiOpen = false
                                } else {
                                    say(context.getString(R.string.im_send_offline))
                                }
                            }
                        },
                    )
                }
            }
        }
        }
    }
        // 第八十八轮：长按气泡的 QQ 风格菜单浮层（深色圆角卡片 + 图标文字）。
        // 必须是外层 Box 的子级、和 Column 平级：放在 Column 里的话，fillMaxSize 的浮层
        // 会把带 weight(1f) 的消息列表挤成 0 高（模拟器实测踩过这个坑）。
        val activeMenu = menuMessage
        if (activeMenu != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { menuMessage = null },
            ) {
                val bounds = menuBounds
                val topDp = with(LocalDensity.current) {
                    val top = ((bounds?.top ?: 0f) - chatTopPx).toDp()
                    val height = (bounds?.height ?: 0f).toDp()
                    // 气泡上方空间够就弹在上面，不够就挪到气泡下面（菜单高约 96dp）
                    if (top > 160.dp) (top - 108.dp) else (top + height + 8.dp)
                }
                BubbleActionMenu(
                    message = activeMenu,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topDp.coerceAtLeast(8.dp))
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    onCopy = {
                        val text = activeMenu.text.ifBlank { activeMenu.name }
                        val cm = chatContext.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
                        say(chatContext.getString(R.string.im_copied))
                        menuMessage = null
                    },
                    onMulti = {
                        selectedKeys = setOf(activeMenu.key)
                        selectMode = true
                        menuMessage = null
                    },
                    onDelete = {
                        requestDelete(setOf(activeMenu.key))
                        menuMessage = null
                    },
                )
            }
        }
        // 第八十九轮：长图预览浮层（黑底 + 原图 + 点击关闭）
        shotPreviewBmp?.let { bmp ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { shotPreviewBmp = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Text(
                        text = stringResource(R.string.im_shot_tap_close),
                        color = Color.White,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
        // QQ 式底部弹层：发送给好友 / 保存到手机 / 取消
        shotSheetFile?.let { file ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { shotSheetFile = null },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                ) {
                    SheetAction(label = stringResource(R.string.im_shot_send)) {
                        runCatching {
                            chatContext.startActivity(
                                ImShot.shareIntent(chatContext, file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        shotSheetFile = null
                    }
                    SheetAction(label = stringResource(R.string.im_shot_save)) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                    bmp != null && ImShot.saveToGallery(chatContext, bmp, file.nameWithoutExtension)
                                }.getOrDefault(false)
                            }
                            say(chatContext.getString(if (ok) R.string.im_shot_saved else R.string.im_shot_failed))
                            shotSheetFile = null
                        }
                    }
                    SheetAction(label = stringResource(R.string.common_cancel)) { shotSheetFile = null }
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
    /** 多选模式下点气泡应该是「勾选」，不是打开内容（第八十八轮） */
    interactive: Boolean = true,
    /** 长按气泡 → 复制/多选/删除菜单（第八十八轮） */
    onLongPress: (() -> Unit)? = null,
) {
    val mine = message.mine
    // 群聊：发送者优先按消息里的 senderId 查（每个人都不一样）；单聊只有对方一个人
    val senderId = message.senderId.takeIf { it.isNotBlank() } ?: peerId
    UserInfoCache.revision
    val brief = if (mine) null else UserInfoCache.get(senderId)
    // 第五十一轮：StaffBrief 已搬进 :engine（aar）。**跨模块的 val 属性不能被 smart cast**，
    // 所以 `brief?.name?.isNotBlank() == true -> brief.name` 不再能推断出非空，
    // 下游 LanhangAvatar(name=) / Text(text=) 会一起报类型不匹配。
    // 先把可能为空的属性落到局部变量，smart cast 就回到局部作用域里，语义与之前完全一致。
    val briefName = brief?.name
    val name = when {
        briefName != null && briefName.isNotBlank() -> briefName
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
                    .tapWhen(interactive) { onOpenProfile(senderId) },
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
                        .tapWhen(interactive) { onOpenProfile(senderId) },
                )
            }
            val bubbleColor = if (mine) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.surfaceContainerHigh
            // 图片气泡自己带圆角和尺寸，不再套内边距（否则四周会多出一圈色边）
            if (message.kind == ImMessage.KIND_IMAGE) {
                BubbleBody(message = message, mine = mine, onHint = onHint, interactive = interactive, onLongPress = onLongPress)
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(bubbleColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BubbleBody(message = message, mine = mine, onHint = onHint, interactive = interactive, onLongPress = onLongPress)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = when (message.state) {
                    ImMessage.STATE_SENDING -> stringResource(R.string.im_sending)
                    ImMessage.STATE_FAILED -> stringResource(R.string.im_send_failed)
                    else -> messageTime(message.time)
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
                    .tapWhen(interactive) { onOpenSelf() },
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleBody(
    message: ImMessage,
    mine: Boolean,
    onHint: (String) -> Unit = {},
    /** 长按气泡呼出菜单（第八十八轮）。文本气泡自己没有手势节点，用不到，靠外层那一行接住 */
    onLongPress: (() -> Unit)? = null,
    /**
     * 多选模式（第八十八轮）下要把它关掉：否则气泡内部自己的 clickable（预览视频 / 打开文件 /
     * 播地图）会抢走点按，「点气泡勾选」就没法用了 —— Compose 里子的手势总是赢过父容器。
     */
    interactive: Boolean = true,
) {
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
                .longPressable(interactive, onLongPress) {
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
                .longPressable(interactive, onLongPress) {
                    // 2026-11-14 第七十四轮（用户：「私信中发送视频或图片时，应该添加一个
                    // 预览功能直接预览发送的图片与视频」）：原来直接 ACTION_VIEW 丢给系统
                    // 播放器，会跳出 App 且没有转场。改成和图片一样的全屏预览（黑底 +
                    // 下拉关闭 + 缩放淡入），播不了时预览页自己再兜底给系统播放器。
                    if (message.uri.isNotBlank()) {
                        runCatching {
                            com.lanhang.miuix.moments.VideoPreviewActivity.start(context, message.uri)
                        }.onFailure { onHint("") }
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
            modifier = Modifier.longPressable(interactive, onLongPress) {
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
                modifier = Modifier.longPressable(interactive, onLongPress) {
                    if (message.uri.isNotBlank()) ImMedia.Player.toggle(context, message.key, message.uri)
                },
            ) {
                Text(text = if (playing) "■" else "▶", color = textColor, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                // 时长越长条越宽（微信那种），60 秒封顶。
                //
                // 2026-11-14 第七十四轮（用户：「播放语音时进度条没有随播放进度而变化」）：
                // 原来这里是一条静态灰条，播起来毫无反馈。现在叠一条按真实播放位置增长的
                // 前景条，进度来自 ImMedia.Player.progress（0..1），由播放器每 80ms 刷新。
                val barWidth = (28 + message.duration.coerceIn(1, 60) * 2.4f).dp
                val barFrac = if (playing) ImMedia.Player.progress else 0f
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(textColor.copy(alpha = 0.30f)),
                ) {
                    if (playing && barFrac > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(barFrac.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(textColor),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${message.duration}″",
                    style = MiuixTheme.textStyles.body1,
                    color = textColor,
                )
            }
        }

        ImMessage.KIND_LOCATION -> Column(
            modifier = Modifier.longPressable(interactive, onLongPress) {
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

/**
 * 输入条右侧的圆形图标按钮：只有图标，没有任何文字和容器底色。
 *
 * 2026-11-15 第八十六轮（用户：「语音按钮不要加阴影，与左侧的按钮设计不统一，只保留图标就好，
 * 不需要『语音』的文字；键盘同理」）：原来是 `IconPill`——38dp 胶囊 + `surfaceContainerHigh`
 * 容器底色 + 「语音 / 键盘 / 发送」文字，看起来像浮起来的一块，跟左侧「+ / 表情」那两个
 * 透明圆形图标完全不是一套东西。现在统一成同一套 38dp 圆形：
 * - 普通态（语音 / 键盘）：透明底 + `onSurfaceVariantSummary` 图标，和 `+`、表情完全一致；
 * - 主操作（发送）：主色实心圆 + 白色图标，是全条唯一的强调色。
 */
@Composable
private fun IconCircle(
    icon: ImageVector,
    description: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (primary) MiuixTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (primary) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(22.dp),
        )
    }
}
/**
 * 「+」附件面板：图片 / 视频 / 文件 / 位置四项（语音走输入条右侧的麦克风）。
 *
 * 2026-11-14 第七十三轮：用户反馈「+ 号拓展页面图片、视频、文件、位置缺少图标」。
 * 原来这里用 `label.take(1)` 把中文首字（图/视/文/位）当图标，看着像文字不像按钮，
 * 现在换成 Miuix extended 图标库里的标准图形。
 */
@Composable
private fun AttachPanel(
    onImage: () -> Unit,
    onVideo: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
) {
    val entries: List<Triple<ImageVector, String, () -> Unit>> = listOf(
        Triple(MiuixIcons.Image, stringResource(R.string.im_attach_image), onImage),
        Triple(MiuixIcons.Play, stringResource(R.string.im_attach_video), onVideo),
        Triple(MiuixIcons.File, stringResource(R.string.im_attach_file), onFile),
        Triple(MiuixIcons.Location, stringResource(R.string.im_attach_location), onLocation),
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
            for ((icon, label, action) in entries) {
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
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
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

/**
 * 气泡下方的发送时间。
 *
 * 2026-11-14 第七十四轮（用户：「私信发送的消息需要显示日期-时间，而非只显示发送时间」）：
 * 原来这里只给 `HH:mm`，翻几屏历史消息就分不清是哪天发的。现在一律带上日期：
 * 当天仍是 `HH:mm`（不啰嗦），昨天写「昨天 HH:mm」，今年写 `MM-dd HH:mm`，
 * 跨年写 `yyyy-MM-dd HH:mm`。日期部分用 24 小时制，避免与「下午」的时分混淆。
 */
private fun messageTime(millis: Long): String {
    if (millis <= 0) return ""
    // 2026-11-14 第七十四轮修正（用户：「私信发送的消息需要显示日期-时间，而非只显示
    // 发送时间」）。第一版保留了「当天只给 HH:mm」的聊天软件惯例，实测下来用户看到的
    // 仍然是一串光秃秃的 05:40，正是他要改掉的东西 —— 所以这里不再分「当天/昨天」，
    // **每条都带日期**；跨年再补年份，免得去年的 12-25 和今年的 12-25 混在一起。
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
        "MM-dd HH:mm"
    } else {
        "yyyy-MM-dd HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

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


// ------------------------------------------------------------------ 第八十八轮：长按菜单 / 多选

/**
 * QQ 风格的长按菜单：深色圆角卡片 + 一排「图标在上、文字在下」的操作项。
 * 只放用户要的三项：复制 / 多选 / 删除（不做转发、撤回、提醒那一整套）。
 */
@Composable
private fun BubbleActionMenu(
    message: ImMessage,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onMulti: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF03A3A3A))
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 文本消息才有「复制」；文件/语音/视频复制一段「[视频]」没有意义
            if (message.kind == ImMessage.KIND_TEXT) {
                MenuActionItem(icon = LanhangIcons.Copy, label = stringResource(R.string.im_menu_copy), onClick = onCopy)
            }
            MenuActionItem(icon = LanhangIcons.CheckCircle, label = stringResource(R.string.im_menu_multi), onClick = onMulti)
            MenuActionItem(icon = LanhangIcons.Trash, label = stringResource(R.string.common_delete), onClick = onDelete)
        }
    }
}

@Composable
private fun MenuActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = label, color = Color.White, style = MiuixTheme.textStyles.footnote1)
    }
}

/** 多选圆圈：未选 = 空心灰圈，已选 = 主色实心 + 白色对勾（QQ 左侧那一排） */
@Composable
private fun SelectDot(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (checked) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text(text = "✓", color = Color.White, fontSize = 13.sp)
    }
}

/** 截图模式底栏的粗体文字按钮（QQ 同款，无图标） */
@Composable
private fun ShotTextAction(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
        primary -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurface
    }
    Text(
        text = label,
        style = MiuixTheme.textStyles.body1,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** QQ 式底部弹层的一行（发送给好友 / 保存到手机 / 取消） */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.body1,
        color = MiuixTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    )
}

/** 多选底栏的一个操作（截图 / 删除），未选中任何消息时置灰且不可点 */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) MiuixTheme.colorScheme.onSurface
    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(text = label, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}


/**
 * 多选模式（第八十八轮）下**不安装** clickable —— 注意不能写成 `clickable(enabled = false)`：
 * disabled 的 clickable 仍然会装一个手势节点并把点按吃掉，父层（列表项的 combinedClickable）
 * 就永远收不到「点一下 = 勾选」这个事件（模拟器实测：视频气泡怎么点都勾不上）。
 * 这里直接不加修饰符，父层自然能接住。
 */
private fun Modifier.tapWhen(interactive: Boolean, onTap: () -> Unit): Modifier =
    if (interactive) clickable(onClick = onTap) else this


/**
 * [tapWhen] 的升级版：有长按回调时挂 `combinedClickable`（点按 = [onTap]，长按 = [onLongClick]），
 * 没有就退回普通 clickable，多选态什么都不挂。
 *
 * 为什么媒体气泡要自己带长按：外层列表项虽然有 combinedClickable，但子层（视频/文件/图片）的
 * clickable 会先把手势吃掉，长按落到子层就变成「松手打开预览」，菜单永远弹不出来（实测踩坑）。
 */
private fun Modifier.longPressable(
    interactive: Boolean,
    onLongClick: (() -> Unit)?,
    onTap: () -> Unit,
): Modifier = when {
    !interactive -> this
    onLongClick != null -> combinedClickable(onClick = onTap, onLongClick = onLongClick)
    else -> clickable(onClick = onTap)
}
