package com.lanhang.miuix.im

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lanhang.miuix.R
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import io.rong.imlib.IRongCallback
import io.rong.imlib.RongIMClient
import io.rong.imlib.model.Conversation
import io.rong.imlib.model.Group
import io.rong.imlib.model.Message
import io.rong.message.FileMessage
import io.rong.message.ImageMessage
import io.rong.message.LocationMessage
import io.rong.message.SightMessage
import io.rong.message.TextMessage
import io.rong.message.VoiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 一条会话。2026-10-26 起支持群聊（[isGroup]）：
 * 单聊的 [peerId] 是对方 userId，群聊的是群 id，[title] 是会话标题（群名 / 对方姓名）。
 */
data class ImConversation(
    val peerId: String,
    val title: String,
    val isGroup: Boolean,
    val preview: String,
    val time: Long,
    val unread: Int,
    /** 群聊最后一条消息是谁发的（拿它去 staffs/batch 换姓名，列表预览才不是一串 id） */
    val previewSenderId: String = "",
)

/**
 * 聊天页里的一条消息。
 *
 * 2026-10-26 第六轮：从「只有文本」扩成文本 / 图片 / 视频 / 文件 / 语音 / 位置六种，
 * [kind] 决定气泡长什么样，[uri] / [thumb] / [duration] / [size] / [name] / [lat] / [lng] / [poi]
 * 是各类型自己的载荷。**没有自造协议**——六种全部映射到 IMLib 自带的消息类型，
 * 官方客户端收到后显示的和我们这头一致。
 */
data class ImMessage(
    val key: String,
    val text: String,
    val mine: Boolean,
    val time: Long,
    val state: Int = STATE_SENT,
    /** 发送者 id（群聊里用来显示「谁说的」；单聊用不到） */
    val senderId: String = "",
    val kind: Int = KIND_TEXT,
    /** 图片 / 视频源 / 文件的地址（本地 file:// 或远端 https://） */
    val uri: String = "",
    /** 视频封面、图片缩略图 */
    val thumb: String = "",
    /** 语音、视频时长（秒） */
    val duration: Int = 0,
    /** 文件字节数 */
    val size: Long = 0L,
    /** 文件名 */
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val poi: String = "",
) {
    companion object {
        const val STATE_SENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2

        const val KIND_TEXT = 0
        const val KIND_IMAGE = 1
        const val KIND_VIDEO = 2
        const val KIND_FILE = 3
        const val KIND_VOICE = 4
        const val KIND_LOCATION = 5

        internal fun of(message: Message): ImMessage? {
            val sender = message.senderUserId.orEmpty()
            val mine = message.messageDirection == Message.MessageDirection.SEND
            val time = if (message.sentTime > 0) message.sentTime else message.receivedTime
            val key = message.uId ?: message.messageId.toString()
            fun plain(text: String, kind: Int) =
                ImMessage(key = key, text = text, mine = mine, time = time, senderId = sender, kind = kind)

            return when (val content = message.content) {
                is TextMessage -> plain(content.content.orEmpty(), KIND_TEXT)
                is ImageMessage -> plain("[图片]", KIND_IMAGE).copy(
                    uri = (content.remoteUri ?: content.localUri)?.toString().orEmpty(),
                    thumb = (content.thumUri ?: content.remoteUri ?: content.localUri)?.toString().orEmpty(),
                )
                is SightMessage -> plain("[视频]", KIND_VIDEO).copy(
                    uri = (content.mediaUrl ?: content.localPath)?.toString().orEmpty(),
                    thumb = content.thumbUri?.toString().orEmpty(),
                    duration = content.duration,
                    size = content.size,
                )
                is FileMessage -> plain("[文件]", KIND_FILE).copy(
                    uri = content.fileUrl?.toString().orEmpty(),
                    name = content.name.orEmpty().ifBlank { "文件" },
                    size = content.size,
                )
                is VoiceMessage -> plain("[语音]", KIND_VOICE).copy(
                    uri = content.uri?.toString().orEmpty(),
                    duration = content.duration,
                )
                is LocationMessage -> plain("[位置]", KIND_LOCATION).copy(
                    lat = content.lat,
                    lng = content.lng,
                    poi = content.poi.orEmpty(),
                )
                // 群通知（「XX 加入了群聊」这类系统提示）
                is io.rong.message.InformationNotificationMessage ->
                    plain(content.message.orEmpty().ifBlank { "[通知]" }, KIND_TEXT)
                is io.rong.message.HQVoiceMessage -> plain("[语音]", KIND_VOICE).copy(
                    uri = content.fileUrl?.toString().orEmpty(),
                    duration = content.duration,
                )
                // 第十二轮（用户反馈「收到『并不支持的信息』通知 / 群聊里也有 / 幽灵私信」）：
                // 学校服务端会下发官方插件自定义的类型（通话记录、通知类等），SDK 手里就是 UnknownMessage。
                // 以前把它画成「[暂不支持的消息]」并弹通知 —— 每来一条就凭空多一条私信。
                // 现在**直接丢弃**：不建气泡、不发通知、顺手清未读，类名只记 logcat。
                is io.rong.imlib.model.UnknownMessage -> {
                    android.util.Log.d("LanhangIM", "drop unsupported: " + message.objectName)
                    null
                }
                else -> {
                    android.util.Log.d("LanhangIM", "drop unknown content: " + content?.javaClass?.name)
                    null
                }
            }
        }
    }
}

/**
 * 融云 IM（私信）的唯一入口。
 *
 * 走的还是学校那套服务，和官方客户端一致：
 * 1. `GET /configuration/all` → `features.im.app_key / navi_urls / media_urls`
 *    （官方 `FeatureConfigManager.getRongIMInfo`，IMTask 里再 `RongIM.setServerInfo`）
 * 2. `POST /user/refresh_token` → IM token（官方 `AuthTask.refreshToken`）
 * 3. `RongIMClient.connect(token)`，连上以后会话 / 消息都由 SDK 本地库给出
 *
 * 只做单聊文本消息：会话列表、历史消息、发文本、收消息。
 * 界面完全自绘（Miuix），**不引入融云 IMKit 的那套 Activity**。
 */
object ImRepository {

    enum class Status { IDLE, CONNECTING, CONNECTED, FAILED }

    private const val TAG = "LanhangIM"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 连接状态（UI 直接读） */
    var status by mutableStateOf(Status.IDLE)
        private set

    /** 最近一次失败原因（给 UI 显示，成功时清空） */
    var error by mutableStateOf<String?>(null)
        private set

    var conversations by mutableStateOf<List<ImConversation>>(emptyList())
        private set

    var loadingConversations by mutableStateOf(false)
        private set

    private val threads = mutableStateMapOf<String, List<ImMessage>>()

    /** 群 id → 群名（本机记住；SDK 的 conversationTitle 拿不到时兜底） */
    private val groupTitles = mutableStateMapOf<String, String>()

    /** 见过的群 id（会话列表里出现过的），用来判断某个 peerId 是不是群——群名还没拉到时也要答得出 */
    private val groupIds = mutableStateMapOf<String, Boolean>()

    /** 正在拉群名的群 id，避免同一轮刷新里重复打接口 */
    private val groupLookups = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 一次性的动作结果（加入群聊成功/失败），UI 显示一次后清空 */
    var notice by mutableStateOf<String?>(null)
        private set

    private var sdkReady = false
    private var listenersReady = false
    private var connectStarted = false

    /** 会话列表 / 聊天页都从这里取（Compose 里读它是可观察的） */
    // ---------------------------------------------------------------- 本地快照（2026-10-26 第七轮）

    /** 缓存只铺一次；连接成功后各条数据会自然覆盖掉它 */
    private var cachePrimed = false

    private fun primeCache() {
        if (cachePrimed) return
        cachePrimed = true
        scope.launch {
            val convs = runCatching { ImCache.loadConversations() }.getOrDefault(emptyList())
            val titles = runCatching { ImCache.loadGroupTitles() }.getOrDefault(emptyMap())
            val msgs = runCatching { ImCache.loadAllMessages() }.getOrDefault(emptyMap())
            withContext(Dispatchers.Main) {
                // 已有数据（比如别处刚刷新过）就不覆盖，缓存只用来「填空」
                if (conversations.isEmpty() && convs.isNotEmpty()) conversations = convs
                titles.forEach { (id, name) ->
                    if (id.isNotBlank() && name.isNotBlank() && groupTitles[id].isNullOrBlank()) {
                        groupTitles[id] = name
                    }
                }
                msgs.forEach { (peer, list) ->
                    if (list.isNotEmpty() && threads[peer].isNullOrEmpty()) threads[peer] = list
                }
                android.util.Log.i(TAG, "cache primed: conv=" + convs.size + " threads=" + msgs.size)
            }
        }
    }

    /** 某条会话的消息变了就落盘（发送、标记状态、拉到历史都会走这里） */
    private fun persistThread(peerId: String) {
        val snapshot = threads[peerId] ?: return
        if (snapshot.isEmpty()) return
        scope.launch { runCatching { ImCache.saveMessages(peerId, snapshot) } }
    }

    private fun persistGroupTitles() {
        val snapshot = groupTitles.toMap()
        if (snapshot.isEmpty()) return
        scope.launch { runCatching { ImCache.saveGroupTitles(snapshot) } }
    }

    fun thread(peerId: String): List<ImMessage> = threads[peerId].orEmpty()

    /** 某个 id 是不是群会话（聊天页据此决定要不要显示发送者名字） */
    fun isGroup(peerId: String): Boolean =
        conversations.firstOrNull { it.peerId == peerId }?.isGroup == true ||
            groupIds.containsKey(peerId)

    val unreadTotal: Int get() = conversations.sumOf { it.unread }

    /**
     * 进入私信页时调一次：初始化 SDK（要 appKey，来自学校下发配置）并建立连接。
     * 已经连上就只刷新一下会话列表。
     */
    fun ensureStarted() {
        // 先把上次的快照铺上（会话列表 / 各家最近 60 条 / 群名），再慢慢连融云。
        // 用户 2026-10-26 第七轮要求：「如果刷新速度跟不上触发速度，先加载缓存的聊天记录，
        // 等到连接成功后再刷新」——所以这里是**同步 entry 的第一步**，不等网络。
        primeCache()
        when (status) {
            Status.CONNECTED -> {
                refreshConversations()
                return
            }
            Status.CONNECTING -> return
            else -> Unit
        }
        if (connectStarted && status == Status.CONNECTING) return
        connectStarted = true
        status = Status.CONNECTING
        error = null
        scope.launch { doConnect(allowTokenRefresh = true) }
    }

    private fun doConnect(allowTokenRefresh: Boolean) {
        // 1) appKey：学校下发的配置里带
        val im = runCatching { RceApi.configurationAll().features?.im }.getOrNull()
        val appKey = im?.appKey?.takeIf { it.isNotBlank() }
        if (appKey == null) {
            status = Status.FAILED
            error = "学校未下发 IM 配置（app_key 为空）"
            return
        }
        val navi = im.naviUrls?.firstOrNull { it.isNotBlank() }
        if (!sdkReady) {
            runCatching {
                val media = im.mediaUrls?.firstOrNull { it.isNotBlank() }
                // 私有云：setServerInfo 必须**在 init 之前**（2.9 文档口径），
                // 否则 SDK 会拿默认的 nav.cn.ronghub.com 去连（本机 DNS 直接不通）
                if (navi != null) RongIMClient.setServerInfo(navi, media.orEmpty())
                RongIMClient.init(AppContext.instance, appKey)
                android.util.Log.i("LanhangIM", "init appKey=" + appKey + " navi=" + navi + " media=" + media)
            }.onFailure {
                status = Status.FAILED
                error = "IM 初始化失败：${it.message}"
                return
            }
            sdkReady = true
        }
        installListeners()

        // 2) token：官方的 IMTask 直接拿登录时下发的 token 连（CacheTask.getToken()），
        //    只有连不上才去 POST /user/refresh_token 换新的
        val token = Session.imToken.takeIf { it.isNotBlank() }
            ?: Session.token.takeIf { it.isNotBlank() }
            ?: fetchToken()
            ?: run {
                status = Status.FAILED
                error = "拿不到 IM token"
                return
            }
        android.util.Log.i(TAG, "connect tokenLen=" + token.length)
        connectWith(token, allowTokenRefresh)
    }

    private fun fetchToken(): String? = runCatching {
        RceApi.refreshImToken()?.takeIf { it.isNotBlank() }?.also { Session.imToken = it }
    }.getOrNull()

    private fun connectWith(token: String, allowTokenRefresh: Boolean) {
        RongIMClient.connect(token, object : RongIMClient.ConnectCallback() {
            override fun onSuccess(userId: String?) {
                android.util.Log.i(TAG, "connect ok userId=" + userId)
                status = Status.CONNECTED
                error = null
                // 通知渠道先建好（没有授权时 showMessage 会自己跳过，不弹也不崩）
                ImNotify.ensureChannel(AppContext.instance)
                refreshConversations()
            }

            override fun onError(e: RongIMClient.ErrorCode?) {
                android.util.Log.w(TAG, "connect error " + e + " tokenLen=" + token.length)
                status = Status.FAILED
                error = "IM 连接失败：" + (e?.name ?: "未知错误")
            }

            /** 2.9 的 token 失效回调（5.x 合并成了 ErrorCode.RC_CONN_TOKEN_INCORRECT） */
            override fun onTokenIncorrect() {
                android.util.Log.w(TAG, "connect onTokenIncorrect tokenLen=" + token.length)
                if (allowTokenRefresh) {
                    Session.imToken = ""
                    val fresh = fetchToken()
                    if (fresh != null) {
                        connectWith(fresh, allowTokenRefresh = false)
                        return
                    }
                }
                status = Status.FAILED
                error = "IM token 无效（学校下发的 token 不被融云认可）"
            }
        })
    }

    private fun installListeners() {
        if (listenersReady) return
        listenersReady = true
        runCatching {
            RongIMClient.setOnReceiveMessageListener { msg, _ ->
                run {
                    val peer = when (msg.conversationType) {
                        Conversation.ConversationType.PRIVATE -> msg.senderUserId.orEmpty()
                        // 群聊：会话的 targetId 就是群 id
                        Conversation.ConversationType.GROUP -> msg.targetId.orEmpty()
                        else -> ""
                    }
                    if (peer.isNotBlank()) {
                        ImMessage.of(msg)?.let { mapped ->
                            val current = threads[peer].orEmpty()
                            if (current.none { it.key == mapped.key }) {
                                threads[peer] = current + mapped
                            }
                            // 群聊里别人说话：把发送者名字补进缓存，气泡才能显示是谁
                            if (msg.conversationType == Conversation.ConversationType.GROUP &&
                                mapped.senderId.isNotBlank()
                            ) {
                                scope.launch {
                                    runCatching { UserInfoCache.resolve(listOf(mapped.senderId)) }
                                }
                            }
                            // 别人的消息 → 系统通知（普通通知，口径见 ImNotify 的 KDoc）
                            if (!mapped.mine) notifyIncoming(peer, mapped)
                        } ?: run {
                            // 解不出来的消息（官方插件的自定义类型）：不建会话、不弹通知，
                            // 顺手把这条会话的未读清掉，别让「幽灵私信」把徽标顶起来
                            dropGhost(msg.conversationType, peer)
                        }
                    }
                    refreshConversations()
                }
                true
            }
            RongIMClient.setConnectionStatusListener { s ->
                status = when (s) {
                    RongIMClient.ConnectionStatusListener.ConnectionStatus.CONNECTED ->
                        Status.CONNECTED
                    RongIMClient.ConnectionStatusListener.ConnectionStatus.CONNECTING ->
                        Status.CONNECTING
                    else -> status
                }
                if (status == Status.CONNECTED) refreshConversations()
            }
        }
    }

    /**
     * 拉会话列表（单聊 + 群聊，按最后一条消息时间倒序）。
     *
     * 2026-10-26 起把 [Conversation.ConversationType.GROUP] 也带上：用户要求「IM 里也要有群聊」。
     * 群名优先用 SDK 给的 `conversationTitle`（服务端/推送下发），拿不到就用本机记住的、再不行给「群聊 · 后四位」。
     */
    fun refreshConversations() {
        if (status != Status.CONNECTED) return
        loadingConversations = true
        RongIMClient.getInstance().getConversationList(
            object : RongIMClient.ResultCallback<List<Conversation>>() {
                override fun onSuccess(list: List<Conversation>?) {
                    val mapped = list.orEmpty()
                        .filter {
                            it.conversationType == Conversation.ConversationType.PRIVATE ||
                                it.conversationType == Conversation.ConversationType.GROUP
                        }
                        .map { c ->
                            val isGroup = c.conversationType == Conversation.ConversationType.GROUP
                            val target = c.targetId.orEmpty()
                            if (isGroup && target.isNotBlank()) groupIds[target] = true
                            ImConversation(
                                peerId = target,
                                title = if (isGroup) groupTitleOf(target, c.conversationTitle) else "",
                                isGroup = isGroup,
                                preview = previewOf(c, isGroup),
                                time = maxOf(c.sentTime, c.receivedTime),
                                unread = c.unreadMessageCount,
                                previewSenderId = if (isGroup) c.senderUserId.orEmpty() else "",
                            )
                        }
                        .filter { it.peerId.isNotBlank() }
                        .sortedByDescending { it.time }
                    // 第十二轮「幽灵私信」：最后一条是不可解析消息、正文拿不出来的**单聊**整条丢掉，
                    // 并把它的未读清掉（否则徽标一直挂着，下次拉取又回来）。
                    // 第十五轮（用户反馈「私信栏里没有群聊了」）：**群聊绝不能按幽灵处理**——
                    // 群里常见未知/插件类消息（预览为空），刚加入的群更是 latestMessage 为 null、
                    // 预览也为空；以前它们被一起丢掉，于是群会话从列表里彻底消失。现在群聊一律保留，
                    // 正文为空时给一句占位（见 previewText）。
                    val (ghosts, real) = mapped.partition { it.preview.isBlank() && !it.isGroup }
                    ghosts.forEach { g -> dropGhost(Conversation.ConversationType.PRIVATE, g.peerId) }
                    conversations = real
                    loadingConversations = false
                    scope.launch { runCatching { ImCache.saveConversations(real) } }
                    // 单聊的名字/头像 + 群聊里「最后说话的人」都走工作圈那套缓存
                    scope.launch {
                        runCatching {
                            val ids = real.filter { !it.isGroup }.map { it.peerId } +
                                real.mapNotNull { it.previewSenderId.takeIf { s -> s.isNotBlank() } }
                            if (ids.isNotEmpty()) UserInfoCache.resolve(ids.distinct())
                        }
                    }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    loadingConversations = false
                    error = "会话列表加载失败：${e?.name ?: "未知错误"}"
                }
            },
            Conversation.ConversationType.PRIVATE,
            Conversation.ConversationType.GROUP
        )
    }

    /**
     * 群名的唯一入口（2026-10-26 第四轮修「群名不对」）。
     *
     * 取值优先级：
     * 1. 已经拿到的真名（[groupTitles]，来自学校 `GET /groups/{id}` 或 SDK 下发的真名）；
     * 2. SDK 的 `conversationTitle`——**只有不是占位格式时才采信**：老版本 `joinGroup` 会把
     *    「群聊 · xxxx」这种占位名 `syncGroup` 进本地库，SDK 之后就一直吐这个假名字，
     *    这正是用户看到「原来是 2601063… 变成了 mlia」的原因；
     * 3. 都拿不到就先发一次网络请求补真名（异步），本次先用占位「群聊 · 后四位」顶着。
     */
    private fun groupTitleOf(groupId: String, fromSdk: String?): String {
        val cached = groupTitles[groupId]?.takeIf { it.isNotBlank() }
        if (cached != null) return cached

        val sdk = fromSdk?.trim()
        if (!sdk.isNullOrEmpty() && sdk != groupId && !sdk.startsWith(GROUP_TITLE_PLACEHOLDER)) {
            groupTitles[groupId] = sdk
            return sdk
        }
        ensureGroupName(groupId)
        return placeholderGroupTitle(groupId)
    }

    /** 占位群名：只在真名还没拿到时顶一下，不能写回 SDK/服务端 */
    private fun placeholderGroupTitle(groupId: String): String =
        if (groupId.length > 4) {
            GROUP_TITLE_PLACEHOLDER + groupId.takeLast(4)
        } else {
            GROUP_TITLE_PLACEHOLDER + groupId
        }

    /**
     * 异步补一次群真名（学校 `GET /groups/{id}` → `result.name`）。
     *
     * 拿到真名后做两件事：写进 [groupTitles]（界面立刻用上），并用 `syncGroup` 把 SDK 本地库里
     * 可能已被占位名污染的群名覆盖回真名——否则退出重进、或者别处读 `conversationTitle` 时
     * 又会看到假名字。失败则清掉去重标记，下次刷新再试。
     */
    private fun ensureGroupName(groupId: String) {
        if (groupId.isBlank()) return
        if (groupTitles[groupId]?.isNotBlank() == true) return
        if (!groupLookups.add(groupId)) return
        scope.launch {
            try {
                val info = runCatching { RceApi.groupInfo(groupId) }.getOrNull()
                val name = info?.name?.trim().orEmpty()
                if (name.isNotBlank()) {
                    groupTitles[groupId] = name
                    persistGroupTitles()
                    runCatching {
                        RongIMClient.getInstance().syncGroup(
                            listOf(Group(groupId, name, null)),
                            object : RongIMClient.OperationCallback() {
                                override fun onSuccess() = refreshConversations()
                                override fun onError(e: RongIMClient.ErrorCode?) = refreshConversations()
                            }
                        )
                    }
                    refreshConversations()
                }
            } finally {
                groupLookups.remove(groupId)
            }
        }
    }

    /** 群名占位符前缀（历史遗留的假名字都长这样，见到就当没名字处理） */
    private const val GROUP_TITLE_PLACEHOLDER = "群聊 · "

    /** 最后一条消息的正文（群聊「谁说的」由界面拼，见 ConversationRow，避免这里查不到名字时写死一串 id） */
    /** 会话列表的最后一条预览（2026-10-26 第六轮：图片/视频/文件/语音/位置也要显示成人话） */
    private fun previewOf(c: Conversation, isGroup: Boolean): String {
        val content = c.latestMessage ?: return ""
        return when (content) {
            is TextMessage -> content.content.orEmpty()
            is ImageMessage -> "[图片]"
            is SightMessage -> "[视频]"
            is FileMessage -> "[文件] " + content.name.orEmpty().ifBlank { "文件" }
            is VoiceMessage -> "[语音] ${content.duration}″"
            is LocationMessage -> "[位置] " + content.poi.orEmpty().ifBlank { "位置" }
            is io.rong.message.InformationNotificationMessage -> content.message.orEmpty()
            // 解不出来的类型不参与预览（口径见 ImMessage.of）；空串 = 这条会话按幽灵处理，整条丢掉
            else -> ""
        }
    }

    /**
     * 丢掉「幽灵会话」的未读：不可解析的消息不进列表、不弹通知，但融云那边仍记着未读，
     * 不清掉的话私信 Tab 会一直挂着红点、下次拉取还把这条会话带回来（第十二轮）。
     */
    private fun dropGhost(type: Conversation.ConversationType, targetId: String) {
        if (targetId.isBlank()) return
        runCatching {
            RongIMClient.getInstance().clearMessagesUnreadStatus(
                type,
                targetId,
                object : RongIMClient.ResultCallback<Boolean>() {
                    override fun onSuccess(t: Boolean?) {}
                    override fun onError(e: RongIMClient.ErrorCode?) {}
                },
            )
        }
    }

    /**
     * 别人的新消息 → 系统通知（2026-10-26 第二轮：放弃 MiPush，改用普通通知）。
     *
     * 不弹的三种情况：自己发的（调用方已过滤）、App 在前台且用户正停在这个会话里
     * （边聊边响很烦）、没有通知权限（Android 13+ 未授权时 [ImNotify] 自己会跳过）。
     * 注意口径：这是**本地通知**，进程被系统杀掉之后收不到——那需要厂商推送通道。
     */
    private fun notifyIncoming(peerId: String, message: ImMessage) {
        val context = AppContext.instance
        if (ImNotify.appForeground && ImNotify.activePeerId == peerId) return
        val text = message.text.ifBlank { context.getString(R.string.im_other_message) }
        if (isGroup(peerId)) {
            val sender = UserInfoCache.get(message.senderId)?.name?.takeIf { it.isNotBlank() }
                ?: when {
                    message.senderId.length > 4 ->
                        context.getString(R.string.im_member_short, message.senderId.takeLast(4))
                    else -> context.getString(R.string.im_notify_member)
                }
            ImNotify.showMessage(
                context = context,
                peerId = peerId,
                title = groupTitleOf(peerId, null),
                body = context.getString(R.string.im_group_sender, sender, text),
            )
        } else {
            val name = UserInfoCache.get(peerId)?.name?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.im_notify_new_message)
            ImNotify.showMessage(context, peerId, name, text)
        }
    }

    /** 打开聊天页：拉最近 50 条，并把这条会话标成已读 */
    fun loadMessages(peerId: String, isGroup: Boolean = false) {
        if (peerId.isBlank()) return
        if (status != Status.CONNECTED) return
        val type = if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE
        RongIMClient.getInstance().getLatestMessages(
            type,
            peerId,
            50,
            object : RongIMClient.ResultCallback<List<Message>>() {
                override fun onSuccess(list: List<Message>?) {
                    val mapped = list.orEmpty()
                        .mapNotNull { ImMessage.of(it) }
                        .sortedBy { it.time }
                    // 还在「发送中」的本地消息不能被覆盖掉
                    val pending = threads[peerId].orEmpty().filter {
                        it.state == ImMessage.STATE_SENDING || it.state == ImMessage.STATE_FAILED
                    }
                    threads[peerId] = mapped + pending.filter { p -> mapped.none { it.key == p.key } }
                    persistThread(peerId)
                    clearUnread(peerId, isGroup)
                    if (isGroup) {
                        // 群聊要显示「谁说的」：把没缓存过的发送者补进来
                        val senders = mapped.filter { !it.mine }.map { it.senderId }
                            .filter { it.isNotBlank() }.distinct()
                        scope.launch { runCatching { UserInfoCache.resolve(senders) } }
                    }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    error = "消息加载失败：${e?.name ?: "未知错误"}"
                }
            }
        )
    }

    private fun clearUnread(peerId: String, isGroup: Boolean = false) {
        RongIMClient.getInstance().clearMessagesUnreadStatus(
            if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE,
            peerId,
            object : RongIMClient.ResultCallback<Boolean>() {
                override fun onSuccess(t: Boolean?) = refreshConversations()
                override fun onError(e: RongIMClient.ErrorCode?) = Unit
            }
        )
    }

    /**
     * 加入一个已有的群聊。
     *
     * ⚠️ IMLib 2.9 的客户端 API **没有** `createGroup`（建群是融云服务端 / Server API 的活，
     * 需要学校融云账号的 app secret），所以这里只做「加入已知群 id」：
     * `joinGroup(groupId, groupName)` → `syncGroup` 把群名写进本地库 → 刷会话列表。
     *
     * 群名的坑（2026-10-26 第四轮）：`joinGroup` 的第二个参数会**被 SDK 当成群名落进本地库**，
     * 老版本在用户没填群名时传的是占位「群聊 · 后四位」，于是真名被覆盖，之后任何地方读
     * `conversationTitle` 都只能拿到这串假名字。现在改为：先问学校 `GET /groups/{id}` 拿真名，
     * 拿不到才用用户自己填的名字，两者都没有就**不传占位符**（传空，交给服务端/后续请求补）。
     */
    fun joinGroup(groupId: String, groupName: String) {
        val id = groupId.trim()
        if (id.isEmpty()) return
        if (status != Status.CONNECTED) {
            notice = "IM 未连接，暂时不能加入群聊"
            return
        }
        scope.launch {
            val serverName = runCatching { RceApi.groupInfo(id) }.getOrNull()?.name?.trim().orEmpty()
            val name = serverName.ifBlank { groupName.trim() }
            joinGroupNow(id, name)
        }
    }

    private fun joinGroupNow(id: String, name: String) {
        RongIMClient.getInstance().joinGroup(
            id,
            name,
            object : RongIMClient.OperationCallback() {
                override fun onSuccess() {
                    groupIds[id] = true
                    if (name.isNotBlank()) groupTitles[id] = name
                    runCatching {
                        RongIMClient.getInstance().syncGroup(
                            listOf(Group(id, name, null)),
                            object : RongIMClient.OperationCallback() {
                                override fun onSuccess() = refreshConversations()
                                override fun onError(e: RongIMClient.ErrorCode?) = refreshConversations()
                            }
                        )
                    }
                    notice = if (name.isNotBlank()) {
                        "已加入群聊「$name」"
                    } else {
                        "已加入群聊"
                    }
                    refreshConversations()
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    notice = "加入群聊失败：${e?.name ?: "未知错误"}"
                    android.util.Log.w(TAG, "joinGroup error " + e + " id=" + id)
                }
            }
        )
    }

    fun clearNotice() {
        notice = null
    }

    /** 发一条文本（乐观上屏，失败标红） */
    fun sendText(peerId: String, text: String, isGroup: Boolean = false) {
        val body = text.trim()
        if (body.isEmpty() || peerId.isBlank()) return
        if (status != Status.CONNECTED) {
            error = "IM 未连接，消息没有发出去"
            return
        }
        val message = Message.obtain(
            peerId,
            if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE,
            TextMessage.obtain(body)
        )
        val key = message.uId ?: UUID.randomUUID().toString()
        threads[peerId] = threads[peerId].orEmpty() + ImMessage(
            key = key,
            text = body,
            mine = true,
            time = System.currentTimeMillis(),
            state = ImMessage.STATE_SENDING,
            senderId = Session.userId,
        )
        persistThread(peerId)
        RongIMClient.getInstance().sendMessage(
            message,
            body,
            body,
            object : IRongCallback.ISendMessageCallback {
                override fun onAttached(message: Message?) = Unit

                override fun onSuccess(message: Message?) {
                    mark(peerId, key, ImMessage.STATE_SENT)
                    refreshConversations()
                }

                override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                    mark(peerId, key, ImMessage.STATE_FAILED)
                    error = "发送失败：${e?.name ?: "未知错误"}"
                }
            }
        )
    }

    // ---------------------------------------------------------------- 富消息（2026-10-26 第六轮）

    /** 图片：本地 uri 直接给 SDK，上传由 `sendMediaMessage` 完成 */
    fun sendImage(peerId: String, uri: Uri, isGroup: Boolean = false) {
        val thumb = uri
        sendRich(peerId, isGroup, ImageMessage.obtain(uri, thumb), "[图片]") { key ->
            ImMessage(
                key = key, text = "[图片]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_IMAGE, uri = uri.toString(), thumb = thumb.toString(),
            )
        }
    }

    /** 视频：`SightMessage`（小视频），[thumb] 是本地抽出来的封面 */
    fun sendVideo(peerId: String, video: Uri, thumb: Uri?, durationSec: Int, isGroup: Boolean = false) {
        val cover = thumb ?: video
        sendRich(peerId, isGroup, SightMessage.obtain(video, cover, durationSec), "[视频]") { key ->
            ImMessage(
                key = key, text = "[视频]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_VIDEO, uri = video.toString(), thumb = cover.toString(),
                duration = durationSec,
            )
        }
    }

    /** 文件：任意本地文件，带文件名与大小 */
    fun sendFile(peerId: String, uri: Uri, name: String, size: Long, isGroup: Boolean = false) {
        val content = FileMessage.obtain(uri).apply {
            this.name = name
            this.size = size
        }
        sendRich(peerId, isGroup, content, "[文件]") { key ->
            ImMessage(
                key = key, text = "[文件]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_FILE, uri = uri.toString(), name = name, size = size,
            )
        }
    }

    /** 语音：录好的 m4a + 秒数 */
    fun sendVoice(peerId: String, uri: Uri, durationSec: Int, isGroup: Boolean = false) {
        sendRich(peerId, isGroup, VoiceMessage.obtain(uri, durationSec), "[语音]") { key ->
            ImMessage(
                key = key, text = "[语音]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_VOICE, uri = uri.toString(), duration = durationSec,
            )
        }
    }

    /** 位置：经纬度 + 地名（地图缩略图留空，官方客户端会用自带的静态图服务补） */
    fun sendLocation(peerId: String, lat: Double, lng: Double, poi: String, isGroup: Boolean = false) {
        sendRich(peerId, isGroup, LocationMessage.obtain(lat, lng, poi, null), "[位置]") { key ->
            ImMessage(
                key = key, text = "[位置]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_LOCATION, lat = lat, lng = lng, poi = poi,
            )
        }
    }

    /** 富消息统一出口：先乐观上屏，再由 SDK 上传/发送，失败标红 */
    private fun sendRich(
        peerId: String,
        isGroup: Boolean,
        content: io.rong.imlib.model.MessageContent,
        push: String,
        echo: (String) -> ImMessage,
    ) {
        if (peerId.isBlank()) return
        if (status != Status.CONNECTED) {
            error = "IM 未连接，消息没有发出去"
            return
        }
        val message = Message.obtain(
            peerId,
            if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE,
            content,
        )
        val key = message.uId ?: UUID.randomUUID().toString()
        threads[peerId] = threads[peerId].orEmpty() + echo(key)
        persistThread(peerId)
        val callback = object : IRongCallback.ISendMediaMessageCallback {
            override fun onProgress(message: Message?, progress: Int) = Unit
            override fun onCanceled(message: Message?) = Unit
            override fun onAttached(message: Message?) = Unit
            override fun onSuccess(message: Message?) {
                mark(peerId, key, ImMessage.STATE_SENT)
                refreshConversations()
            }
            override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                mark(peerId, key, ImMessage.STATE_FAILED)
                error = "发送失败：${e?.name ?: "未知错误"}"
            }
        }
        if (content is LocationMessage) {
            RongIMClient.getInstance().sendLocationMessage(message, push, null, callback)
        } else {
            RongIMClient.getInstance().sendMediaMessage(message, push, null, callback)
        }
    }

    private fun mark(peerId: String, key: String, state: Int) {
        threads[peerId] = threads[peerId].orEmpty().map {
            if (it.key == key) it.copy(state = state) else it
        }
        persistThread(peerId)
    }

    /** 退出登录时断开并清空（token / 会话 / 消息都是本进程内的状态） */
    fun release() {
        runCatching { RongIMClient.getInstance().logout() }
        // 换账号不能看到上一个人的聊天：内存和磁盘快照一起清
        runCatching { java.io.File(AppContext.instance.filesDir, "im_cache").deleteRecursively() }
        cachePrimed = false
        conversations = emptyList()
        threads.clear()
        groupTitles.clear()
        groupIds.clear()
        groupLookups.clear()
        notice = null
        status = Status.IDLE
        error = null
        connectStarted = false
    }
}
