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
    /**
     * IMLib 本地库的 messageId。**删除消息要用它**（`deleteMessages(int[])` 认的是这个
     * 数字 id，不是 uId）；本地回声在 SDK 还没入库时是 0，那种直接本地移除即可。
     */
    val messageId: Int = 0,
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
            // uId 有时是**空串**（本地刚建、还没被服务端确认），拿空串当 key 会让两条消息
            // 互相当成同一条（缓存里就出现过 key="" 的条目），这里退回 messageId。
            val key = message.uId?.takeIf { it.isNotBlank() } ?: message.messageId.toString()
            /**
             * 2026-11-15 第八十七轮（用户：「把显示问题一并修掉」）：
             * 以前完全忽略 `sentStatus`，于是**本地库里真实的失败消息被画成已发送**，
             * 而界面上那些「发送失败」其实是另一份本地回声 —— 一份内容两个气泡。
             * 现在按 SDK 的状态映射，服务端那份就是唯一真相。
             */
            val state = when (message.sentStatus) {
                Message.SentStatus.SENDING -> STATE_SENDING
                Message.SentStatus.FAILED, Message.SentStatus.CANCELED -> STATE_FAILED
                else -> STATE_SENT
            }
            fun plain(text: String, kind: Int) =
                ImMessage(
                    key = key, text = text, mine = mine, time = time, state = state,
                    messageId = message.messageId, senderId = sender, kind = kind,
                )

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

    /**
     * 发送体积防御上限（第八十九轮探测后定的）：
     * 学校 media 服务器实测 1MB/8MB/20MB/60MB/130MB/300MB 文件与 15MB/40s 视频全部发送成功，
     * **没有可感知的上限**，所以不做常规拦截、也不做本地压缩，这个 1GB 只是极端兜底。
     */
    private const val MAX_SEND_BYTES = 1024L * 1024 * 1024

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

    /** 可恢复错误最多自动补连几次（IPC_DISCONNECT 这类瞬时错误） */
    private const val MAX_AUTO_RETRY = 3

    /** 会话列表 / 聊天页都从这里取（Compose 里读它是可观察的） */
    // ---------------------------------------------------------------- 本地快照（2026-10-26 第七轮）

    /** 缓存只铺一次；连接成功后各条数据会自然覆盖掉它 */
    private var cachePrimed = false

    // ---------------------------------------------------------------- 可恢复错误的重连（第四十五轮）
    /** 自动重连次数（onSuccess 归零）。IPC_DISCONNECT 这类瞬时错误最多自动补 3 次 */
    private var retryAttempt = 0
    private var retryJob: kotlinx.coroutines.Job? = null

    // ---------------------------------------------------------------- 自动刷新与自愈（第八十三轮）
    //
    // 用户原话：「做一个自动刷新私信消息的功能，参考主流聊天软件的解决方案，防止一次私信接收失败后
    // 无法再次刷新新的私信内容」。原来只有两条刷新路径：会话列表 20s 轮询（写在 Compose 的
    // LaunchedEffect 里，页面不在就没)、以及融云的消息回调。于是三种情况会「彻底不动」：
    //   1. 回调丢一条（弱网/心跳），聊天页进过一次之后就再也不拉新消息；
    //   2. 会话列表请求的 SDK 回调没回来 —— `loadingConversations` 永远是 true；
    //   3. 连接假死（状态还写着 CONNECTED，其实早就断了）—— 所有刷新都失败却没人重连。
    // 现在收成一份心跳：每 12s 对齐一次会话列表 + 当前打开的会话；请求超时自动解锁、
    // 连续失败或长时间没有一次成功同步就强制重连；前台的进入/离开由 MainActivity 通知。

    /** 心跳间隔（前台）。融云本身有长连接，这只是「一定有新数据」的兜底 */
    private const val HEARTBEAT_MS = 12_000L

    /** 多久没有一次成功同步就认定连接假死，强制重连 */
    private const val STALE_AFTER_MS = 45_000L

    /** 单个 SDK 查询超过这么久没有回调就当它丢了，否则会永远卡在「加载中」 */
    private const val REQUEST_TIMEOUT_MS = 12_000L

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var appForeground = false

    /** 当前正在看的会话（聊天页进来时设置，离开清空），心跳会顺带刷新它 */
    @Volatile
    private var activePeer: String? = null

    @Volatile
    private var activePeerIsGroup = false

    private var lastConversationOkAt = 0L
    private var lastThreadOkAt = 0L
    private var conversationRequestAt = 0L
    private var conversationFailures = 0
    private var connectingSince = 0L
    private var reconnecting = false

    /** FAILED 状态下的下一次重试时刻（uptimeMillis）。退避重试用，见 heartbeatTick */
    private var nextRetryAt = 0L

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    /**
     * 这些错误是**瞬时**的：IPC 服务还没就绪、弱网、连不上导航服务器。
     * 等一下重连就好，所以既不该弹给用户看，也不该把状态钉死在 FAILED。
     * token / 配置类错误一律排除 —— 重连一百次也没用。
     */
    private fun isRecoverable(e: RongIMClient.ErrorCode?): Boolean {
        val n = e?.name ?: return true
        if (n.contains("TOKEN")) return false
        return n.contains("IPC_DISCONNECT") ||
            n.contains("NETWORK_UNAVAILABLE") ||
            n.contains("TIMEOUT") ||
            n.contains("CONNECT_NAVI") ||
            n.contains("RC_CONN_") ||
            n.contains("UNKNOWN")
    }

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
        // 第八十三轮：真正开始用私信了，把心跳拉起来（前台才跑，见 setForeground）
        if (appForeground) startHeartbeat()
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
        // 用户手动刷新（或重新进页面）= 愿意再试一次，把自动重连的次数归还
        if (status == Status.FAILED) {
            retryAttempt = 0
            nextRetryAt = 0
        }
        connectStarted = true
        status = Status.CONNECTING
        error = null
        scope.launch { doConnect(allowTokenRefresh = true) }
    }

    private fun doConnect(allowTokenRefresh: Boolean) {
        // 1) appKey：学校下发的配置里带
        val config = runCatching { RceApi.configurationAll() }
        val im = config.getOrNull()?.features?.im
        val appKey = im?.appKey?.takeIf { it.isNotBlank() }
        if (appKey == null) {
            status = Status.FAILED
            // 第八十七轮：断网时这条配置请求本身就是失败的，以前一律报「学校未下发 app_key」，
            // 用户会以为是服务端的问题（截图里就在私信页顶上挂着这么一句）。
            error = if (config.isFailure) {
                "连不上学校服务器，稍后会自动重试"
            } else {
                "学校未下发 IM 配置（app_key 为空）"
            }
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
                // 第八十五轮：**小视频（Sight）不在 SDK 默认注册的消息类型里**。
                // `RongIMClient.init()` 内部只注册 Text/Voice/HQVoice/Image/Location/File…
                //（见 SDK 的初始化列表，里面没有 SightMessage），而 `NativeClient.sendMediaMessage`
                // 第一步就是 `getMessageHandler(objectName)`，拿不到就回 PARAMETER_ERROR ——
                // 表现就是「视频一发就失败，其余类型正常」。这里补注册一次（幂等，重复调用无害），
                // 收发两个方向都靠它。
                runCatching { RongIMClient.registerMessageType(SightMessage::class.java) }
                    .onFailure { android.util.Log.w("LanhangIM", "注册 SightMessage 失败", it) }
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
                retryAttempt = 0
                nextRetryAt = 0
                // 通知渠道先建好（没有授权时 showMessage 会自己跳过，不弹也不崩）
                ImNotify.ensureChannel(AppContext.instance)
                refreshConversations()
            }

            override fun onError(e: RongIMClient.ErrorCode?) {
                android.util.Log.w(TAG, "connect error " + e + " tokenLen=" + token.length)
                // 第四十五轮（用户第 5 条：「私信列表经常加载失败，提示 ipc_disconnect」）：
                // IPC_DISCONNECT 表示融云 SDK 与本机 IPC 服务**短时断开** —— 进程刚被系统回收、
                // 刚强杀过 App、SDK 内部服务还在重启，都会碰到它。它本质是瞬时错误，
                // 以前却直接 status = FAILED 并把裸错误码（IPC_DISCONNECT）当提示丢给用户，
                // 看上去就像功能坏了。现在：可恢复错误自动退避重连（0.8s / 3.2s / 7.2s），
                // 状态留在「连接中」，文案也换成中文；真的连不上才失败，并告诉用户可以点重试。
                if (retryAttempt < MAX_AUTO_RETRY && isRecoverable(e)) {
                    retryAttempt++
                    status = Status.CONNECTING
                    error = "正在重新连接聊天服务器（第 " + retryAttempt + " 次）"
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        kotlinx.coroutines.delay(800L * retryAttempt * retryAttempt)
                        if (status != Status.CONNECTED) connectWith(token, allowTokenRefresh)
                    }
                    return
                }
                status = Status.FAILED
                error = if (isRecoverable(e)) {
                    "聊天服务器暂时连不上，点右上角刷新重试"
                } else {
                    "IM 连接失败：" + (e?.name ?: "未知错误")
                }
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
                // 第八十三轮：以前这里只认 CONNECTED / CONNECTING，其余一律「保持原状」——
                // 断网、被踢下线之后 `status` 还写着 CONNECTED，于是所有刷新都被放行、
                // 全部失败，却没有任何人去重连。这就是「一次失败之后再也刷不出新私信」。
                when (s) {
                    RongIMClient.ConnectionStatusListener.ConnectionStatus.CONNECTED -> {
                        status = Status.CONNECTED
                        error = null
                        conversationFailures = 0
                        retryAttempt = 0
                        nextRetryAt = 0
                        lastConversationOkAt = nowMs()
                        refreshNow()
                    }

                    RongIMClient.ConnectionStatusListener.ConnectionStatus.CONNECTING -> {
                        status = Status.CONNECTING
                        connectingSince = nowMs()
                    }

                    RongIMClient.ConnectionStatusListener.ConnectionStatus.DISCONNECTED,
                    RongIMClient.ConnectionStatusListener.ConnectionStatus.NETWORK_UNAVAILABLE -> {
                        status = Status.CONNECTING
                        error = "聊天连接已断开，正在重新连接…"
                        connectingSince = nowMs()
                        forceReconnect("连接状态 " + s.name)
                    }

                    RongIMClient.ConnectionStatusListener.ConnectionStatus.KICKED_OFFLINE_BY_OTHER_CLIENT -> {
                        status = Status.FAILED
                        error = "账号在其它设备登录，消息已断开"
                    }

                    RongIMClient.ConnectionStatusListener.ConnectionStatus.TOKEN_INCORRECT -> {
                        status = Status.FAILED
                        error = "登录状态已失效，请重新登录"
                    }

                    RongIMClient.ConnectionStatusListener.ConnectionStatus.SERVER_INVALID,
                    RongIMClient.ConnectionStatusListener.ConnectionStatus.CONN_USER_BLOCKED -> {
                        status = Status.FAILED
                        error = "聊天服务器拒绝连接：" + s.name
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * 前台 / 后台切换（由 MainActivity 的 onResume / onPause 调）。
     *
     * 后台期间心跳停掉：融云自己的长连接 + 系统推送负责收消息，我们不该在后台每 12 秒唤醒一次。
     * 回前台先补一次刷新（后台期间推送可能漏），失败状态顺便重置重试次数，让它自己长回来。
     */
    fun setForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        if (!foreground) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            return
        }
        // 私信从没被用过（status 还是 IDLE 且没连过）就别在启动时把 IM 拉起来
        if (status == Status.IDLE && !connectStarted) return
        startHeartbeat()
        when (status) {
            Status.CONNECTED -> refreshNow()
            Status.FAILED -> {
                retryAttempt = 0
                nextRetryAt = 0
                ensureStarted()
            }
            else -> Unit
        }
    }

    /** 心跳只跑一份 */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(HEARTBEAT_MS)
                runCatching { heartbeatTick() }
            }
        }
    }

    /** 立刻对齐一次：会话列表 + 正在看的那个会话 */
    private fun refreshNow() {
        if (status != Status.CONNECTED) return
        refreshConversations()
        activePeer?.let { syncThread(it, activePeerIsGroup) }
    }

    /**
     * 一次心跳。三件事：卡死解锁 → 假死重连 → 正常刷新。
     */
    private fun heartbeatTick() {
        if (!appForeground) return
        val now = nowMs()
        android.util.Log.d(TAG, "心跳 status=" + status + " 会话=" + conversations.size + " active=" + activePeer)

        // 1) 请求卡死自救：SDK 回调丢了就解锁。以前 `loadingConversations` 一旦卡在 true，
        //    后面每次刷新都会以为「正在加载」，看上去就是私信再也不更新了。
        if (loadingConversations && conversationRequestAt > 0 &&
            now - conversationRequestAt > REQUEST_TIMEOUT_MS
        ) {
            android.util.Log.w(TAG, "会话列表请求超时，解锁后重试")
            loadingConversations = false
            conversationFailures++
        }

        when (status) {
            Status.CONNECTED -> {
                // 2) 假死判定：连着却一直没成功同步过，说明长连接已经不行了
                if (lastConversationOkAt > 0 && now - lastConversationOkAt > STALE_AFTER_MS) {
                    forceReconnect("超过 " + STALE_AFTER_MS / 1000 + "s 没有同步成功")
                    return
                }
                // 3) 正常同步
                refreshNow()
            }

            Status.CONNECTING -> {
                // 连接卡在「连接中」太久：重来一次（弱网切换网络时很常见）
                if (connectingSince > 0 && now - connectingSince > STALE_AFTER_MS) {
                    forceReconnect("连接超时")
                }
            }

            Status.FAILED -> {
                // 第八十三轮二次修正：失败后**一直**按退避重试（12s → 24s → 48s → 60s 封顶），
                // 而不是试 3 次就躺平。用户断网走进电梯再出来，是不该需要手动点「刷新」的。
                if (now >= nextRetryAt) {
                    retryAttempt++
                    val wait = (HEARTBEAT_MS shl (retryAttempt - 1).coerceAtMost(3))
                        .coerceAtMost(60_000L)
                    nextRetryAt = now + wait
                    android.util.Log.i(TAG, "失败后自动重试 #" + retryAttempt + "，下次间隔 " + wait + "ms")
                    connectStarted = false
                    status = Status.CONNECTING
                    scope.launch { doConnect(allowTokenRefresh = true) }
                }
            }

            Status.IDLE -> if (connectStarted) ensureStarted()
        }
    }

    /**
     * 强制重建连接。
     *
     * 优先用融云自己的 `reconnect`（它内部认当前的会话票据，重建 socket 比重新 `connect` 轻），
     * 失败再退回完整流程 `doConnect`（会重新拿配置、必要时刷 token）。
     */
    private fun forceReconnect(reason: String) {
        if (reconnecting) return
        reconnecting = true
        android.util.Log.i(TAG, "forceReconnect: " + reason)
        status = Status.CONNECTING
        error = "正在重新连接聊天服务器…"
        connectingSince = nowMs()
        retryJob?.cancel()
        retryJob = scope.launch {
            kotlinx.coroutines.delay(1200)
            val client = runCatching { RongIMClient.getInstance() }.getOrNull()
            if (client == null) {
                reconnecting = false
                status = Status.FAILED
                return@launch
            }
            runCatching {
                client.reconnect(object : RongIMClient.ConnectCallback() {
                    override fun onSuccess(userId: String?) {
                        reconnecting = false
                        status = Status.CONNECTED
                        error = null
                        retryAttempt = 0
                        nextRetryAt = 0
                        conversationFailures = 0
                        lastConversationOkAt = nowMs()
                        refreshNow()
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        reconnecting = false
                        android.util.Log.w(TAG, "reconnect 失败：" + e + "，改走完整连接")
                        connectStarted = false
                        status = Status.CONNECTING
                        connectingSince = nowMs()
                        scope.launch { doConnect(allowTokenRefresh = true) }
                    }

                    override fun onTokenIncorrect() {
                        reconnecting = false
                        Session.imToken = ""
                        connectStarted = false
                        status = Status.CONNECTING
                        scope.launch { doConnect(allowTokenRefresh = true) }
                    }
                })
            }.onFailure {
                reconnecting = false
                android.util.Log.w(TAG, "reconnect 抛异常：" + it.message)
                connectStarted = false
                status = Status.FAILED
                error = "聊天服务器连接失败：" + (it.message ?: "未知错误")
            }
        }
    }

    /** 聊天页进来 / 离开：告诉心跳该顺带刷新哪条会话 */
    fun setActiveChat(peerId: String?, isGroup: Boolean) {
        activePeer = peerId
        activePeerIsGroup = isGroup
        if (peerId != null) {
            lastThreadOkAt = nowMs()
        }
    }

    /**
     * 合并「服务端最新 N 条」与「本机还在发 / 刚发失败的本地回声」。
     *
     * 2026-11-15 第八十七轮（用户：「把显示问题一并修掉」）：
     * 旧写法是 `mapped + pending`——直接往后追加、也不排序。于是每失败一次留下的本地回声
     * 都堆在列表**最末尾**：一进会话，屏幕最新处全是「发送失败」，真正发出去的那些
     * 视频/图片反而被顶到上面看不见，看起来就像「发出去的视频不显示」。
     * 现在先按 key 去重、再按时间排序，失败的回声回到它自己那条消息的时间位置。
     *
     * @param current 当前内存里的列表（心跳刷新时也用它取本地回声）
     */
    private fun mergeThread(
        mapped: List<ImMessage>,
        current: List<ImMessage>,
    ): List<ImMessage> {
        val pending = current.filter {
            it.state == ImMessage.STATE_SENDING || it.state == ImMessage.STATE_FAILED
        }
        if (pending.isEmpty()) return mapped
        val seen = mapped.mapTo(HashSet(mapped.size)) { it.key }
        return (mapped + pending.filter { it.key !in seen }).sortedBy { it.time }
    }

    /**
     * 只同步某个会话的最新消息（不清未读、不建会话）。
     *
     * 与 [loadMessages] 的分工：进页面那次用 `loadMessages`（要清未读），之后心跳和兜底用这个，
     * 免得每 12 秒都发一次 `clearMessagesUnreadStatus` 又把会话列表刷一遍。
     * 正在发送/失败的本机消息会被保留，不会被服务端结果覆盖掉。
     */
    private fun syncThread(peerId: String, isGroup: Boolean) {
        if (peerId.isBlank() || status != Status.CONNECTED) return
        val type = if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE
        runCatching {
            RongIMClient.getInstance().getLatestMessages(
                type,
                peerId,
                30,
                object : RongIMClient.ResultCallback<List<Message>>() {
                    override fun onSuccess(list: List<Message>?) {
                        val mapped = list.orEmpty().mapNotNull { ImMessage.of(it) }.sortedBy { it.time }
                        if (mapped.isEmpty()) return
                        val old = threads[peerId].orEmpty()
                        val merged = mergeThread(mapped, old)
                        lastThreadOkAt = nowMs()
                        // 没变化就不写，避免每 12 秒把界面重组成同一个列表
                        // （第八十七轮：比较里带上 state，发送中 → 已发送 / 失败才不会被当成「没变化」）
                        if (old.size == merged.size &&
                            old.map { it.key to it.state } == merged.map { it.key to it.state }
                        ) {
                            return
                        }
                        threads[peerId] = merged
                        persistThread(peerId)
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        android.util.Log.w(TAG, "同步会话失败 " + peerId + "：" + e)
                    }
                }
            )
        }
    }

    /**
     * 删除聊天记录（2026-11-15 第八十八轮，用户：「增加删除聊天记录功能，长按后仿照 QQ」）。
     *
     * 注意这是 QQ 的「删除」不是「撤回」：只是把消息从**本机**移除（IMLib 本地库 + 我们的
     * 线程与缓存），对方那边的记录不受影响，也不能指望服务端同步撤回。
     * [keys] 是界面选中的消息 key；messageId 为 0 的（还没入 SDK 库的本地回声）只从本地移除。
     */
    fun deleteMessages(peerId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val before = threads[peerId].orEmpty()
        val doomed = before.filter { it.key in keys }
        if (doomed.isEmpty()) return
        threads[peerId] = before.filterNot { it.key in keys }
        persistThread(peerId)
        val ids = doomed.map { it.messageId }.filter { it > 0 }.toIntArray()
        if (ids.isEmpty()) {
            refreshConversations()
            return
        }
        runCatching {
            RongIMClient.getInstance().deleteMessages(
                ids,
                object : RongIMClient.ResultCallback<Boolean>() {
                    override fun onSuccess(t: Boolean?) {
                        refreshConversations()
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        android.util.Log.w(TAG, "删除消息失败：" + e)
                        error = "删除失败：${e?.name ?: "未知错误"}"
                        // SDK 本地库没删掉的话，心跳同步会把它拉回来，不会丢记录
                    }
                },
            )
        }.onFailure {
            android.util.Log.w(TAG, "deleteMessages 抛异常", it)
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
        // 第八十三轮：上一次还没回来就别再发（心跳 12s 一次，正好和请求超时对齐）。
        // 但超过超时时间就当作丢了，重新发 —— 否则一次回调丢失会把刷新永久堵死。
        val startAt = nowMs()
        if (loadingConversations && conversationRequestAt > 0 &&
            startAt - conversationRequestAt < REQUEST_TIMEOUT_MS
        ) {
            return
        }
        loadingConversations = true
        conversationRequestAt = startAt
        RongIMClient.getInstance().getConversationList(
            object : RongIMClient.ResultCallback<List<Conversation>>() {
                override fun onSuccess(list: List<Conversation>?) {
                    // 第八十三轮：记一笔成功时间，假死判定和失败重连都靠它
                    lastConversationOkAt = nowMs()
                    conversationFailures = 0
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
                    conversationFailures++
                    error = "会话列表加载失败：${e?.name ?: "未知错误"}"
                    // 第八十三轮：连着两次拉不到就别傻等了，重建连接。
                    // （以前这里只写一句错误文案，连接其实已经坏了，用户只能看着旧列表。）
                    if (conversationFailures >= 2) forceReconnect("会话列表连续失败 " + conversationFailures + " 次")
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
                    lastThreadOkAt = nowMs()
                    val mapped = list.orEmpty()
                        .mapNotNull { ImMessage.of(it) }
                        .sortedBy { it.time }
                    threads[peerId] = mergeThread(mapped, threads[peerId].orEmpty())
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

    /**
     * 发一条文本（乐观上屏，失败标红）。
     *
     * 返回 **是否已经入队**：断网 / 未连接时返回 false，调用方要保留输入框里的草稿 ——
     * 第八十七轮修的一个小显示问题：以前断网时点发送，气泡没出现、草稿也被清空了，字直接丢掉。
     */
    fun sendText(peerId: String, text: String, isGroup: Boolean = false): Boolean {
        val body = text.trim()
        if (body.isEmpty() || peerId.isBlank()) return false
        if (status != Status.CONNECTED) {
            error = "IM 未连接，消息没有发出去"
            return false
        }
        val message = Message.obtain(
            peerId,
            if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE,
            TextMessage.obtain(body)
        )
        val key = message.uId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        threads[peerId] = (threads[peerId].orEmpty() + ImMessage(
            key = key,
            text = body,
            mine = true,
            time = System.currentTimeMillis(),
            state = ImMessage.STATE_SENDING,
            senderId = Session.userId,
        )).sortedBy { it.time }
        persistThread(peerId)
        RongIMClient.getInstance().sendMessage(
            message,
            body,
            body,
            object : IRongCallback.ISendMessageCallback {
                override fun onAttached(message: Message?) = Unit

                override fun onSuccess(message: Message?) {
                    mark(peerId, key, ImMessage.STATE_SENT, message?.uId, message?.messageId)
                    refreshConversations()
                }

                override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                    mark(peerId, key, ImMessage.STATE_FAILED, message?.uId, message?.messageId)
                    error = describeSendError(e)
                }
            }
        )
        return true
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

    /**
     * 视频：`SightMessage`（小视频），[thumb] 是本地抽出来的封面。
     *
     * 2026-11-15 第八十五轮：**参数顺序写反了**。SDK 的签名是
     * `SightMessage.obtain(Uri thumbUri, Uri localPath, int duration)` —— 第一个是封面，
     * 第二个才是视频本体（反编译 `SightMessage(Uri, Uri, int)`：`mThumbUri = a`、`setLocalPath(b)`）。
     * 我们原来传的是 `(video, cover)`，于是「上传的视频」其实是那张封面 jpg、
     * 「缩略图」指向视频文件，服务端按视频解析必然失败 —— 视频一直发不出去就是这个原因。
     * 官方 IMKit 用的是 `SightMessage.obtain(uri, duration)`，顺序也是「封面在前」。
     */
    fun sendVideo(peerId: String, video: Uri, thumb: Uri?, durationSec: Int, isGroup: Boolean = false) {
        // 第九十轮更正第八十九轮的结论：服务端限制**不在媒体文件体积**，而在消息体
        // （序列化成 JSON 的 content ≤ 128KB）。视频文件走独立上传通道，300MB 文件实测能发，
        // 所以这里不压缩视频本体 —— 压了既绕不开限制又白掉画质。要压的是封面，见 [ImMedia]。
        val videoSize = video.path?.let { java.io.File(it).length() } ?: 0L
        if (videoSize > MAX_SEND_BYTES) {
            error = "视频超过 1GB，服务器可能无法接收，已取消发送"
            return
        }
        // 第九十轮：`thumb ?: video` 是致命兜底 —— 抽帧失败时会把**整个视频文件**当封面，
        // SDK 直接 file2byte + base64，几十 MB 必然超限甚至 OOM。现在强制收口到 ≤48KB 的 JPEG。
        val cover = resolveThumb(thumb, video)
        // 第八十五轮二次修正：必须用**两参** obtain(video, duration)。
        // 三参 `obtain(thumb, local, duration)` 只设 mThumbUri 和 localPath，
        // **不设 name / size**（那是 File 版构造函数干的活，两参 obtain 内部才会走 File 版），
        // 实测这样发出去 SDK 直接回 PARAMETER_ERROR。官方 SightPlugin 用的也是两参版。
        val sight = SightMessage.obtain(video, durationSec) ?: run {
            android.util.Log.e("ImSend", "SightMessage.obtain 返回 null video=" + video)
            error = "视频文件读不出来"
            return
        }
        // 封面：两参 obtain 不带缩略图，单独补一次（接收端气泡靠它显示预览）
        // 拿不到封面就干脆不设，让 SDK 走它自己的抽帧分支，绝不能塞视频文件。
        if (cover != null) runCatching { sight.thumbUri = cover }
        val thumbBytes = cover?.path?.let { java.io.File(it).length() } ?: 0L
        android.util.Log.i(
            "ImSend",
            "准备发送视频 size=" + sight.size + " 封面字节=" + thumbBytes +
                " 估算消息体≈" + ((thumbBytes + 2) / 3) * 4 +
                " 文件存在=" + (sight.localPath?.path?.let { java.io.File(it).exists() } ?: false),
        )
        sendRich(peerId, isGroup, sight, "[视频]") { key ->
            ImMessage(
                key = key, text = "[视频]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_VIDEO, uri = video.toString(),
                thumb = cover?.toString().orEmpty(), duration = durationSec,
            )
        }
    }

    /**
     * 第九十轮：把封面收口到「一定 ≤48KB 的 JPEG」。
     *
     * 反编译 `SightMessageHandler.encodeMessage()`：只要 `thumbUri` 是 file://，
     * 它就 `FileUtils.file2byte` → `Base64.encodeToString` → `setBase64`，
     * **不缩放不压缩**；`SightMessage.encode()` 再把它作为 `content` 写进 JSON。
     * ① 传进来的封面先走 [ImMedia.normalizeThumbFile]（合规原样用，不合规重压）；
     * ② 没有封面 / 封面不是图片，就自己用 ThumbnailUtils 抽一帧再压；
     * ③ 都失败返回 null —— **绝不把视频文件本身当封面**。
     */
    @Suppress("DEPRECATION")
    private fun resolveThumb(thumb: Uri?, video: Uri): Uri? {
        val supplied = thumb?.path?.let { java.io.File(it) }?.let { ImMedia.normalizeThumbFile(it) }
        if (supplied != null) return Uri.fromFile(supplied)
        val path = video.path ?: return null
        val bmp = runCatching {
            android.media.ThumbnailUtils.createVideoThumbnail(
                path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
            )
        }.getOrNull() ?: return null
        val out = java.io.File(java.io.File(path).parentFile, "thumb_fb_" + System.currentTimeMillis() + ".jpg")
        val result = ImMedia.writeThumb(bmp, out)
        if (!bmp.isRecycled) bmp.recycle()
        return result?.let { Uri.fromFile(it) }
    }

    /**
     * 文件：任意本地文件，带文件名与大小。
     *
     * 2026-11-15 第八十五轮：**不要再覆盖 name/size**。`FileMessage.obtain(file://…)` 内部已经
     * 用 `File.getName()` / `File.length()` 填好了；原来我们又拿 `OpenableColumns` 的值覆盖一遍，
     * 而 SAF / 微信 QQ 的文件 provider 经常查不到 SIZE（返回 0），消息就变成「0 字节文件」，
     * 服务端与对端都当成非法文件 —— 文件发不出去/对方收到 0 字节就是这个原因。
     * 官方 `FilePlugin` 只 `setType(后缀)`，其余交给 SDK。
     */
    fun sendFile(peerId: String, uri: Uri, name: String, size: Long, isGroup: Boolean = false) {
        // 第八十九轮探测：300MB 都能发，这里只挡极端情况
        if (size > MAX_SEND_BYTES) {
            error = "文件超过 1GB，服务器可能无法接收，已取消发送"
            return
        }
        val content = FileMessage.obtain(uri) ?: run {
            error = "文件不存在或读不出来"
            return
        }
        if (name.isNotBlank()) content.name = name
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?.let { content.type = it }
        // 大小以 SDK 从本地文件读到的那份为准（[size] 参数只作兜底，通常用不上）
        val realSize = content.size.takeIf { it > 0 } ?: size
        sendRich(peerId, isGroup, content, "[文件]") { key ->
            ImMessage(
                key = key, text = "[文件]", mine = true, time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING, senderId = Session.userId,
                kind = ImMessage.KIND_FILE, uri = uri.toString(), name = name, size = realSize,
            )
        }
    }

    /**
     * 语音：录好的 m4a/amr + 秒数。
     *
     * 2026-11-15 第八十五轮：真正的毛病不在编码，而在**发送 API 选错了**（见 [sendRich] 里的分流）。
     * `VoiceMessage` 在 2.9 里继承的是 `MessageContent` 而不是 `MediaMessageContent`，
     * 走 `sendMediaMessage` 会在 SDK 内部 `checkcast` 时抛 ClassCastException —— 语音必失败。
     */
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

    /**
     * 把融云错误码翻成人话。第九十轮：`RC_MSG_SIZE_OUT_OF_LIMIT` 极易被误读成
     * 「视频文件太大」，实际是**消息体（JSON）超过 128KB**，多数由视频封面 base64 撑爆。
     * 封面已在 [resolveThumb] 里压到 ≤48KB；若仍触发，给用户一个能看懂的提示。
     */
    private fun describeSendError(e: RongIMClient.ErrorCode?): String = when (e?.name) {
        "RC_MSG_SIZE_OUT_OF_LIMIT", "MSG_SIZE_OUT_OF_LIMIT" ->
            "发送失败：消息体超过 128KB（视频封面过大），请重发或换一个视频"
        else -> "发送失败：${e?.name ?: "未知错误"}"
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
        val key = message.uId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        threads[peerId] = (threads[peerId].orEmpty() + echo(key)).sortedBy { it.time }
        persistThread(peerId)
        val callback = object : IRongCallback.ISendMediaMessageCallback {
            // 第八十五轮：上传进度与失败原因都留一条日志（tag ImSend），
            // 以后「媒体发不出去」可以直接看是哪一步断的，不用再猜。
            override fun onProgress(message: Message?, progress: Int) {
                if (progress % 25 == 0) {
                    android.util.Log.i("ImSend", "上传中 " + content.javaClass.simpleName + " " + progress + "%")
                }
            }
            override fun onCanceled(message: Message?) = Unit
            override fun onAttached(message: Message?) = Unit
            override fun onSuccess(message: Message?) {
                android.util.Log.i("ImSend", "发送成功 " + content.javaClass.simpleName)
                mark(peerId, key, ImMessage.STATE_SENT, message?.uId, message?.messageId)
                refreshConversations()
            }
            override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                android.util.Log.e("ImSend", "发送失败 " + content.javaClass.simpleName + " code=" + e)
                mark(peerId, key, ImMessage.STATE_FAILED, message?.uId, message?.messageId)
                error = describeSendError(e)
            }
        }
        /**
         * 语音专用回调：`sendMessage` 只认 [IRongCallback.ISendMessageCallback]。
         *
         * 2026-11-15 第八十五轮（用户：「私信无法发送视频、语音、文件」）：
         * `io.rong.message.VoiceMessage` 在 2.9 里 **不是** `MediaMessageContent`，
         * 而 `sendMediaMessage()` 内部第一件事就是 `checkcast MediaMessageContent` —— 必抛
         * ClassCastException，异常被下面的 runCatching 吞掉，用户只看到「发送失败」。
         * 官方 App 的 IMKit（`AudioRecordManager.sendAudioFile`）发普通语音用的也是 `sendMessage`，
         * 只有高清语音 `HQVoiceMessage`（它才是 MediaMessageContent）才走 sendMediaMessage。
         */
        val voiceCallback = object : IRongCallback.ISendMessageCallback {
            override fun onAttached(message: Message?) = Unit
            override fun onSuccess(message: Message?) {
                mark(peerId, key, ImMessage.STATE_SENT, message?.uId, message?.messageId)
                refreshConversations()
            }
            override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                android.util.Log.e("ImSend", "语音发送失败 code=" + e)
                mark(peerId, key, ImMessage.STATE_FAILED, message?.uId, message?.messageId)
                error = describeSendError(e)
            }
        }
        // 2026-10-31 第五十六轮：融云的 sendMediaMessage 是同步入口，内部读不到本地文件
        // （空文件 / 录音被截断）时会当场抛 RuntimeException，直接闪退。这里兜住并标红。
        runCatching {
            when (content) {
                is LocationMessage ->
                    RongIMClient.getInstance().sendLocationMessage(message, push, null, callback)

                is VoiceMessage ->
                    RongIMClient.getInstance().sendMessage(message, push, null, voiceCallback)

                else ->
                    RongIMClient.getInstance().sendMediaMessage(message, push, null, callback)
            }
        }.onFailure {
            android.util.Log.e("ImSend", "sendMediaMessage 抛异常", it)
            mark(peerId, key, ImMessage.STATE_FAILED)
            error = "发送失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /**
     * 更新本地回声的状态。
     *
     * 2026-11-15 第八十七轮（用户：「把显示问题一并修掉」）：
     * [sdkUid] 是发送回调里 SDK 给这条消息分配的 uId。之前本地回声一直用自造的
     * `UUID` 当 key，服务端回来的那份副本 key 是 uId —— 两个 key 永远对不上，
     * 合并时只能「两份都留」，界面就出现「一条正常副本 + 一条发送失败幽灵」。
     * 现在成功/失败都把回声的 key 换成 uId，两份自然并成一条。
     */
    private fun mark(
        peerId: String,
        key: String,
        state: Int,
        sdkUid: String? = null,
        sdkMessageId: Int? = null,
    ) {
        val newKey = sdkUid?.takeIf { it.isNotBlank() }
        // 用 LinkedHashMap 覆盖写：同 key 时保留**后出现**的那份（刚更新的状态），
        // 而不是像 distinctBy 那样保留先出现的旧状态。
        val out = LinkedHashMap<String, ImMessage>()
        threads[peerId].orEmpty().forEach { m ->
            out[if (m.key == key) (newKey ?: m.key) else m.key] =
                if (m.key == key) {
                    m.copy(key = newKey ?: m.key, state = state, messageId = sdkMessageId ?: m.messageId)
                } else m
        }
        threads[peerId] = out.values.sortedBy { it.time }
        persistThread(peerId)
    }

    /** 退出登录时断开并清空（token / 会话 / 消息都是本进程内的状态） */
    fun release() {
        // 第八十三轮：心跳属于「这个账号的会话」，退出登录必须一起停掉
        heartbeatJob?.cancel()
        heartbeatJob = null
        retryJob?.cancel()
        retryJob = null
        reconnecting = false
        activePeer = null
        activePeerIsGroup = false
        lastConversationOkAt = 0
        lastThreadOkAt = 0
        conversationRequestAt = 0
        conversationFailures = 0
        nextRetryAt = 0
        loadingConversations = false
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
