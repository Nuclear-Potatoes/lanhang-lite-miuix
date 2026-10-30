package com.lanhang.miuix.moments

import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import com.lanhang.miuix.R
import com.lanhang.miuix.net.ApiException
import com.lanhang.miuix.mine.UserProfilePage
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.net.MomentsApi
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.net.cleanOrgName
import com.lanhang.miuix.net.StaffBrief
import com.lanhang.miuix.net.StaffInfo
import com.lanhang.miuix.profile.ProfileStackHost
import com.lanhang.miuix.profile.ProfileStackState
import com.lanhang.miuix.profile.ProfileStore
import com.lanhang.miuix.profile.profileTitle
import com.lanhang.miuix.ui.miuix.MiuiNavStack
import com.lanhang.miuix.ui.miuix.rememberStickyListState
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.MainActivity
import com.lanhang.miuix.settings.Diagnostics
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.isNightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 动态流列表状态的粘性 key（`StickyStates` 里按它存取，跨 Tab 切换 + 二级页进出都存活） */
private const val TAB_LIST_KEY = "tab_moments"

/**
 * 工作圈宿主。视觉层换成 Compose + Miuix（见 [MomentsScreen]），
 * 这里只保留数据、翻页与增删改动作。
 */
class MomentsFragment : Fragment() {

    private var items by mutableStateOf<List<MomentItem>>(emptyList())
    private var loading by mutableStateOf(false)
    /** 上一次加载是否失败（列表为空时才用来画「加载失败 / 重新加载」，有内容时不打扰） */
    private var loadFailed by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var ended by mutableStateOf(false)
    private var publishing by mutableStateOf(false)
    private var emptyText by mutableStateOf<String?>(null)

    private var fromFeedId: String? = null

    /** 点赞/评论会改 MomentItem 里的普通可变列表，靠它触发重组 */
    private var revision by mutableStateOf(0)

    /** 已选图片（Compose 状态，选图回调直接改它） */
    private val pickedImages = mutableStateListOf<Uri>()

    /** 当前页：列表 / 发表页 / 某个人的主页（都是本 Tab 内的二级页） */
    private var page by mutableStateOf(MomentPage.LIST)

    // ---- 动态流的滚动位置存档（2026-10-30）----
    //
    // 用户：「工作圈内双击 Tab 回顶一次，再次下滑，点头像进主页再返回，依旧回到顶部」。
    //
    // 原来只在 Compose 的 `LaunchedEffect(page)` 里存位置，可那一刻列表页可能已经被
    // `MiuiNavStack` 移出组合，`feedListState.firstVisibleItemIndex` 读到的是 0 ——
    // 存下来的「原位置」就是顶部，返回自然回到顶部。（双击回顶后位置基线变成 0，
    // 才让这个时序问题稳定暴露出来。）
    //
    // 改法：位置存在 Fragment 字段上，并且**在所有跳转前同步存一次**
    // （见 [saveFeedScroll]，那时列表一定还活着），Compose 侧只负责「数据回来后再补回」。
    private var feedRestoreIndex = 0
    private var feedRestoreOffset = 0
    private var feedRestorePending = false

    /** 动态流列表状态（进程级粘性，和 Compose 里那份是同一个对象） */
    private val feedListState: androidx.compose.foundation.lazy.LazyListState
        get() = com.lanhang.miuix.ui.miuix.stickyListStateFor(TAB_LIST_KEY)

    /** 离开动态流之前调一次：同步记下当前滚到第几条 + 偏移 */
    private fun saveFeedScroll() {
        if (page != MomentPage.LIST) return
        feedRestoreIndex = feedListState.firstVisibleItemIndex
        feedRestoreOffset = feedListState.firstVisibleItemScrollOffset
        feedRestorePending = true
    }

    // ---- 他人主页：整块交给 profile/ProfileHost.kt（「我的」里也是同一份）----
    /** 主页栈：A → B → … 任意层，返回正好弹回上一个人 */
    private val profileStack = ProfileStackState()

    /** 主页数据（资料 + 动态分页 + 点赞评论），按 uid 缓存 */
    private lateinit var profileStore: ProfileStore

    /** 发表页正文 */
    private var publishText by mutableStateOf("")

    /** 「消息」里点帖子时要定位的 feed_id（滚动到就清空） */
    private var focusFeedId by mutableStateOf<String?>(null)

    /** 物理返回键：发表页里先退页面 */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (page) {
                MomentPage.PUBLISH -> closePublish()
                MomentPage.PROFILE -> closeProfile()
                MomentPage.SEARCH -> closeSearch()
                else -> Unit
            }
        }
    }

    /**
     * 选图入口：**每次点「+」都按剩余名额限制**（2026-10-25 用户要求）。
     *
     * `PickMultipleVisualMedia(maxItems)` 只能在注册时固定上限，做不到「已选 8 张时
     * 只允许再选 1 张」，所以这里自己发 Intent：
     *  · Android 13+：`ACTION_PICK_IMAGES` + `EXTRA_PICK_IMAGES_MAX = 剩余名额`
     *  · 更低版本：`ACTION_OPEN_DOCUMENT`（多选），回来后仍按剩余名额截断
     */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = buildList {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            }
            if (isEmpty()) data.data?.let { add(it) }
        }
        appendPickedImages(uris)
    }

    /** 剩余可选名额 */
    private fun remainingImageSlots(): Int = (MAX_MOMENT_IMAGES - pickedImages.size).coerceAtLeast(0)

    private fun launchImagePicker() {
        val remaining = remainingImageSlots()
        if (remaining <= 0) {
            toast(getString(R.string.moments_images_full, MAX_MOMENT_IMAGES))
            return
        }
        val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, remaining)
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        runCatching { imagePicker.launch(intent) }
            .onFailure { toast(getString(R.string.moments_action_failed, it.message.orEmpty())) }
    }

    private fun appendPickedImages(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            // 2026-10-25 用户反馈：已经加过图后再点「+」选图，应该是**追加**到已有图片后面，
            // 而不是把之前选的图全清掉、换成这一批。这里做追加 + 去重 + 仍以 9 张为上限。
            uris.forEach { uri ->
                if (pickedImages.size < 9 && pickedImages.none { it == uri }) {
                    pickedImages.add(uri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = com.lanhang.miuix.ui.miuix.ThemeState.isDark) {
                // 发表动态是「新页面」：左右滑动切页（往左进新页，退回时反过来）
                val publishCurve = spring<IntOffset>(dampingRatio = 1f, stiffness = 146f)
                // 动态流的滚动位置提到 AnimatedContent **外面**：进个人主页会把列表页的组合整个销毁，
                // 状态放在里面就跟着没了，返回时会跳回顶部（用户 2026-10-25 两次反馈）
                // 跨 Tab 切换存活（第十三轮）：Tab 切页 detach/attach 会重建 view，
                // 普通 rememberLazyListState() 会丢掉滚动位置，回来时跳回顶部、顶栏也跟着闪。
                // 2026-10-30：state 与「存档」都挪到 Fragment 上（feedListState / saveFeedScroll），
                // 因为存档必须在跳转**之前**同步做，那时列表还活着；Compose 这边只负责补回。
                LaunchedEffect(page, items.size) {
                    // 从「消息」点名要看某条动态时，别让「返回原位置」把列表拽回顶部
                    if (focusFeedId != null) return@LaunchedEffect
                    if (page == MomentPage.LIST && feedRestorePending && items.isNotEmpty()) {
                        feedListState.scrollToItem(feedRestoreIndex, feedRestoreOffset)
                        feedRestorePending = false
                    }
                }
                // 统一转场：和「我的」二级页、通讯录下钻完全同一套几何/时长/曲线
                // （第十轮重写：发表页以前还用 600ms 弹簧 + 视差淡入，观感和别处不一致）
                // 工作圈关键词过滤（第三十一轮）：命中规则的动态**只在本机藏起来**，
                // 自己发的、寻物/招领帖由 MomentsFilterStore.match 内部豁免。
                val feedVisible = remember(items, revision) {
                    if (!MomentsFilterStore.enabled) {
                        items
                    } else {
                        items.filter {
                            MomentsFilterStore.match(it.content.text.orEmpty(), it.feed.user_id) == null
                        }
                    }
                }
                val feedHidden = if (feedVisible.size == items.size) 0 else items.size - feedVisible.size
                MiuiNavStack(
                    key = page,
                    forward = pageForward,
                    pageBackground = MiuixTheme.colorScheme.background,
                ) { currentKey: Any ->
                    val current = currentKey as MomentPage
                    when (current) {
                        MomentPage.PUBLISH -> {
                            PublishMomentScreen(
                            text = publishText,
                            onTextChange = { publishText = it },
                            images = pickedImages,
                            onPickImages = { launchImagePicker() },
                            onRemoveImage = { index ->
                                if (index in pickedImages.indices) pickedImages.removeAt(index)
                            },
                                onPreviewImage = { index ->
                                    ImagePreviewActivity.start(
                                        requireContext(),
                                        pickedImages.map { it.toString() },
                                        index,
                                    )
                                },
                                // 长按拖拽排序：就地换位（九宫格自己做让位动画）
                                onMoveImage = { from, to ->
                                    if (from in pickedImages.indices &&
                                        to in pickedImages.indices && from != to
                                    ) {
                                        pickedImages.add(to, pickedImages.removeAt(from))
                                    }
                                },
                            )
                        }

                        MomentPage.PROFILE -> ProfileStackHost(
                            stack = profileStack,
                            store = profileStore,
                            // 主页退场动画里它还在组合里，只在「当前是主页」时才改顶栏
                            onTitle = { if (page == MomentPage.PROFILE) syncToolbar() },
                            onOpenProfile = { openProfile(it) },
                            onMessage = { uid, _ ->
                                (activity as? com.lanhang.miuix.ui.MainActivity)
                                    ?.openDirectChat(uid)
                            },
                            onImage = { urls, index, rect ->
                                ImagePreviewActivity.start(requireContext(), urls, index, rect)
                            },
                            emptyText = getString(R.string.profile_empty_other),
                        )

                        MomentPage.SEARCH -> MomentSearchScreen(
                            items = items,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onOpenProfile = { openProfile(it) },
                            // 第三十二轮：搜索结果展示的是完整动态。点卡片本体 → 回动态流并定位到这条，
                            // 赞和评论都在动态流里做（搜索页不摆假按钮）。
                            onOpenPost = { item ->
                                val id = item.feed.feed_id
                                searchQuery = ""
                                if (id != null) focusFeedId = id
                                closeSearch()
                            },
                            // 搜索结果里的图片照样能点开全屏预览
                            onImage = { urls, index, rect ->
                                ImagePreviewActivity.start(requireContext(), urls, index, rect)
                            },
                        )

                        else -> MomentsScreen(
                            filteredCount = feedHidden,
                            listState = feedListState,
                            focusFeedId = focusFeedId,
                            onFocusConsumed = { focusFeedId = null },
                            items = feedVisible,
                            loading = loading,
                loadFailed = loadFailed,
                onRetry = { load(reset = true) },
                            refreshing = refreshing,
                            ended = ended,
                            emptyText = emptyText,
                            revision = revision,
                            onRefresh = { load(reset = true) },
                            onLoadMore = { load(reset = false) },
                            onLike = { toggleLike(it) },
                            onPostComment = { item, replyTo, text -> postComment(item, replyTo, text) },
                            onDeleteComment = { item, comment -> deleteComment(item, comment) },
                            onDeletePost = { item -> deletePost(item) },
                            onImage = { urls, index, rect ->
                                // rect = 缩略图在窗口里的位置，预览页据此做「从缩略图放大 / 缩回原位」
                                ImagePreviewActivity.start(requireContext(), urls, index, rect)
                            },
                            onOpenPublish = { openPublish() },
                            onOpenProfile = { openProfile(it) },
                        )
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ProfileStore(lifecycleScope) { bumpLists() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // 视图重建（进程恢复/主题切换）后列表必为空，不能因 savedInstanceState 跳过加载
        if (items.isEmpty()) load(reset = true)
    }

    override fun onResume() {
        super.onResume()
        syncToolbar()
    }

    /** 这次换页是「往里推」还是「往回退」，交给 MiuiNavStack 决定动画方向（第十轮） */
    private var pageForward = true

    /** 搜索页的查询串（放在 Fragment 上：切页、返回都不该丢） */
    private var searchQuery by mutableStateOf("")

    /** 发表页 = 二级页（返回箭头 + 右上角「发表」）；列表页 = 主 Tab。 */
    fun syncToolbar() {
        val act = activity as? MainActivity ?: return
        if (isHidden || !isAdded) return
        // 不是当前 Tab 就不要动顶栏（见 MainActivity.activeTab 的注释：抢写会造成标题叠加）
        if (act.activeTab != com.lanhang.miuix.ui.shell.ShellState.TAB_MOMENTS) return
        when (page) {
            MomentPage.PUBLISH -> act.showToolbarSub(
                getString(R.string.moments_publish_title),
                { closePublish() },
                if (publishing) null else getString(R.string.moments_publish),
                { publish(publishText) },
            )

            MomentPage.PROFILE -> act.showToolbarSub(
                // 「X 的个人主页」；姓名还没回来时退回通用的「个人主页」
                run {
                    val uid = profileStack.current.orEmpty()
                    val name = profileStore.peek(uid)?.name ?: UserInfoCache.get(uid)?.name
                    profileTitle(requireContext(), name)
                },
                { closeProfile() },
            )

            MomentPage.SEARCH -> act.showToolbarSub(
                getString(R.string.moments_search_title),
                { closeSearch() },
            )

            // 列表页：右上角一颗搜索按钮（用户第三十一轮要求「工作圈搜索在顶栏加一个搜索按钮」）
            else -> act.showToolbarMain(
                getString(R.string.tab_moments),
                MiuixIcons.Search,
                { openSearch() },
            )
        }
    }

    /** 外部入口（通讯录 / 消息）打开某个人的主页；自己则忽略 */
    fun openProfileForUser(userId: String) {
        if (!isAdded || view == null) return
        openProfile(userId)
    }

    /** 「消息」里点帖子内容：切回动态流，并把这条动态滚进视野（找不到会继续翻页） */
    fun openFeed(feedId: String) {
        if (!isAdded || view == null || feedId.isBlank()) return
        if (page != MomentPage.LIST) {
            page = MomentPage.LIST
            backCallback.isEnabled = false
        }
        focusFeedId = feedId
        syncToolbar()
        if (items.isEmpty()) load(reset = true)
    }

    private fun openPublish() {
        // 第四十五轮（用户第 8 条）：上次点了「保存草稿」的内容在这里回填，
        // 图片 Uri 逐条 parse，失效的直接跳过（系统可能已收回图片权限）
        val draft = PublishDraftStore.load()
        publishText = draft?.text.orEmpty()
        pickedImages.clear()
        draft?.images?.forEach { raw ->
            val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return@forEach
            if (pickedImages.size < MAX_MOMENT_IMAGES && pickedImages.none { it == uri }) {
                pickedImages.add(uri)
            }
        }
        emptyText = null
        saveFeedScroll()
        pageForward = true
        page = MomentPage.PUBLISH
        backCallback.isEnabled = true
        syncToolbar()
    }

    /** 顶栏搜索按钮：进搜索页（推入动画；搜索页是普通二级页，返回箭头/返回键都能退） */
    private fun openSearch() {
        if (page == MomentPage.SEARCH) return
        saveFeedScroll()
        pageForward = true
        page = MomentPage.SEARCH
        backCallback.isEnabled = true
        syncToolbar()
    }

    private fun closeSearch() {
        if (page != MomentPage.SEARCH) return
        pageForward = false
        page = MomentPage.LIST
        backCallback.isEnabled = false
        syncToolbar()
    }

    private fun closePublish() {
        if (page != MomentPage.PUBLISH) return
        // 第四十五轮（用户第 8 条）：写了字或选了图就**先问**一句再走，
        // 别让一次误触把整篇内容丢掉
        if (publishText.isNotBlank() || pickedImages.isNotEmpty()) {
            val ctx = context ?: return
            // 注意：不能用 showMiuixConfirm —— 它的 dismiss 按钮绑定的是空动作，
            // 点「不保存」只会关掉弹窗、留在发布页。这里两个按钮都要有自己的行为：
            // 保存 = 落盘后离开；不保存 = 销毁后离开；点弹窗外 = 什么都不做（内容还在，最安全）。
            com.lanhang.miuix.ui.miuix.showMiuixDialog(
                ctx,
                com.lanhang.miuix.ui.miuix.MiuixDialogSpec(
                    title = getString(R.string.moments_draft_title),
                    summary = getString(R.string.moments_draft_message),
                    actions = listOf(
                        getString(R.string.moments_draft_discard) to {
                            PublishDraftStore.clear()
                            leavePublish()
                        },
                        getString(R.string.moments_draft_save) to {
                            PublishDraftStore.save(publishText, pickedImages.map { it.toString() })
                            leavePublish()
                        },
                    ),
                ),
            )
            return
        }
        leavePublish()
    }

    /** 真正离开发布页（已经问过、或者本来就没内容） */
    private fun leavePublish() {
        pageForward = false
        page = MomentPage.LIST
        backCallback.isEnabled = false
        publishText = ""
        pickedImages.clear()
        syncToolbar()
    }

    /**
     * 点头像/姓名 → 这个人的主页（帖子列表按 /timeline/{user_id} 拉）。
     *
     * 已经在主页层时**往主页栈里压一层**（A → B），返回会正好弹回 A
     * —— 和「我的」里那份实现完全同一套（profile/ProfileHost.kt）。
     */
    private fun openProfile(userId: String) {
        if (userId.isBlank() || userId == Session.userId) {
            // 自己：交给「我的」Tab（那边才有我的资料与相册）
            return
        }
        if (page == MomentPage.PROFILE) {
            profileStack.push(userId)
            syncToolbar()
            return
        }
        profileStack.reset(userId)
        // 离开动态流之前先记下滚动位置：此刻列表还活着，等 Compose 的 LaunchedEffect 再存
        // 可能列表已被移出组合、index 读成 0（见 saveFeedScroll 注释）
        saveFeedScroll()
        pageForward = true
        page = MomentPage.PROFILE
        backCallback.isEnabled = true
        syncToolbar()
    }

    /** 返回：主页栈里还有人（A 上面压了 B）就只弹一层，没人了才退回动态流 */
    private fun closeProfile() {
        if (page != MomentPage.PROFILE) return
        if (profileStack.pop()) {
            syncToolbar()
            return
        }
        pageForward = false
        page = MomentPage.LIST
        backCallback.isEnabled = false
        syncToolbar()
    }

    /** 底栏选中「工作圈」时回调：列表为空则自动刷新（兜底全黑） */
    fun onTabSelected() {
        if (view == null) return
        // 已经在列表页时再点「工作圈」= 从发表页 / 他人主页退回（微信同款手势心智）
        if (page != MomentPage.LIST) {
            page = MomentPage.LIST
            backCallback.isEnabled = false
        }
        syncToolbar()
        if (loading || items.isNotEmpty()) return
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        if (loading) return
        if (!reset && ended) return
        loading = true
        if (reset) {
            ended = false
            fromFeedId = null
            refreshing = true
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val ids = MomentsApi.getTimeLine(if (reset) null else fromFeedId)
                    val feeds = MomentsApi.getFeeds(ids)
                    val userIds = feeds.flatMap { f ->
                        listOfNotNull(f.user_id) +
                            (f.likes?.mapNotNull { it.user_id } ?: emptyList()) +
                            (f.comments?.mapNotNull { it.user_id } ?: emptyList())
                    }
                    UserInfoCache.resolve(userIds)
                    feeds
                }
                val newItems = page.map { MomentItem(it) }
                items = if (reset) newItems else items + newItems
                if (page.size < MomentsApi.PAGE_SIZE) ended = true
                fromFeedId = page.lastOrNull()?.feed_id ?: fromFeedId
                loadFailed = false
                emptyText = if (items.isEmpty()) AppContext.instance.getString(R.string.moments_empty) else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 页面已经销毁（切 Tab / 退到后台）：这不是网络失败，别写进界面状态
                throw e
            } catch (e: ApiException) {
                Diagnostics.record(AppContext.instance, AppContext.instance.getString(R.string.moments_tag_load), e)
                handleLoadError(AppContext.instance.getString(R.string.moments_load_failed, e.message.orEmpty()))
            } catch (e: Exception) {
                Diagnostics.record(AppContext.instance, AppContext.instance.getString(R.string.moments_tag_load), e)
                handleLoadError(AppContext.instance.getString(R.string.moments_network_error))
            } catch (t: Throwable) {
                // 第二十四轮：兜底。只接 Exception 的话，任何 Error（类加载失败、OOM 之类）
                // 会让协程直接死掉，下面两行永远不会跑 —— 界面就永久停在「转圈」或一片空白，
                // 连「加载失败 / 重新加载」都出不来。凡是没拉成就必须复位并允许重试。
                Diagnostics.record(AppContext.instance, AppContext.instance.getString(R.string.moments_tag_load), t)
                handleLoadError(AppContext.instance.getString(R.string.moments_network_error))
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    private fun handleLoadError(message: String) {
        // 有内容时照旧只 toast（别把已经看到的东西盖掉）；一片空白时露出「加载失败 / 重新加载」
        loadFailed = items.isEmpty()
        if (items.isEmpty()) emptyText = message else toast(message)
    }

    /**
     * 点赞 / 取消点赞。
     *
     * 2026-10-25 用户反馈「点赞后再点应该取消点赞，而不是又点一次赞」：
     * 之前是「先请求、成功后才改本地状态」，请求期间（或请求失败、或服务端返回的
     * 点赞列表里 user_id 对不上）本地 `likedByMe` 一直还是 false，于是第二次点击
     * 又走了一遍「点赞」。现在改成**先乐观改本地状态再请求**，失败回滚，
     * 这样第二次点击一定是取消。
     */
    private fun toggleLike(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        val liked = item.likedByMe
        val meId = Session.userId.ifBlank { "local_me" }
        fun markLiked(on: Boolean) {
            if (on) {
                item.likes.add(
                    LikeEntry(
                        like_id = "local_${System.currentTimeMillis()}",
                        feed_id = feedId,
                        user_id = meId,
                        create_dt = System.currentTimeMillis(),
                    )
                )
            } else {
                item.likes.removeAll { it.user_id == meId || (it.like_id ?: "").startsWith("local_") }
            }
            bumpLists()
        }
        // 乐观更新：手指抬起来 UI 立刻变
        markLiked(!liked)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (liked) MomentsApi.cancelLike(feedId) else MomentsApi.like(feedId)
                }
            } catch (e: Exception) {
                markLiked(liked)   // 请求失败：把乐观更新回滚回去
                toast(AppContext.instance.getString(R.string.moments_action_failed, e.message.orEmpty()))
            }
        }
    }

    private fun publish(text: String) {
        if (publishing) return
        val images = pickedImages.toList()
        if (text.isEmpty() && images.isEmpty()) {
            toast(AppContext.instance.getString(R.string.moments_publish_empty))
            return
        }
        if (text.length > 2000) {
            toast(AppContext.instance.getString(R.string.moments_publish_too_long))
            return
        }
        publishing = true
        syncToolbar()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appContext = AppContext.instance
                withContext(Dispatchers.IO) {
                    val media = images.map { MomentImageUploader.upload(appContext, it) }
                    check(MomentsApi.publishFeed(text, media) != null) {
                        AppContext.instance.getString(R.string.moments_server_no_id)
                    }
                }
                pickedImages.clear()
                publishText = ""
                // 发布成功 = 草稿已经用掉了，必须销毁（否则下次打开发布页又冒出上次的内容）
                PublishDraftStore.clear()
                toast(AppContext.instance.getString(R.string.moments_publish_done))
                closePublish()
                load(reset = true)
            } catch (e: Exception) {
                Diagnostics.record(AppContext.instance, AppContext.instance.getString(R.string.moments_tag_publish), e)
                toast(AppContext.instance.getString(R.string.moments_publish_failed, e.message.orEmpty()))
            } finally {
                publishing = false
                syncToolbar()
            }
        }
    }

    private fun postComment(item: MomentItem, replyToUserId: String?, text: String) {
        val feedId = item.feed.feed_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    MomentsApi.postComment(feedId, text, replyToUserId)
                    runCatching { MomentsApi.getFeed(feedId) }.getOrNull()
                }
                if (fresh != null) {
                    item.comments.clear()
                    item.comments.addAll(fresh.comments.orEmpty())
                    item.likes.clear()
                    item.likes.addAll(fresh.likes.orEmpty())
                } else {
                    item.comments.add(MomentsApi.localComment(feedId, text))
                }
                bumpLists()
                toast(AppContext.instance.getString(R.string.moments_sent))
            } catch (e: Exception) {
                toast(AppContext.instance.getString(R.string.moments_send_failed, e.message.orEmpty()))
            }
        }
    }

    private fun deleteComment(item: MomentItem, comment: CommentEntry) {
        val feedId = item.feed.feed_id ?: return
        val commentId = comment.comment_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { MomentsApi.deleteComment(feedId, commentId) }
                item.comments.removeIf { it.comment_id == commentId }
                bumpLists()
                toast(AppContext.instance.getString(R.string.moments_deleted))
            } catch (e: Exception) {
                toast(AppContext.instance.getString(R.string.moments_delete_failed, e.message.orEmpty()))
            }
        }
    }

    /** 删除自己发布的动态：服务端成功后才从列表里摘掉，失败给出提示 */
    private fun deletePost(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { MomentsApi.deleteFeed(feedId) }
                items = items.filterNot { it.feed.feed_id == feedId }
                bumpLists()
                toast(AppContext.instance.getString(R.string.moments_deleted))
            } catch (e: Exception) {
                toast(AppContext.instance.getString(R.string.moments_delete_failed, e.message.orEmpty()))
            }
        }
    }

    /**
     * 点赞/评论会直接改 MomentItem 里的可变列表，Compose 看不到变化，
     * 统一在这里把「公共流」和「他人主页」「我的相册」三份列表都推一个新实例。
     */
    private fun bumpLists() {
        revision++
        items = items.toList()
    }

    /**
     * 提示。Fragment 已经 detach（切走了 tab / 页面已销毁）时 `context` 是 null，
     * 这时退到 Application context —— 2026-10-26 第六轮崩溃
     * `IllegalStateException: … not attached to a context` 就是这里和 Diagnostic 一路 requireContext() 打的。
     */
    private fun toast(msg: String) {
        val ctx = context ?: AppContext.instance
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

/** 工作圈这一栈的页面（枚举顺序 = 层级深度，用来定推入/返回方向） */
internal enum class MomentPage { LIST, PUBLISH, PROFILE, SEARCH }

/** 一条动态最多 9 张图 */
private const val MAX_MOMENT_IMAGES = 9
