package com.lanhang.miuix.mine

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.Intent
import android.text.TextUtils
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.MiuixDialogSpec
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.miuix.showMiuixDialog
import com.lanhang.miuix.ui.miuix.showMiuixPicker
import com.lanhang.miuix.ui.miuix.MiuixProgressDialog
import com.lanhang.miuix.ui.miuix.showMiuixProgress
import com.lanhang.miuix.schedule.ScheduleData
import com.lanhang.miuix.schedule.ScheduleExporter
import com.lanhang.miuix.schedule.ScheduleRepository
import com.lanhang.miuix.settings.Diagnostics
import com.lanhang.miuix.session.AppLanguage
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.MainActivity
import com.lanhang.miuix.moments.CommentEntry
import com.lanhang.miuix.moments.ImagePreviewActivity
import com.lanhang.miuix.moments.LikeEntry
import com.lanhang.miuix.moments.MomentActions
import com.lanhang.miuix.moments.MomentFeedSource
import com.lanhang.miuix.moments.MomentItem
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.im.ImRepository
import com.lanhang.miuix.profile.ProfileStore
import com.lanhang.miuix.profile.ProfileStackState
import com.lanhang.miuix.profile.profileTitle
import com.lanhang.miuix.net.MomentsApi
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.net.cleanOrgName
import com.lanhang.miuix.net.StaffBrief
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.isNightMode
import com.lanhang.miuix.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的」Tab 宿主（0.8.0 由原「设置」Tab 升级而来）。
 *
 * 一层：我的（头像资料卡 + 工作 / 身份码 / 相册 / 设置 / 关于）
 * 二层：设置、身份码、相册（我发过的所有动态）、个人主页、工作（占位）
 * 三层：设置 > 外观 / 课表工具 / 关于
 *
 * 二级、三级页都在**本 Fragment 内**用页面栈 + AnimatedContent 完成（和原设置页同一套
 * Miuix 推入几何：只有一张纸在动），系统返回键退一层；连按两次底栏「我的」也回根页。
 * 数据侧沿用原来那套：主题/语言/导出/诊断/退出登录逻辑一行没改，只是位置挪进了二级页。
 */
class MineFragment : Fragment() {

    /**
     * Compose 侧读取的 UI 状态。视觉层已换成 Miuix（见 [MineScreen]），
     * 这里只保留数据与动作；改完状态要调 [render] 触发重组。
     */
    private var uiState by mutableStateOf(MineUiState())

    private val themeOptions = listOf(
        "light" to R.string.settings_theme_light,
        "dark" to R.string.settings_theme_dark,
        "system" to R.string.settings_theme_system
    )

    /**
     * 通讯录的下钻栈 + 组织树缓存。
     *
     * 2026-10-26 第二轮：从 Compose 的 `remember` 提到 Fragment 这一层，
     * 这样**顶栏返回箭头**也能「先退一级目录」（原来它直接退出通讯录页，
     * 用户只能点页面里那行「返回上一级」胶囊——那颗胶囊本轮已按用户要求删掉）。
     */
    private val contactsUi = ContactsUiState()

    /**
     * 个人主页栈（2026-10-27 第二十一轮）。
     *
     * 通讯录 / 消息 / 私信 / 相册里点进来的「他人主页」全部走这一份实现
     * （`profile/ProfileHost.kt`）：A → B 可以一直往下压，返回正好弹回 A，
     * 转场、标题、数据缓存都与工作圈完全同一套。
     */
    private val profileStack = ProfileStackState()

    /** 主页数据（资料 + 动态分页 + 点赞评论），按 uid 缓存 */
    private lateinit var profileStore: ProfileStore

    /**
     * 页面栈里的一层：页面 + 这一层自带的目标。
     *
     * [peerId]/[title]：`IM_CHAT` 层看的是谁（单聊=对方 userId，群聊=groupId；title 是顶栏标题）
     * [otherId]：`PROFILE_OTHER` 层看的是谁
     */
    private data class MineEntry(
        val page: Int,
        val peerId: String = "",
        val title: String = "",
        val otherId: String = "",
    )
    /**
     * 页面栈：第 0 层固定是 [MinePage.ROOT]，返回键只弹栈顶。
     *
     * 2026-10-26 第五轮（用户反馈「群聊 → 某人主页 → 发私信 → 返回，第二次返回到的是私聊而不是群聊」）：
     * 栈里以前只存页面类型（`Int`），聊天页的目标（peerId）只有一份全局 `uiState`，
     * 于是「群聊(2601063) → 他人主页 → 和他的私聊」压了两层 `IM_CHAT` 之后，
     * 弹回第一层 `IM_CHAT` 时渲染用的还是**最新**那个 peer，看起来就像返回进了私聊页。
     * 现在每层自带目标（[MineEntry]），出栈时按该层还原，群聊就是群聊。
     */
    private val stack = ArrayList<MineEntry>().apply { add(MineEntry(MinePage.ROOT)) }

    /** 我的资料只拉一次（失败允许下次重试） */
    private var staffLoaded = false

    /** 我发过的动态分页游标 */
    private var selfFromFeedId: String? = null

    private var pendingExport: ScheduleExporter.ExportPayload? = null
    private var exportJob: Job? = null
    private val createIcsDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@registerForActivityResult
        saveDocument(uri, payload.ics.toByteArray(Charsets.UTF_8), "${payload.baseFileName}.ics")
    }
    private val createCsvDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@registerForActivityResult
        saveDocument(uri, payload.csv.toByteArray(Charsets.UTF_8), "${payload.baseFileName}.csv")
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主页里的点赞/评论改的是同一批 MomentItem，顺手把相册那一页的 revision 提一下
        profileStore = ProfileStore(lifecycleScope) {
            uiState = uiState.copy(selfRevision = uiState.selfRevision + 1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = LocalContext.current.isNightMode()) {
                MineScreen(
                    state = uiState,
                    contactsState = contactsUi,
                    onOpenGrades = { open(MinePage.GRADES) },
                    onOpenIdCard = { open(MinePage.IDCARD) },
                    onOpenAlbum = { open(MinePage.ALBUM) },
                    onOpenProfile = { open(MinePage.PROFILE) },
                    onOpenSettings = { open(MinePage.SETTINGS) },
                    onOpenAppearance = { open(MinePage.APPEARANCE) },
            onOpenMomentsFilter = { open(MinePage.MOMENTS_FILTER) },
            onOpenClassReminder = { open(MinePage.CLASS_REMINDER) },
                    onOpenTools = { open(MinePage.TOOLS) },
                    onOpenAbout = { open(MinePage.ABOUT) },
            onOpenContacts = { open(MinePage.CONTACTS) },
            onOpenMessages = { open(MinePage.MESSAGES) },
            onOpenIm = { open(MinePage.IM) },
            onOpenImChat = { peerId, title -> openImChat(peerId, title) },
            onOpenOtherProfile = { id -> openOtherProfile(id) },
            onOpenMoments = { feedId -> (activity as? MainActivity)?.openMomentsFeed(feedId) },
                    onTheme = { showThemeDialog() },
                    onLanguage = { showLanguageDialog() },
                    onExport = { showExportSchedule() },
                    onDiagnostics = { showDiagnostics() },
                    onDebugSchedule = { (activity as? com.lanhang.miuix.ui.MainActivity)?.debugSchedule() },
                    onLogout = { confirmLogout() },
                    onRefreshIdCard = { refreshIdCard() },
                    onRefreshSelf = { loadSelf(reset = true) },
                    onLoadMoreSelf = { loadSelf(reset = false) },
                    onLike = { selfLike(it) },
                    onPostComment = { item, replyTo, text -> selfComment(item, replyTo, text) },
                    onDeleteComment = { item, comment -> selfDeleteComment(item, comment) },
                    onDeletePost = { item -> selfDeletePost(item) },
                    onImage = { urls, index, rect ->
                        // rect = 缩略图在窗口里的位置，预览页据此做「从缩略图放大 / 缩回原位」
                        ImagePreviewActivity.start(requireContext(), urls, index, rect)
                    },
                    profileStack = profileStack,
                    profileStore = profileStore,
                    onProfileTitle = { title ->
                        // 主页退出动画期间它还在组合里，标题只在「当前确实是主页」时才写，
                        // 否则会把刚进去的聊天页顶栏又改回「X 的个人主页」
                        if (stack.last().page == MinePage.PROFILE_OTHER) {
                            (activity as? MainActivity)?.showToolbarSub(title, onBack = { goBack() })
                        }
                    },
                    onMessageOther = { id, name -> openImChat(id, name) },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val restored = savedInstanceState?.getIntArray(KEY_STACK)
        if (restored != null && restored.isNotEmpty()) {
            val peerIds = savedInstanceState.getStringArray(KEY_STACK_PEER) ?: emptyArray()
            val titles = savedInstanceState.getStringArray(KEY_STACK_TITLE) ?: emptyArray()
            val others = savedInstanceState.getStringArray(KEY_STACK_OTHER) ?: emptyArray()
            stack.clear()
            restored.forEachIndexed { i, page ->
                stack.add(
                    MineEntry(
                        page = page,
                        peerId = peerIds.getOrNull(i).orEmpty(),
                        title = titles.getOrNull(i).orEmpty(),
                        otherId = others.getOrNull(i).orEmpty(),
                    )
                )
            }
            stack[0] = MineEntry(MinePage.ROOT)
            savedInstanceState?.getStringArray(KEY_PROFILE_STACK)
                ?.takeIf { it.isNotEmpty() }
                ?.let { profileStack.restore(it.toList()) }
            backCallback.isEnabled = stack.size > 1
            // 恢复后让当前层的数据跟着栈顶走（否则页面渲染的是别的会话 / 别的人）
            applyEntry(stack.last(), resetData = false)
        }
        render()
        syncToolbar()
        ensureStaff()
    }

    /** 把 Fragment 侧的数据整理成 [MineUiState] 交给 Compose 渲染。 */
    private fun render() {
        val version = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "--"
        val staff = uiState.selfStaff
        val name = staff?.name?.takeIf { it.isNotBlank() } ?: Session.name
        val staffNo = staff?.staffNo?.takeIf { it.isNotBlank() }
            ?: Session.staffNo.ifBlank { Session.username }
        val department = cleanDepartment(staff?.depart_name, staff?.org, Session.department)
        val avatar = staff?.portraitUrl.orEmpty().ifBlank { Session.avatarUrl }
        uiState = uiState.copy(
            page = stack.last().page,
            themeLabel = currentThemeLabel(),
            languageLabel = currentLanguageLabel(),
            account = Session.name.ifEmpty { Session.username }.ifEmpty { "--" },
            version = version,
            proto = "NchuApp/${Session.appVersion}",
            name = name,
            staffNo = staffNo,
            department = department,
            avatarUrl = avatar,
            idCard = uiState.idCard.copy(
                name = name,
                staffNo = staffNo,
                department = department,
                avatarUrl = avatar,
            ),
        )
    }

    override fun onResume() {
        refreshUnread()
        super.onResume()
        syncToolbar()
        ensureStaff()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(KEY_STACK, stack.map { it.page }.toIntArray())
        outState.putStringArray(KEY_STACK_PEER, stack.map { it.peerId }.toTypedArray())
        outState.putStringArray(KEY_STACK_TITLE, stack.map { it.title }.toTypedArray())
        outState.putStringArray(KEY_STACK_OTHER, stack.map { it.otherId }.toTypedArray())
        outState.putStringArray(
            KEY_PROFILE_STACK,
            profileStack.snapshot().toTypedArray(),
        )
    }

    /** 与 MainActivity 对齐 Toolbar 状态（目录态 = 标题+无返回；二级页 = 返回箭头+子页标题）。 */
    /**
     * 与 MainActivity 对齐顶栏状态。
     *
     * 第十一轮（2026-10-26）：**这里不再挑动效档位**。顶栏怎么动只有一份实现
     * （[com.lanhang.miuix.ui.shell.BarAnimation]）：推入/退回二级页、切 Tab、切课表
     * 都播同一条收放 tween；标题变了而形态没变时，就只剩标题自身的淡化。
     */
    fun syncToolbar() {
        val act = activity as? MainActivity ?: return
        // 不是当前 Tab 就别写标题（抢写会让顶栏标题叠加，见 MainActivity.activeTab 注释）
        if (isHidden || act.activeTab != com.lanhang.miuix.ui.shell.ShellState.TAB_MINE) return
        val page = stack.last().page
        if (page == MinePage.ROOT) {
            act.showToolbarMain(getString(R.string.tab_mine))
        } else if (page == MinePage.IM_CHAT) {
            // 聊天页顶栏显示对方名字 / 群名（拿不到就退回「私信」）
            val title = uiState.imPeerName.takeIf { it.isNotBlank() } ?: getString(R.string.mine_im)
            act.showToolbarSub(title, onBack = { goBack() })
        } else if (page == MinePage.PROFILE_OTHER) {
            // 与他人主页同一份标题：姓名回来之前显示「个人主页」，回来之后是「X 的个人主页」
            val uid = profileStack.current.orEmpty()
            val name = profileStore.peek(uid)?.name ?: UserInfoCache.get(uid)?.name
            act.showToolbarSub(profileTitle(requireContext(), name), onBack = { goBack() })
        } else if (page == MinePage.CONTACTS) {
            // 通讯录是深层目录：左上角「首页」一步回「我的」根页，返回箭头只退一级目录
            // （用户 2026-10-26 要求）
            act.showToolbarSub(
                getString(MinePage.titleRes(page)),
                // 返回箭头 = 先退出搜索、再退一级目录；已经在通讯录根层才退「我的」页栈
                onBack = { if (!contactsUi.backOnce()) goBack() },
                onHome = { goRoot() },
            )
        } else {
            act.showToolbarSub(getString(MinePage.titleRes(page)), onBack = { goBack() })
        }
    }

    /** 往深一层走（不带目标的页面：设置/相册/身份码……） */
    private fun open(page: Int) = open(MineEntry(page))

    /** 往深一层走：把这一层自带的目标一起压栈，返回时才能还原成「当时那一页」 */
    private fun open(entry: MineEntry) {
        if (stack.last().page == entry.page && entry.peerId.isBlank() && entry.otherId.isBlank()) return
        stack.add(entry)
        uiState = uiState.copy(forward = true)
        backCallback.isEnabled = true
        render()
        syncToolbar()
        onPageOpened(entry.page)
    }

    /**
     * 按栈里某一层还原当前显示目标（弹出/恢复时调用）。
     *
     * 只负责「这一层看的是谁」：聊天页换回该层的 peer、他人主页换回该层的人；
     * [resetData] = true 时顺带清掉上一个目标的动态列表，避免串号。
     */
    private fun applyEntry(entry: MineEntry, resetData: Boolean = true) {
        when (entry.page) {
            MinePage.IM_CHAT -> {
                if (uiState.imPeerId != entry.peerId || uiState.imPeerName != entry.title) {
                    uiState = uiState.copy(
                        imPeerId = entry.peerId,
                        imPeerName = entry.title.ifBlank {
                            UserInfoCache.get(entry.peerId)?.name.orEmpty()
                        },
                    )
                }
            }
            MinePage.PROFILE_OTHER -> {
                // 主页栈自己记着「这一层是谁」，这里只在栈空时按页面栈里的目标还原
                // （进程恢复、或从聊天页退回主页时）
                if (profileStack.isEmpty) profileStack.restore(listOf(entry.otherId))
            }
            else -> Unit
        }
    }

    /** 退一层（物理返回键与顶栏返回箭头共用） */
    private fun goBack() {
        // 他人主页是「栈中栈」：A → B → …，先按主页栈退一层，退到没人了才退页面
        if (stack.last().page == MinePage.PROFILE_OTHER && profileStack.size > 1) {
            profileStack.pop()
            stack[stack.lastIndex] = MineEntry(
                MinePage.PROFILE_OTHER,
                otherId = profileStack.current.orEmpty(),
            )
            syncToolbar()
            return
        }
        if (stack.size <= 1) return
        if (stack.last().page == MinePage.PROFILE_OTHER) profileStack.clear()
        stack.removeAt(stack.lastIndex)
        uiState = uiState.copy(forward = false)
        backCallback.isEnabled = stack.size > 1
        applyEntry(stack.last())
        render()
        syncToolbar()
        onPageOpened(stack.last().page)
        // 第四十五轮（用户第 4 条）：退回「我的」根目录时重算互动角标。
        // refreshUnread 以前只挂在 onResume 上，而消息页是 Fragment 内的 Compose 二级页，
        // 返回时 onResume 不会再来 —— 于是「进过消息页」这件事根本没机会反映到角标上。
        if (stack.last().page == MinePage.ROOT) refreshUnread()
    }

    /** 一路退回「我的」根页（顶栏首页键 / 再点一次底栏「我的」共用） */
    private fun goRoot() {
        if (stack.size <= 1) return
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        uiState = uiState.copy(forward = false)
        backCallback.isEnabled = false
        applyEntry(stack.last())
        render()
        syncToolbar()
        // 与 goBack 一致：回到根目录就重算角标（消息页刚被看过的那些不再计入）
        refreshUnread()
    }

    /**
     * 打开和某个人 / 某个群的聊天页（「私信」列表点进来、或别人主页点「发私信」）。
     * 已经在聊天页时只换目标，不再压栈（换目标时**同一层的栈信息一起更新**，
     * 否则返回上一层时弹出的是旧 peer）。
     *
     * [title]：单聊是对方姓名、群聊是群名（会话列表直接给），空的话回落到本地缓存去查。
     */
    fun openImChat(peerId: String, title: String = "") {
        if (peerId.isBlank() || view == null) return
        // 从「别人的主页 → 发私信」直接进会话时也把通知权限问一次（列表页那条路在 ImScreens 里）
        (activity as? com.lanhang.miuix.ui.MainActivity)?.ensureNotifyPermission()
        val cached = title.takeIf { it.isNotBlank() }
            ?: UserInfoCache.get(peerId)?.name.orEmpty()
        val entry = MineEntry(MinePage.IM_CHAT, peerId = peerId, title = cached)
        uiState = uiState.copy(imPeerId = peerId, imPeerName = cached)
        // 聊天页的「上一级」是私信列表：从通知 / 外部入口直接进聊天时先垫一层私信列表，
        // 否则返回会一步落回「我的」根页（用户 2026-10-26 第四轮：返回只退一级）
        if (stack.size <= 1) stack.add(MineEntry(MinePage.IM))
        if (stack.last().page == MinePage.IM_CHAT) {
            // 已经在聊天页：换这一层的目标（不是新压一层），返回时不会弹回「上一个人」
            stack[stack.lastIndex] = entry
            render()
            syncToolbar()
        } else {
            open(entry)
        }
        // 名字没缓存过就补一次（工作圈那套 staffs/batch 缓存，同一个 id）；群聊不查人
        if (ImRepository.isGroup(peerId)) return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { UserInfoCache.resolve(listOf(peerId)) }
            val name = UserInfoCache.get(peerId)?.name.orEmpty()
            if (name.isNotBlank() && name != uiState.imPeerName) {
                uiState = uiState.copy(imPeerName = name)
                    .also { render() }
                // 补到的名字也写回栈里这一层，弹出再回来仍然是真名
                val top = stack.lastIndex
                if (stack[top].page == MinePage.IM_CHAT && stack[top].peerId == peerId) {
                    stack[top] = stack[top].copy(title = name)
                }
                syncToolbar()
            }
        }
    }

    /** 底栏再次点「我的」：直接回到根页（微信同款心智） */
    fun onTabSelected() {
        if (view == null) return
        if (stack.size > 1) {
            goRoot()
        } else {
            syncToolbar()
        }
        ensureStaff()
    }

    /**
     * 打开某个人的个人主页（通讯录里点「查看个人主页」）。
     *
     * 2026-10-26 用户反馈第 5 条：以前这里切到工作圈 Tab 去看主页，返回就落回「我的」根页。
     * 现在主页是本栈内的 `PROFILE_OTHER`，压栈顺序 ROOT → CONTACTS → PROFILE_OTHER，
     * 返回正好退回通讯录那级目录（`ContactsUiState` 记着下钻栈）。
     */
    fun openOtherProfile(userId: String) {
        if (userId.isBlank() || view == null) return
        // 自己 → 「我的主页」（相册 / 消息里点到自己的头像时走这条）
        if (userId == Session.userId) {
            if (stack.last().page == MinePage.PROFILE_OTHER) profileStack.clear()
            if (stack.last().page == MinePage.PROFILE) return
            open(MinePage.PROFILE)
            return
        }
        if (stack.last().page == MinePage.PROFILE_OTHER) {
            // 已经在主页层：往主页栈里压一层 → 自带推入转场，返回正好弹回上一个人
            profileStack.push(userId)
            stack[stack.lastIndex] = MineEntry(
                MinePage.PROFILE_OTHER,
                otherId = profileStack.current.orEmpty(),
            )
            syncToolbar()
            return
        }
        profileStack.reset(userId)
        open(MineEntry(MinePage.PROFILE_OTHER, otherId = userId))
    }

    /** 进入某个二级页时按需拉数据 */
    private fun onPageOpened(page: Int) {
        when (page) {
            MinePage.IDCARD -> if (uiState.idCard.token == null && !uiState.idCard.loading) refreshIdCard()
            MinePage.ALBUM, MinePage.PROFILE -> {
                ensureStaff()
                if (uiState.selfItems.isEmpty() && !uiState.selfLoading) loadSelf(reset = true)
            }
            MinePage.PROFILE_OTHER -> Unit   // 主页数据由 ProfileStackHost 自己 ensure
            else -> ensureStaff()
        }
    }

    // ============================================================ 我的资料 / 身份码

    /**
     * 我的资料：GET /staffs/{id}（官方 UserTask.STAFF_INFO），成功后落一份到 Session，
     * 我的页头像、身份码页头、个人主页都用这份。
     */
    /** 工作圈互动未读数（角标）；失败就保持旧值，不打扰用户 */
    private fun refreshUnread() {
        if (uiState.page != MinePage.ROOT) return
        viewLifecycleOwner.lifecycleScope.launch {
            val count = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // 第二十四轮第 4 条：角标也只看「我的帖子」上的互动，否则会出现
                    // 「角标 3、点进去什么都没有」（别人的帖子上的互动已经被过滤掉了）
                    // 第四十五轮：再减去「已经在消息页看过」的那些 id（用户第 4 条）
                    val ids = com.lanhang.miuix.moments.InteractionFilter.unreadIdsMine(
                        com.lanhang.miuix.net.MomentsApi.getUnreadMessages()
                    )
                    MessageReadStore.unseenCount(ids)
                }
            }.getOrDefault(0)
            if (count != uiState.unreadCount) uiState = uiState.copy(unreadCount = count)
        }
    }

    private fun ensureStaff() {
        if (staffLoaded || uiState.selfStaff != null) return
        val uid = Session.userId
        if (uid.isEmpty()) return
        staffLoaded = true
        lifecycleScope.launch {
            val staff = runCatching {
                withContext(Dispatchers.IO) { RceApi.getStaff(uid) }
            }.getOrNull()
            if (staff != null) {
                Session.staffNo = staff.staffNo.orEmpty()
                Session.department = cleanDepartment(staff.depart_name, staff.org, "")
                Session.avatarUrl = staff.portraitUrl.orEmpty()
                Session.email = staff.email.orEmpty()
                // 自己发过的动态要显示我的头像和姓名：顺手塞进工作圈的用户缓存
                val uid = staff.id
                if (!uid.isNullOrEmpty()) {
                    UserInfoCache.put(
                        StaffBrief(
                            id = uid,
                            name = staff.name,
                            portrait_url = staff.portraitUrl,
                            depart_name = staff.depart_name,
                            duty_name = null,
                            company_id = staff.company_id,
                        )
                    )
                }
                uiState = uiState.copy(selfStaff = staff)
            } else {
                staffLoaded = false
            }
            if (view != null) render()
        }
    }

    /**
     * 身份码：GET /apps/getUserQRCode，result 就是二维码里那串 token。
     * 官方版离开页面即失效，这里做成「进页面取一次 + 点二维码重取」。
     */
    private fun refreshIdCard() {
        uiState = uiState.copy(idCard = uiState.idCard.copy(loading = true, error = null))
        lifecycleScope.launch {
            val token = runCatching {
                withContext(Dispatchers.IO) { RceApi.getUserQrCode() }
            }.getOrNull()
            val ok = !token.isNullOrBlank()
            val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            uiState = uiState.copy(
                idCard = uiState.idCard.copy(
                    token = if (ok) token else uiState.idCard.token,
                    loading = false,
                    error = if (ok) null else getString(R.string.idcard_failed),
                    refreshedAt = if (ok) at else uiState.idCard.refreshedAt,
                ),
            )
        }
    }

    // ============================================================ 我发过的动态（相册 / 个人主页）

    private fun loadSelf(reset: Boolean) {
        if (uiState.selfLoading) return
        if (!reset && uiState.selfEnded) return
        uiState = uiState.copy(
            selfLoading = true,
            selfRefreshing = reset && uiState.selfItems.isNotEmpty(),
        )
        lifecycleScope.launch {
            try {
                val from = if (reset) null else selfFromFeedId
                val fetched = MomentFeedSource.load(Session.userId, from, MomentsApi.PAGE_SIZE)
                val merged = if (reset) fetched else uiState.selfItems + fetched
                if (fetched.isNotEmpty()) {
                    selfFromFeedId = fetched.last().feed.feed_id ?: selfFromFeedId
                }
                uiState = uiState.copy(
                    selfItems = merged,
                    selfLoading = false,
                    selfRefreshing = false,
                    selfEnded = fetched.size < MomentsApi.PAGE_SIZE,
                )
            } catch (e: Exception) {
                uiState = uiState.copy(selfLoading = false, selfRefreshing = false)
                toast(e.message)
            }
        }
    }

    private fun selfLike(item: MomentItem) {
        lifecycleScope.launch {
            try {
                val liked = MomentActions.toggleLike(item)
                if (liked) {
                    item.likes.add(LikeEntry(user_id = Session.userId, feed_id = item.feed.feed_id))
                } else {
                    item.likes.removeAll { it.user_id == Session.userId }
                }
                uiState = uiState.copy(selfRevision = uiState.selfRevision + 1)
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    private fun selfComment(item: MomentItem, replyTo: String?, text: String) {
        lifecycleScope.launch {
            try {
                MomentActions.comment(item, replyTo, text)
                reloadFeed(item)
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    private fun selfDeleteComment(item: MomentItem, comment: CommentEntry) {
        lifecycleScope.launch {
            try {
                MomentActions.deleteComment(item, comment)
                reloadFeed(item)
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    private fun selfDeletePost(item: MomentItem) {
        lifecycleScope.launch {
            try {
                MomentActions.deleteFeed(item)
                selfFromFeedId = selfFromFeedId
                uiState = uiState.copy(
                    selfItems = uiState.selfItems.filter { it !== item },
                    selfRevision = uiState.selfRevision + 1,
                )
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    /** 评论/点赞后回刷单条动态（官方同样用 GET /feed/{id} 取最新评论） */
    private fun reloadFeed(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        lifecycleScope.launch {
            val fresh = runCatching {
                withContext(Dispatchers.IO) { MomentsApi.getFeed(feedId) }
            }.getOrNull() ?: return@launch
            item.comments.clear()
            fresh.comments?.let { item.comments.addAll(it) }
            item.likes.clear()
            fresh.likes?.let { item.likes.addAll(it) }
            uiState = uiState.copy(selfRevision = uiState.selfRevision + 1)
        }
    }

    /**
     * 院系名的三档取值：depart_name → extra.org → 上次缓存的。
     * 学校的 extra.org 有时是纯数字组织编码（形如 2601063），那种不往界面上放。
     */
    /** 院系：`机电学院-2026-2603071` → `机电学院`，纯数字组织编码直接丢掉（见 cleanOrgName） */
    private fun cleanDepartment(vararg candidates: String?): String =
        candidates.firstNotNullOfOrNull { it.cleanOrgName() }.orEmpty()

    private fun toast(message: String?) {
        if (message.isNullOrBlank()) return
        if (view == null) return
        Toast.makeText(AppContext.instance, message, Toast.LENGTH_SHORT).show()
    }

    private fun currentThemeLabel(): String =
        themeOptions.firstOrNull { it.first == Session.themeMode }
            ?.let { getString(it.second) }
            ?: getString(R.string.settings_theme_system)

    private fun showThemeDialog() {
        val labels = themeOptions.map { getString(it.second) }
        val checked = themeOptions.indexOfFirst { it.first == Session.themeMode }.coerceAtLeast(0)
        showMiuixPicker(
            requireActivity(),
            title = getString(R.string.settings_theme_title),
            items = labels,
            selectedIndex = checked,
            cancelText = getString(R.string.schedule_export_cancel),
            onPick = { which ->
                val mode = themeOptions.getOrNull(which)?.first ?: return@showMiuixPicker
                if (mode != Session.themeMode) {
                    Session.themeMode = mode
                    applyTheme(mode)
                }
            },
        )
    }

    private fun applyTheme(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        context?.applicationContext?.let { ScheduleWidgetUpdater.updateAll(it) }
    }

    private fun currentLanguageLabel(): String =
        getString(AppLanguage.labelRes(Session.language))

    private fun showLanguageDialog() {
        val labels = AppLanguage.options.map { getString(AppLanguage.labelRes(it)) }
        val checked = AppLanguage.options.indexOf(Session.language).coerceAtLeast(0)
        showMiuixPicker(
            requireActivity(),
            title = getString(R.string.settings_language),
            items = labels,
            selectedIndex = checked,
            cancelText = getString(R.string.common_cancel),
            onPick = { which ->
                val mode = AppLanguage.options.getOrNull(which) ?: return@showMiuixPicker
                if (mode != Session.language) {
                    Session.language = mode
                    // 见 LocaleHelper：不再用系统的 per-app locale（它会系统级 relaunch，
                    // 过程中有 0.4s 纯黑帧）。改完自己 recreate()，界面立刻换语言且不黑屏
                    AppLanguage.apply(mode)
                    context?.applicationContext?.let { ScheduleWidgetUpdater.updateAll(it) }
                    requireActivity().recreate()
                }
            },
        )
    }

    private fun showExportSchedule() {
        val data = ScheduleRepository.loadCached()?.data
        if (data == null) {
            Toast.makeText(AppContext.instance, R.string.schedule_export_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val plan = runCatching { ScheduleExporter.plan(data) }.getOrNull()
        if (plan == null) {
            Toast.makeText(AppContext.instance, R.string.schedule_export_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val missing = missingWeeks(data, plan)
        if (missing > 0) {
            showMiuixConfirm(
                requireActivity(),
                title = getString(R.string.schedule_export_title),
                message = getString(R.string.schedule_export_sync_needed, missing),
                confirmText = getString(R.string.schedule_export_start_sync),
                dismissText = getString(R.string.schedule_export_cancel),
                onConfirm = { startExport(data, plan) },
            )
        } else {
            startExport(data, plan)
        }
    }

    private fun startExport(data: ScheduleData, plan: ScheduleExporter.Plan) {
        if (exportJob?.isActive == true) return
        val missing = missingWeeks(data, plan)
        val progress = if (missing > 0) createExportProgress() else null
        val baseIsCurrent = weekInBase(data, plan.currentWeek)
        exportJob = lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    val weeks = LinkedHashMap<Int, ScheduleData>()
                    val total = plan.weeks.count()
                    var done = 0
                    for (week in plan.weeks) {
                        ensureActive()
                        val weekData = if (week == plan.currentWeek && baseIsCurrent) {
                            data
                        } else {
                            cachedWeek(week, data.termId)
                                ?: ScheduleRepository.refresh(week, updateWidget = false).data
                        } ?: throw IllegalStateException(getString(R.string.settings_week_empty, week))
                        weeks[week] = weekData
                        done++
                        if (progress != null) {
                            withContext(Dispatchers.Main) {
                                progress.setText(
                                    getString(
                                        R.string.schedule_export_syncing,
                                        week,
                                        done,
                                        total
                                    )
                                )
                            }
                        }
                    }
                    ensureActive()
                    ScheduleExporter.build(AppContext.instance, data, plan, weeks)
                }
                progress?.dialog?.dismiss()
                if (payload == null) {
                    Toast.makeText(
                        AppContext.instance,
                        R.string.schedule_export_no_data,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showExportOptions(payload)
                }
            } catch (e: CancellationException) {
                progress?.dialog?.dismiss()
            } catch (e: Exception) {
                progress?.dialog?.dismiss()
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.schedule_export_failed)
                Toast.makeText(AppContext.instance, message, Toast.LENGTH_LONG).show()
            } finally {
                exportJob = null
            }
        }
    }

    private fun showExportOptions(payload: ScheduleExporter.ExportPayload) {
        val labels = listOf(
            getString(R.string.schedule_export_ics),
            getString(R.string.schedule_export_csv),
            getString(R.string.schedule_export_share)
        )
        showMiuixDialog(
            requireActivity(),
            MiuixDialogSpec(
                title = getString(R.string.schedule_export_title),
                summary = getString(
                    R.string.schedule_export_message,
                    payload.courseCount,
                    payload.firstWeek,
                    payload.lastWeek
                ),
                items = labels,
                onItemClick = { index ->
                    when (index) {
                        0 -> {
                            pendingExport = payload
                            createIcsDocument.launch("${payload.baseFileName}.ics")
                        }
                        1 -> {
                            pendingExport = payload
                            createCsvDocument.launch("${payload.baseFileName}.csv")
                        }
                        2 -> shareSchedule(payload.text)
                    }
                },
                actions = listOf(getString(R.string.schedule_export_cancel) to {}),
            ),
        )
    }

    private fun createExportProgress(): MiuixProgressDialog = showMiuixProgress(
        requireActivity(),
        title = getString(R.string.schedule_export_title),
        initialText = getString(R.string.schedule_export_preparing),
        cancelText = getString(R.string.schedule_export_cancel),
        onCancel = { exportJob?.cancel() },
    )

    private fun missingWeeks(data: ScheduleData, plan: ScheduleExporter.Plan): Int {
        val baseIsCurrent = weekInBase(data, plan.currentWeek)
        return plan.weeks.count { week ->
            if (week == plan.currentWeek && baseIsCurrent) {
                false
            } else {
                cachedWeek(week, data.termId) == null
            }
        }
    }

    private fun weekInBase(data: ScheduleData, week: Int): Boolean {
        val baseWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull()
        return baseWeek == null || baseWeek == week
    }

    private fun cachedWeek(week: Int, termId: String?): ScheduleData? {
        val data = ScheduleRepository.loadCachedWeek(week)?.data ?: return null
        if (!termId.isNullOrBlank() && data.termId != termId) return null
        return data
    }

    private fun saveDocument(uri: Uri, bytes: ByteArray, displayName: String) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val output = ctx.contentResolver.openOutputStream(uri)
                        ?: error("open output stream failed")
                    output.use { stream ->
                        stream.write(bytes)
                    }
                }.isSuccess
            }
            val message = if (success) {
                ctx.getString(R.string.schedule_export_saved, displayName)
            } else {
                ctx.getString(R.string.schedule_export_failed)
            }
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareSchedule(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.schedule_export_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.schedule_export_chooser)))
    }

    /** 退出登录（原顶栏「更多」菜单项，2026-10-24 用户要求删掉三点菜单后移到这里） */
    private fun confirmLogout() {
        showMiuixConfirm(
            requireActivity(),
            title = getString(R.string.action_logout),
            message = getString(R.string.settings_logout_confirm),
            confirmText = getString(R.string.action_logout),
            dismissText = getString(R.string.common_cancel),
            onConfirm = { (activity as? com.lanhang.miuix.ui.MainActivity)?.logout() },
        )
    }

    private fun showDiagnostics() {
        val report = Diagnostics.report(AppContext.instance)
        showMiuixDialog(
            requireActivity(),
            MiuixDialogSpec(
                title = getString(R.string.settings_diagnostics),
                summary = report,
                scrollableSummary = true,
                actions = listOf(
                    getString(R.string.common_close) to {},
                    getString(R.string.common_share) to {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_diagnostics_subject))
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        startActivity(
                            Intent.createChooser(send, getString(R.string.settings_diagnostics_chooser))
                        )
                    },
                ),
            ),
        )
    }

    companion object {
        private const val KEY_STACK = "mine_page_stack"
private const val KEY_STACK_PEER = "mine_page_stack_peer"
private const val KEY_STACK_TITLE = "mine_page_stack_title"
private const val KEY_STACK_OTHER = "mine_page_stack_other"
    /** 个人主页栈（A → B → …）随页面栈一起存 */
    private const val KEY_PROFILE_STACK = "mine_profile_stack"
    }
}
