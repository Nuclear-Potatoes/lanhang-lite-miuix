package com.lanhang.miuix.profile

import android.content.Context
import android.graphics.Rect
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lanhang.miuix.R
import com.lanhang.miuix.mine.UserProfilePage
import com.lanhang.miuix.moments.CommentEntry
import com.lanhang.miuix.moments.LikeEntry
import com.lanhang.miuix.moments.MomentFeedSource
import com.lanhang.miuix.moments.MomentItem
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.net.MomentsApi
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.net.StaffBrief
import com.lanhang.miuix.net.StaffInfo
import com.lanhang.miuix.net.cleanOrgName
import com.lanhang.miuix.schedule.AppContext
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.MiuiNavStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 个人主页（他人主页）的**唯一一份实现**。
 *
 * 2026-10-27 第二十一轮（用户反馈）：
 *  · 通讯录 → A 主页，顶栏显示的是「A」而不是「A 的个人主页」；
 *  · 在这层主页里再点 B，没有转场动画；
 *  · 从 B 返回一步直接落回通讯录首页（应该退回 A 的主页），标题也还是 B；
 *  · 我的 → 相册（我发过的动态）里点别人的名字，进的却是**自己**的主页。
 *
 * 根因是同一件事在三处各写了一遍：「我的」里是 `MinePage.PROFILE_OTHER` 单槽位
 * （`uiState.otherUserId` 一份数据，A→B 只能覆盖），工作圈里是 `MomentPage.PROFILE` 单槽位，
 * 相册那处干脆把「点谁的主页」写死成了「我的主页」。所以三处的标题、返回层级、转场各不相同。
 *
 * 现在收敛成这一份：[ProfileStackState]（主页栈：A → B → C 任意层）
 * + [ProfileStore]（资料 / 动态分页 / 点赞评论，按 uid 缓存，退回上一层不重拉）
 * + [ProfileStackHost]（渲染：内部自己一层 [MiuiNavStack]，转场与全应用其它二级页一致）。
 *
 * 「我的」和工作圈两个宿主都只调用 [ProfileStackHost]，行为天然一致。
 */

/** 顶栏标题文案：「X 的个人主页」；姓名还没回来时退回「个人主页」 */
fun profileTitle(context: Context, name: String?): String =
    name?.takeIf { it.isNotBlank() }
        ?.let { context.getString(R.string.profile_title_of, it) }
        ?: context.getString(R.string.profile_title)

/**
 * 主页栈：栈顶就是**当前显示**的那个人。
 *
 * 只存 userId（主页数据在 [ProfileStore] 里按 uid 缓存），所以 A → B 之后弹回 A，
 * A 的内容原样还在，不会白屏重拉。
 */
@Stable
class ProfileStackState {

    private val uids = mutableStateListOf<String>()

    /** 这次是「往里推」还是「往回退」，交给 [MiuiNavStack] 定动画方向 */
    var forward by mutableStateOf(true)
        private set

    val current: String? get() = uids.lastOrNull()

    val size: Int get() = uids.size

    val isEmpty: Boolean get() = uids.isEmpty()

    /** 压入一个人的主页（已经在这一层就忽略，避免重复点同一张头像压两层） */
    fun push(uid: String) {
        if (uid.isBlank()) return
        if (uids.lastOrNull() == uid) return
        uids.add(uid)
        forward = true
    }

    /** 从入口（通讯录 / 消息 / 私信 / 相册）打开某个人：清空旧栈，只留他一个 */
    fun reset(uid: String) {
        uids.clear()
        if (uid.isNotBlank()) uids.add(uid)
        forward = true
    }

    /** 弹一层；返回「弹完还剩人没有」（false = 栈空了，宿主应该连这一页一起关掉） */
    fun pop(): Boolean {
        if (uids.isEmpty()) return false
        uids.removeAt(uids.lastIndex)
        forward = false
        return uids.isNotEmpty()
    }

    fun clear() {
        uids.clear()
        forward = false
    }

    /** 进程恢复用：整栈还原 */
    fun restore(list: List<String>) {
        uids.clear()
        list.filter { it.isNotBlank() }.forEach { uids.add(it) }
        forward = false
    }

    fun snapshot(): List<String> = uids.toList()
}

/** 一个人的主页数据（资料 + 动态分页）。按 uid 缓存，返回上一层时原样还在 */
@Stable
class ProfileSlice {
    var staff by mutableStateOf<StaffInfo?>(null)
    var items by mutableStateOf<List<MomentItem>>(emptyList())
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var ended by mutableStateOf(false)

    /** 点赞/评论会就地改 MomentItem 里的可变列表，靠它触发重组 */
    var revision by mutableStateOf(0)

    var loadedOnce by mutableStateOf(false)

    internal var fromFeedId: String? = null

    /** 姓名（资料优先，退回工作圈那份批量缓存） */
    val name: String?
        get() = staff?.name?.takeIf { it.isNotBlank() } ?: UserInfoCache.get(staff?.id.orEmpty())?.name
}

/**
 * 主页数据源 + 互动动作（两个宿主共用）。
 *
 * 取数协议完全沿用工作圈那套：`GET /staffs/{id}` 拿资料、`GET /timeline/{user_id}` +
 * `POST /feed/batch` 拿动态；点赞/评论和动态流里那一条是同一个 [MomentItem] 对象，
 * 所以两边状态天然同步。
 */
class ProfileStore(
    private val scope: CoroutineScope,
    /** 互动之后通知宿主把自己列表的 revision 也提一下（同一个对象被就地改了） */
    private val onMutated: () -> Unit = {},
) {

    private val slices = mutableStateMapOf<String, ProfileSlice>()

    fun slice(uid: String): ProfileSlice = slices.getOrPut(uid) { ProfileSlice() }

    fun peek(uid: String): ProfileSlice? = slices[uid]

    /** 进栈顶时调用：没取过就取，取过就只补资料（姓名/头像） */
    fun ensure(uid: String) {
        if (uid.isBlank()) return
        val slice = slice(uid)
        if (slice.loading) return
        if (!slice.loadedOnce) {
            loadStaff(uid)
            load(uid, reset = true)
        } else {
            loadStaff(uid)
        }
    }

    fun refresh(uid: String) = load(uid, reset = true)

    fun loadMore(uid: String) = load(uid, reset = false)

    /** 资料：GET /staffs/{id}；拿到就写回工作圈的用户缓存（顶栏标题、头像都靠它） */
    fun loadStaff(uid: String) {
        if (uid.isBlank()) return
        scope.launch {
            val staff = runCatching {
                withContext(Dispatchers.IO) { RceApi.getStaff(uid) }
            }.getOrNull()
            if (staff == null) {
                // 拿不到就退回批量接口，至少有名字和头像
                runCatching { withContext(Dispatchers.IO) { UserInfoCache.resolve(listOf(uid)) } }
                slice(uid).revision++
                return@launch
            }
            val slice = slice(uid)
            slice.staff = staff
            slice.revision++
            UserInfoCache.put(
                StaffBrief(
                    id = uid,
                    name = staff.name,
                    portrait_url = staff.portraitUrl,
                    depart_name = staff.depart_name ?: staff.org.cleanOrgName(),
                    duty_name = null,
                    company_id = staff.company_id,
                ),
            )
        }
    }

    private fun load(uid: String, reset: Boolean) {
        if (uid.isBlank()) return
        val slice = slice(uid)
        if (slice.loading) return
        if (!reset && slice.ended) return
        slice.loading = true
        slice.refreshing = reset && slice.items.isNotEmpty()
        scope.launch {
            try {
                val from = if (reset) null else slice.fromFeedId
                val fetched = MomentFeedSource.load(uid, from, MomentsApi.PAGE_SIZE)
                if (fetched.isNotEmpty()) {
                    slice.fromFeedId = fetched.last().feed.feed_id ?: slice.fromFeedId
                }
                slice.items = if (reset) fetched else slice.items + fetched
                slice.ended = fetched.size < MomentsApi.PAGE_SIZE
                slice.loadedOnce = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(AppContext.instance.getString(R.string.moments_load_failed, e.message.orEmpty()))
            } finally {
                slice.loading = false
                slice.refreshing = false
                slice.revision++
            }
        }
    }

    /**
     * 点赞 / 取消点赞：**先乐观改本地再请求**，失败回滚（和工作圈同一份逻辑）。
     */
    fun toggleLike(uid: String, item: MomentItem) {
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
                    ),
                )
            } else {
                item.likes.removeAll { it.user_id == meId || (it.like_id ?: "").startsWith("local_") }
            }
            bump(uid)
        }
        markLiked(!liked)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (liked) MomentsApi.cancelLike(feedId) else MomentsApi.like(feedId)
                }
            } catch (e: Exception) {
                markLiked(liked)
                toast(AppContext.instance.getString(R.string.moments_action_failed, e.message.orEmpty()))
            }
        }
    }

    fun postComment(uid: String, item: MomentItem, replyTo: String?, text: String) {
        scope.launch {
            try {
                com.lanhang.miuix.moments.MomentActions.comment(item, replyTo, text)
                reloadFeed(uid, item)
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    fun deleteComment(uid: String, item: MomentItem, comment: CommentEntry) {
        scope.launch {
            try {
                com.lanhang.miuix.moments.MomentActions.deleteComment(item, comment)
                reloadFeed(uid, item)
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    fun deletePost(uid: String, item: MomentItem) {
        scope.launch {
            try {
                com.lanhang.miuix.moments.MomentActions.deleteFeed(item)
                val slice = slice(uid)
                slice.items = slice.items.filter { it !== item }
                bump(uid)
            } catch (e: Exception) {
                toast(e.message)
            }
        }
    }

    /** 评论/点赞后回刷单条动态（官方同样用 GET /feed/{id} 取最新评论） */
    private fun reloadFeed(uid: String, item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        scope.launch {
            val fresh = runCatching {
                withContext(Dispatchers.IO) { MomentsApi.getFeed(feedId) }
            }.getOrNull() ?: return@launch
            item.comments.clear()
            fresh.comments?.let { item.comments.addAll(it) }
            item.likes.clear()
            fresh.likes?.let { item.likes.addAll(it) }
            bump(uid)
        }
    }

    private fun bump(uid: String) {
        slice(uid).revision++
        // 列表头（头像/姓名）也跟着这份数据走
        slice(uid).items = slice(uid).items.toList()
        onMutated()
    }

    private fun toast(message: String?) {
        val text = message?.takeIf { it.isNotBlank() } ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(AppContext.instance, text, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 主页宿主：把栈顶那个人的主页画出来，并处理「主页里再点别人的头像/姓名」。
 *
 * @param onTitle 顶栏标题变化（「X 的个人主页」），由宿主去改 Toolbar
 * @param onOpenProfile 宿主入口（通讯录/消息/私信/相册）进来时用它从外面开第一层
 */
@Composable
fun ProfileStackHost(
    stack: ProfileStackState,
    store: ProfileStore,
    onTitle: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onMessage: (String, String) -> Unit,
    onImage: (List<String>, Int, Rect?) -> Unit,
    emptyText: String,
) {
    val uid = stack.current ?: return
    val context = LocalContext.current

    // 每层各自的滚动位置：A → B → 返回 A 时不会跳回顶部
    val listStates = remember { HashMap<String, LazyListState>() }

    // 进栈顶就把资料/动态取齐（取过的不重拉）
    LaunchedEffect(uid) { store.ensure(uid) }

    // 标题跟着「当前这层是谁」走：姓名回来之前先显示「个人主页」
    val titleName = store.slice(uid).name ?: store.slice(uid).staff?.name
    LaunchedEffect(uid, titleName) { onTitle(profileTitle(context, titleName)) }

    MiuiNavStack(
        key = uid,
        forward = stack.forward,
        pageBackground = MiuixTheme.colorScheme.background,
    ) { key: Any ->
        val pageUid = key as String
        val slice = store.slice(pageUid)
        UserProfilePage(
            staff = slice.staff,
            userId = pageUid,
            isSelf = false,
            items = slice.items,
            loading = slice.loading,
            refreshing = slice.refreshing,
            ended = slice.ended,
            revision = slice.revision,
            emptyText = emptyText,
            onRefresh = { store.refresh(pageUid) },
            onLoadMore = { store.loadMore(pageUid) },
            onLike = { store.toggleLike(pageUid, it) },
            onPostComment = { item, replyTo, text -> store.postComment(pageUid, item, replyTo, text) },
            onDeleteComment = { item, comment -> store.deleteComment(pageUid, item, comment) },
            onDeletePost = { item -> store.deletePost(pageUid, item) },
            onImage = onImage,
            // 主页里再点别人的头像/姓名：往栈里压一层 → 自带推入动画，返回正好弹回这一层
            onOpenProfile = { next ->
                if (next.isNotBlank() && next != Session.userId && next != pageUid) {
                    stack.push(next)
                }
            },
            onMessage = { onMessage(pageUid, slice.staff?.name.orEmpty()) },
            listState = listStates.getOrPut(pageUid) { LazyListState() },
        )
    }
}
