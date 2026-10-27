package com.lanhang.miuix.mine

import com.lanhang.miuix.ui.miuix.pressableRow
import com.lanhang.miuix.ui.miuix.accentColor
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.im.ImChatScreen
import com.lanhang.miuix.im.ImConversationsScreen
import com.lanhang.miuix.moments.CommentEntry
import com.lanhang.miuix.moments.MomentItem
import com.lanhang.miuix.moments.MomentsScreen
import com.lanhang.miuix.net.StaffInfo
import com.lanhang.miuix.profile.ProfileStackHost
import com.lanhang.miuix.profile.ProfileStackState
import com.lanhang.miuix.profile.ProfileStore
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.LanhangIcons
import com.lanhang.miuix.ui.miuix.MiuiNavStack
import com.lanhang.miuix.ui.miuix.rememberPageListStates
import com.lanhang.miuix.ui.shell.ReportListScroll
import com.lanhang.miuix.ui.shell.ScrollToTop
import com.lanhang.miuix.ui.shell.animateScrollToTopSlow
import com.lanhang.miuix.ui.shell.ShellState
import com.lanhang.miuix.grades.GradesScreen
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image

/**
 * 「我的」这一栈里的页面编号。
 *
 * 层级只用来决定推入/返回的方向：[depth] 变大 = 往深处走（新页从右边滑进来），
 * 变小 = 返回（当前页往右滑出去）。设置 > 外观/课表工具/关于 是第三层。
 */
object MinePage {
    const val ROOT = 0
    /** 成绩（2026-10-26 第六轮从底栏挪进「我的」；底栏那个位置换成了「工作」） */
    const val GRADES = 1
    const val IDCARD = 2
    const val ALBUM = 3
    const val PROFILE = 4
    const val SETTINGS = 5
    const val APPEARANCE = 6
    const val TOOLS = 7
    const val ABOUT = 8
    const val CONTACTS = 9
    const val MESSAGES = 10
    const val IM = 11
    const val IM_CHAT = 12

    /**
     * 通讯录里点「查看个人主页」（2026-10-26 新增）。
     *
     * 以前这里直接切到工作圈 Tab 去看那个人的主页，于是「通讯录 → 某人主页 → 返回」
     * 会跳回「我的」根页（用户反馈的第 5 条）。现在主页是本栈里的第三层：
     * ROOT → CONTACTS → PROFILE_OTHER，返回就是退回通讯录那一级目录。
     */
    const val PROFILE_OTHER = 13

    /** 设置 → 工作圈 → 工作圈过滤（第三十一轮） */
    const val MOMENTS_FILTER = 14

    /** 设置 → 通用 → 上课提醒（第三十一轮） */
    const val CLASS_REMINDER = 15

    fun depth(page: Int): Int = when (page) {
        ROOT -> 0
        APPEARANCE, TOOLS, ABOUT, IM_CHAT, PROFILE_OTHER, MOMENTS_FILTER, CLASS_REMINDER -> 2
        else -> 1
    }

    /** 页面对应的顶栏标题（返回键由宿主 MainActivity 挂） */
    fun titleRes(page: Int): Int = when (page) {
        GRADES -> R.string.tab_grades
        IDCARD -> R.string.idcard_title
        ALBUM -> R.string.mine_album
        PROFILE -> R.string.profile_title
        SETTINGS -> R.string.mine_settings
        APPEARANCE -> R.string.settings_appearance
        TOOLS -> R.string.settings_schedule_tools
        ABOUT -> R.string.mine_about
        CONTACTS -> R.string.mine_contacts
        MESSAGES -> R.string.mine_messages
        PROFILE_OTHER -> R.string.mine_profile
        MOMENTS_FILTER -> R.string.moments_filter_title
        CLASS_REMINDER -> R.string.class_reminder_title
        IM, IM_CHAT -> R.string.mine_im
        else -> R.string.tab_mine
    }
}

/** 「我的」整栈的渲染状态（Fragment 侧 [MineFragment] 装配，Compose 侧只读） */
data class MineUiState(
    val page: Int = MinePage.ROOT,
    /**
     * 页面过渡方向：true = 往深一层推入（新页从右进），false = 返回上一级（当前页往右出）。
     *
     * 2026-10-26 第四轮：原来靠 [MinePage.depth] 猜方向，一旦「同一层之间跳」
     * （聊天页 → 某人主页，两者 depth 都是 2）就会把**返回**也演成**推入**。
     * 现在方向由 [MineFragment] 的页面栈直接决定，动画永远和真实的前进/后退一致。
     */
    val forward: Boolean = true,
    // 我的资料
    val name: String = "",
    val staffNo: String = "",
    val department: String = "",
    val avatarUrl: String = "",
    // 设置页需要的几行值
    val account: String = "--",
    val version: String = "--",
    val proto: String = "--",
    val themeLabel: String = "",
    val languageLabel: String = "",
    // 身份码
    val idCard: IdCardUiState = IdCardUiState(),
    // 我自己的资料 + 我发过的动态（相册与个人主页共用这一份）
    val selfStaff: StaffInfo? = null,
    val selfItems: List<MomentItem> = emptyList(),
    val selfLoading: Boolean = false,
    val selfRefreshing: Boolean = false,
    val selfEnded: Boolean = false,
    val selfRevision: Int = 0,
    /** 工作圈互动消息未读数（「消息」行右上角的角标） */
    val unreadCount: Int = 0,
    // 私信（聊天页的对方）
    val imPeerId: String = "",
    val imPeerName: String = "",
    // 通讯录里点进来的人的主页（PROFILE_OTHER）
    val otherUserId: String = "",
    val otherStaff: StaffInfo? = null,
    val otherItems: List<MomentItem> = emptyList(),
    val otherLoading: Boolean = false,
    val otherRefreshing: Boolean = false,
    val otherEnded: Boolean = false,
    val otherRevision: Int = 0,
)

/**
 * 「我的」页（底栏第 4 个 Tab）。
 *
 * 结构：头像资料卡 + 工作 / 身份码 / 相册 / 设置 / 关于 五行入口，
 * 身份码、相册、个人主页、设置下的三页都是**本栈内的二级页**，
 * 统一走 [miuixPushTransform]（只有一张纸在动），返回键由 [MineFragment] 拦。
 */
@Composable
fun MineScreen(
    state: MineUiState,
    /** 通讯录的下钻栈 + 组织树：由 [MineFragment] 持有，顶栏返回键才能「先退一级目录」 */
    contactsState: ContactsUiState,
    onOpenGrades: () -> Unit,
    onOpenIdCard: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenAbout: () -> Unit,
    /** 设置 → 工作圈 → 工作圈过滤 */
    onOpenMomentsFilter: () -> Unit,
    /** 设置 → 通用 → 上课提醒 */
    onOpenClassReminder: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenMessages: () -> Unit,
    /** 「私信」入口 → 会话列表 */
    onOpenIm: () -> Unit,
    /** 会话列表 / 个人主页 → 和这个 userId 的聊天页（第二个参数是顶栏标题：单聊=姓名，群聊=群名） */
    onOpenImChat: (String, String) -> Unit,
    /** 通讯录里点「查看个人主页」→ 切到工作圈看这个人的主页 */
    onOpenOtherProfile: (String) -> Unit,
    /** 消息里点某条互动 → 切到工作圈 */
    onOpenMoments: (String) -> Unit,
    onTheme: () -> Unit,
    onLanguage: () -> Unit,
    onExport: () -> Unit,
    onDebugSchedule: () -> Unit,
    onDiagnostics: () -> Unit,
    onLogout: () -> Unit,
    onRefreshIdCard: () -> Unit,
    onRefreshSelf: () -> Unit,
    onLoadMoreSelf: () -> Unit,
    onLike: (MomentItem) -> Unit,
    onPostComment: (MomentItem, String?, String) -> Unit,
    onDeleteComment: (MomentItem, CommentEntry) -> Unit,
    onDeletePost: (MomentItem) -> Unit,
    onImage: (List<String>, Int, android.graphics.Rect?) -> Unit,
    // 别人的个人主页：整块交给共享实现（profile/ProfileHost.kt，与工作圈同一份代码）
    profileStack: ProfileStackState,
    profileStore: ProfileStore,
    /** 主页顶栏标题（「X 的个人主页」）→ 由 Fragment 去改 Toolbar */
    onProfileTitle: (String) -> Unit,
    /** 主页上的「发私信」→ 和这个人聊 */
    onMessageOther: (String, String) -> Unit,
) {
    // 通讯录的下钻栈由 [MineFragment] 持有（2026-10-26 第二轮：顶栏返回键要能逐级退回，
    // 所以状态必须放得比本 Composable 更高一层）

    // 每个页面各自一份列表滚动位置，**挂在这一层 AnimatedContent 之外**。
    // 2026-10-26 第九轮（用户第 3 条）：从页面底部进二级菜单再返回，一级页会跳回顶部——
    // 因为 AnimatedContent 只组合当前那一页，离开时整棵子树被销毁，页面里的
    // rememberLazyListState() 跟着没了。key 用 MinePage 编号，同一页每次进来都取回自己那份。
    val pageLists = rememberPageListStates("mine")

    // 统一转场：几何/时长/曲线全在 ui/miuix/NavPush.kt 的 NavMotion 里（第十轮重写）
    MiuiNavStack(
        key = state.page,
        forward = state.forward,
        pageBackground = colorResource(R.color.page_bg),
    ) { pageKey: Any ->
        val page = pageKey as Int
        // 每页自带不透明底色：推入/退出时两页会重叠，透明根会透出下面那一页
        // 列表型页面用「页面底色」（#F7F7F7 / 暗色 #000000）铺底，白卡片才能分出组；
        // 动态流（相册、个人主页）沿用 Miuix 的 background，和工作圈那页保持一致。
        val isFeedPage = page == MinePage.ALBUM || page == MinePage.PROFILE ||
            page == MinePage.PROFILE_OTHER
        val pageBg = if (isFeedPage) {
            MiuixTheme.colorScheme.background
        } else {
            colorResource(R.color.page_bg)
        }
        // 底色必须由页面自己铺（两页重叠时不能透出下面那一页）；
        // 圆角裁剪交给 MiuiNavStack，只在转场过程中裁。
        val pageModifier = Modifier
            .fillMaxSize()
            .background(pageBg)

        when (page) {
            MinePage.ALBUM -> Box(pageModifier) {
                MomentsScreen(
                    items = state.selfItems,
                    loading = state.selfLoading,
                    refreshing = state.selfRefreshing,
                    ended = state.selfEnded,
                    emptyText = stringResource(R.string.album_empty),
                    revision = state.selfRevision,
                    onRefresh = onRefreshSelf,
                    onLoadMore = onLoadMoreSelf,
                    onLike = onLike,
                    onPostComment = onPostComment,
                    onDeleteComment = onDeleteComment,
                    onDeletePost = onDeletePost,
                    onImage = onImage,
                    onOpenPublish = {},
                    // 相册里虽然都是我发的动态，但评论/点赞的人可能是别人：
                    // 点谁的头像就进谁的主页（自己 → 「我的主页」，别人 → 他人主页）
                    onOpenProfile = { id ->
                        if (id.isBlank() || id == Session.userId) onOpenProfile()
                        else onOpenOtherProfile(id)
                    },
                    showFab = false,
                    listState = pageLists.of(MinePage.ALBUM),
                )
            }

            MinePage.PROFILE -> Box(pageModifier) {
                UserProfilePage(
                    staff = state.selfStaff,
                    userId = "",
                    isSelf = true,
                    items = state.selfItems,
                    loading = state.selfLoading,
                    refreshing = state.selfRefreshing,
                    ended = state.selfEnded,
                    revision = state.selfRevision,
                    emptyText = stringResource(R.string.profile_empty_self),
                    onRefresh = onRefreshSelf,
                    onLoadMore = onLoadMoreSelf,
                    onLike = onLike,
                    onPostComment = onPostComment,
                    onDeleteComment = onDeleteComment,
                    onDeletePost = onDeletePost,
                    onImage = onImage,
                    onOpenProfile = { id ->
                        if (id.isNotBlank() && id != Session.userId) onOpenOtherProfile(id)
                    },
                    listState = pageLists.of(MinePage.PROFILE),
                )
            }

            MinePage.IDCARD -> Box(pageModifier) {
                IdCardScreen(
                    state = state.idCard,
                    onRefresh = onRefreshIdCard,
                    listState = pageLists.of(MinePage.IDCARD),
                )
            }

            // 通讯录：组织架构树 → 部门 → 成员资料卡（数据走主 API，页面自己取数）
            MinePage.CONTACTS -> Box(pageModifier) {
                ContactsScreen(
                    modifier = Modifier.fillMaxSize(),
                    state = contactsState,
                    onOpenProfile = onOpenOtherProfile,
                    listStates = pageLists,
                )
            }

            // 别人的个人主页：通讯录 / 消息 / 私信 / 相册 点进来都是这一份实现
            // （与工作圈相同：profile/ProfileHost.kt）
            MinePage.PROFILE_OTHER -> Box(pageModifier) {
                ProfileStackHost(
                    stack = profileStack,
                    store = profileStore,
                    onTitle = onProfileTitle,
                    onOpenProfile = onOpenOtherProfile,
                    onMessage = onMessageOther,
                    onImage = onImage,
                    emptyText = stringResource(R.string.profile_empty_other),
                )
            }

            // 消息：工作圈互动消息（评论 / 赞）
            MinePage.MESSAGES -> Box(pageModifier) {
                MessagesScreen(
                    modifier = Modifier.fillMaxSize(),
                    listState = pageLists.of(MinePage.MESSAGES),
                    onOpenMoments = onOpenMoments,
                    onOpenProfile = onOpenOtherProfile,
                )
            }

            // 私信：融云单聊 / 群聊的会话列表（数据来自 ImRepository，界面全是 Miuix）
            MinePage.IM -> Box(pageModifier) {
                ImConversationsScreen(
                    modifier = Modifier.fillMaxSize(),
                    listState = pageLists.of(MinePage.IM),
                    onOpenChat = { conversation ->
                        onOpenImChat(conversation.peerId, conversation.title)
                    },
                    onOpenProfile = onOpenOtherProfile,
                )
            }

            // 私信：和某个人的聊天页（点头像/姓名进主页：别人 → 他人主页，自己 → 我的主页）
            MinePage.IM_CHAT -> Box(pageModifier) {
                ImChatScreen(
                    peerId = state.imPeerId,
                    peerName = state.imPeerName,
                    modifier = Modifier.fillMaxSize(),
                    listState = pageLists.of(MinePage.IM_CHAT),
                    onOpenProfile = onOpenOtherProfile,
                    onOpenSelf = onOpenProfile,
                )
            }

            // 成绩：2026-10-26 第六轮从底栏降到这里（底栏那个位置让给「工作」）
            MinePage.GRADES -> Box(pageModifier) { GradesScreen(listState = pageLists.of(MinePage.GRADES)) }

            // 工作圈过滤 / 上课提醒：都是自带 LazyColumn 的设置页，整页交给它们
            // （第三十一轮；滚动状态仍按 MinePage 编号复用自己的那份）
            MinePage.MOMENTS_FILTER -> Box(pageModifier) {
                com.lanhang.miuix.moments.MomentsFilterScreen(
                    listState = pageLists.of(MinePage.MOMENTS_FILTER),
                )
            }

            MinePage.CLASS_REMINDER -> Box(pageModifier) {
                com.lanhang.miuix.schedule.ClassReminderScreen(
                    listState = pageLists.of(MinePage.CLASS_REMINDER),
                )
            }

            else -> {
                val mineListState = pageLists.of(page)
                ReportListScroll(mineListState, ShellState.TAB_MINE)
                // 双击底栏「我的」回到顶部
                LaunchedEffect(ScrollToTop.tick) {
                    if (ScrollToTop.tab == ShellState.TAB_MINE) mineListState.animateScrollToTopSlow()
                }
                LazyColumn(
                    modifier = pageModifier,
                    state = mineListState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                ) {
                    when (page) {
                        MinePage.SETTINGS -> settingsDirectoryItems(
                            state = state,
                            onOpenAppearance = onOpenAppearance,
                            onOpenTools = onOpenTools,
                            onOpenMomentsFilter = onOpenMomentsFilter,
                            onOpenClassReminder = onOpenClassReminder,
                            onLogout = onLogout,
                        )

                        MinePage.APPEARANCE -> settingsAppearanceItems(
                            state = state,
                            onTheme = onTheme,
                            onLanguage = onLanguage,
                        )

                        MinePage.TOOLS -> settingsToolsItems(
                            onExport = onExport,
                            onDebugSchedule = onDebugSchedule,
                        )

                        MinePage.ABOUT -> settingsAboutItems(state = state, onDiagnostics = onDiagnostics)

                        else -> mineRootItems(
                            state = state,
                            onOpenIm = onOpenIm,
                            onOpenProfile = onOpenProfile,
                            onOpenGrades = onOpenGrades,
                            onOpenIdCard = onOpenIdCard,
                            onOpenAlbum = onOpenAlbum,
                            onOpenSettings = onOpenSettings,
                            onOpenAbout = onOpenAbout,
                            onOpenContacts = onOpenContacts,
                            onOpenMessages = onOpenMessages,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 我的首页

private fun androidx.compose.foundation.lazy.LazyListScope.mineRootItems(
    state: MineUiState,
    onOpenProfile: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenIdCard: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenIm: () -> Unit,
) {
    item("header") {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressableRow(onClick = { onOpenProfile() }, corner = 16.dp)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanhangAvatar(url = state.avatarUrl, name = state.name, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.name.ifBlank { stringResource(R.string.mine_guest) },
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.staffNo.isBlank()) {
                            stringResource(R.string.mine_profile)
                        } else {
                            stringResource(R.string.profile_label_staff_no) + " " + state.staffNo
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (state.department.isNotBlank()) {
                        Text(
                            text = state.department,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    // 2026-10-26 第六轮：底栏的「成绩」降到这里（底栏那个位置换成「工作」办事大厅）
    item("grades") {
        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            MineRow(
                icon = MiuixIcons.Notes,
                tint = accentColor(Color(0xFF3B7DD8)),
                title = stringResource(R.string.tab_grades),
                summary = stringResource(R.string.mine_grades_summary),
                onClick = onOpenGrades,
            )
        }
    }

    item("contactsMessages") {
        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            MineRow(
                icon = LanhangIcons.Contacts,
                tint = accentColor(Color(0xFF3F8CE8)),
                title = stringResource(R.string.mine_contacts),
                summary = stringResource(R.string.mine_contacts_summary),
                onClick = onOpenContacts,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 62.dp, end = 16.dp))
            MineRow(
                icon = LanhangIcons.Message,
                tint = accentColor(Color(0xFFE2564F)),
                title = stringResource(R.string.mine_messages),
                summary = stringResource(R.string.mine_messages_summary),
                badge = state.unreadCount,
                onClick = onOpenMessages,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 62.dp, end = 16.dp))
            MineRow(
                icon = LanhangIcons.Bolt,
                tint = accentColor(Color(0xFF3E9C63)),
                title = stringResource(R.string.mine_im),
                summary = stringResource(R.string.mine_im_summary),
                onClick = onOpenIm,
            )
        }
    }

    item("idAlbum") {
        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            MineRow(
                icon = MiuixIcons.Scan,
                tint = accentColor(Color(0xFF8A63D2)),
                title = stringResource(R.string.mine_idcard),
                summary = stringResource(R.string.mine_idcard_summary),
                onClick = onOpenIdCard,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 62.dp, end = 16.dp))
            MineRow(
                icon = MiuixIcons.Album,
                tint = accentColor(Color(0xFFE8894A)),
                title = stringResource(R.string.mine_album),
                summary = stringResource(R.string.mine_album_summary),
                onClick = onOpenAlbum,
            )
        }
    }

    item("settingsAbout") {
        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            MineRow(
                icon = MiuixIcons.Settings,
                tint = accentColor(Color(0xFF6E7B8B)),
                title = stringResource(R.string.mine_settings),
                summary = null,
                onClick = onOpenSettings,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 62.dp, end = 16.dp))
            MineRow(
                icon = MiuixIcons.Info,
                tint = accentColor(Color(0xFF2FA39A)),
                title = stringResource(R.string.mine_about),
                summary = stringResource(R.string.settings_about_version, state.version),
                onClick = onOpenAbout,
            )
        }
    }

    item("footer") {
        Text(
            text = stringResource(R.string.settings_footer),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** 一行入口：彩色图标片 + 标题（可选副标题）+ 右箭头，行间用缩进分隔线 */
@Composable
private fun MineRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    summary: String?,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (badge > 0) {
            Text(
                text = if (badge > 99) "99+" else badge.toString(),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---------------------------------------------------------------- 设置（二级栈）

private fun androidx.compose.foundation.lazy.LazyListScope.settingsDirectoryItems(
    state: MineUiState,
    onOpenAppearance: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenMomentsFilter: () -> Unit,
    onOpenClassReminder: () -> Unit,
    onLogout: () -> Unit,
) {
    // 第二十四轮：**设置里不再放「关于」入口**（用户要求），关于只在「我的」首页有入口；
    // 这一页的每一行都走同一个 [SettingRow]，行高 / 字号 / 分隔线完全一致。
    item("directory") {
        SmallTitle(stringResource(R.string.settings_general_section))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_appearance),
                summary = state.themeLabel,
                onClick = onOpenAppearance,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.settings_schedule_tools),
                summary = stringResource(R.string.settings_tools_summary),
                onClick = onOpenTools,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // 上课提醒（第三十一轮移植）：副标题实时说「已开启 · 15 分钟前」或「缺少通知权限」
            SettingRow(
                title = stringResource(R.string.class_reminder_title),
                summary = com.lanhang.miuix.schedule.ClassReminderStore.summary(
                    androidx.compose.ui.platform.LocalContext.current,
                ),
                onClick = onOpenClassReminder,
            )
        }
    }
    item("directoryMoments") {
        SmallTitle(stringResource(R.string.settings_moments_section))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.moments_filter_title),
                summary = com.lanhang.miuix.moments.MomentsFilterStore.summary(
                    androidx.compose.ui.platform.LocalContext.current,
                ),
                onClick = onOpenMomentsFilter,
            )
        }
    }
    item("directoryAccount") {
        SmallTitle(stringResource(R.string.settings_account_section))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(title = stringResource(R.string.settings_account), value = state.account)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.action_logout),
                summary = stringResource(R.string.settings_logout_summary),
                onClick = onLogout,
            )
        }
    }
    item("directoryFooter") {
        SettingsFooter()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.settingsAppearanceItems(
    state: MineUiState,
    onTheme: () -> Unit,
    onLanguage: () -> Unit,
) {
    item("appearance") {
        SmallTitle(stringResource(R.string.settings_appearance))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_theme_title),
                summary = state.themeLabel,
                onClick = onTheme,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.settings_language),
                summary = state.languageLabel,
                onClick = onLanguage,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.settingsToolsItems(
    onExport: () -> Unit,
    onDebugSchedule: () -> Unit,
) {
    item("tools") {
        SmallTitle(stringResource(R.string.settings_schedule_tools))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_export_schedule),
                summary = stringResource(R.string.settings_export_schedule_subtitle),
                onClick = onExport,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.menu_debug_schedule),
                summary = stringResource(R.string.settings_debug_summary),
                onClick = onDebugSchedule,
            )
        }
    }
}

/** 本仓库（Miuix 改版后的主仓库） */
private const val OPEN_SOURCE_URL = "https://github.com/Nuclear-Potatoes/lanhang-lite-miuix"

/** 上游原项目：只作致谢，功能与界面已由本项目重写 */
private const val UPSTREAM_URL = "https://github.com/Micheal-Reber/lanhang-lite"

/** 用浏览器打开外链（失败就算了，不弹崩溃） */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.settingsAboutItems(
    state: MineUiState,
    onDiagnostics: () -> Unit,
) {
    // 第二十四轮：参考 nexio 课程表的「关于」页重排 ——
    // 顶部一块居中的图标 / 名字 / 版本号当封面，下面才是条目；不再是一进来就一列清单。
    item("aboutHero") {
        AboutHero(version = state.version)
    }
    item("aboutApp") {
        SmallTitle(stringResource(R.string.mine_about_section_app))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_app_version),
                value = state.version,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.settings_proto),
                value = state.proto,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.settings_account),
                value = state.account,
            )
        }
    }
    item("aboutDiagnostics") {
        SmallTitle(stringResource(R.string.mine_support_section))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_diagnostics),
                summary = stringResource(R.string.settings_diagnostics_subtitle),
                onClick = onDiagnostics,
            )
        }
    }
    item("aboutOpenSource") {
        val context = androidx.compose.ui.platform.LocalContext.current
        SmallTitle(stringResource(R.string.mine_open_source_section))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.mine_open_source),
                summary = stringResource(R.string.mine_open_source_summary),
                onClick = { openUrl(context, OPEN_SOURCE_URL) },
            )
        }
    }
    item("aboutStack") {
        val context = androidx.compose.ui.platform.LocalContext.current
        SmallTitle(stringResource(R.string.mine_credits_section))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            // 2026-10-27 第二十七轮（用户：「把关于中原项目 lanhanglite 移到致谢一栏中」）：
            // 原项目只作致谢，不再和「开源地址」挤在同一张卡里
            SettingRow(
                title = stringResource(R.string.mine_credits_upstream),
                summary = stringResource(R.string.mine_credits_upstream_summary),
                onClick = { openUrl(context, UPSTREAM_URL) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.mine_credits_miuix),
                summary = stringResource(R.string.mine_credits_miuix_summary),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.mine_credits_coil),
                summary = stringResource(R.string.mine_credits_coil_summary),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingRow(
                title = stringResource(R.string.mine_credits_http),
                summary = stringResource(R.string.mine_credits_http_summary),
            )
        }
    }
    item("footer") {
        SettingsFooter()
    }
}

/** 关于页封面：应用图标 + 名字 + 版本 + 一句话说明（居中） */
@Composable
private fun AboutHero(version: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 自适应图标走系统 ApplicationInfo，拿到的就是桌面上那枚（不用另外做一张图）
    val icon = remember(context) {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(168, 168)
                .asImageBitmap()
        }.getOrNull()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(22.dp)),
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MiuixTheme.textStyles.title1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_about_version, version),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_about_tagline),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun SettingsFooter() {
    Text(
        text = stringResource(R.string.settings_footer),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, start = 24.dp, end = 24.dp),
    )
}

/**
 * 设置 / 关于页里的一行（第二十四轮：**行高与字号统一**）。
 *
 * 之前这一页混用了三种行：Miuix 库的 `ArrowPreference`（内边距由库里定）、自定义
 * `ValueRow`、还有带彩色图标的 `MineRow`，三套 padding 叠在一页上，行高就参差不齐。
 * 现在统一到这一个组件：上下 15dp、标题 body1、副标题 footnote1、右侧值 body2、
 * 可点的行尾部统一一枚 20dp 尖括号。带副标题的行天然更高，那是两行文字撑出来的，
 * 不是内边距不一样。
 */
@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.pressableRow(onClick = onClick, corner = 16.dp)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
