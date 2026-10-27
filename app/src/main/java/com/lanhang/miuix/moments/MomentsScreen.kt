package com.lanhang.miuix.moments

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.ui.miuix.isNightMode
import coil.compose.AsyncImage
import com.lanhang.miuix.R
import com.lanhang.miuix.net.cleanOrgName
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.LoadFailedHint
import com.lanhang.miuix.ui.miuix.rememberBottomSafeInsetDp
import com.lanhang.miuix.ui.miuix.refreshTexts
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import com.lanhang.miuix.ui.miuix.showMiuixInput
import com.lanhang.miuix.ui.miuix.showMiuixInfo
import com.lanhang.miuix.ui.shell.ReportListScroll
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.shell.ScrollToTop
import com.lanhang.miuix.ui.shell.animateScrollToTopSlow
import com.lanhang.miuix.ui.shell.ShellState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作圈（Miuix 版）。
 *
 * 一条动态一张卡片：头像 + 姓名/部门 + 时间 → 正文 → 九宫格 → 点赞/评论 → 评论区。
 * 发布与评论用 Miuix 的 `OverlayDialog` + `TextField`（不再是 Material 的 EditText 弹窗）。
 */
@Composable
fun MomentsScreen(
    items: List<MomentItem>,
    loading: Boolean,
    refreshing: Boolean,
    ended: Boolean,
    emptyText: String?,
    revision: Int,
    /** 被「工作圈过滤」藏起来的条数：>0 时列表底部给一句说明，免得用户以为动态丢了 */
    filteredCount: Int = 0,
    /** true = 上一次加载失败且列表里没有任何内容：露出「加载失败 / 重新加载」 */
    loadFailed: Boolean = false,
    /** 「重新加载」被点：重新拉第一页 */
    onRetry: () -> Unit = {},
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLike: (MomentItem) -> Unit,
    onPostComment: (MomentItem, String?, String) -> Unit,
    onDeleteComment: (MomentItem, CommentEntry) -> Unit,
    onDeletePost: (MomentItem) -> Unit,
    onImage: (List<String>, Int, android.graphics.Rect?) -> Unit,
    onOpenPublish: () -> Unit,
    /** 点作者头像/姓名 → 打开这个人的个人主页（工作圈里是真人，相册里是自己） */
    onOpenProfile: (String) -> Unit = {},
    /** 列表顶部的头（个人主页用：资料卡片 + 「TA 的动态」小标题）；null = 普通动态流 */
    header: (@Composable () -> Unit)? = null,
    /** 右下角「发表」按钮：相册、个人主页里不显示 */
    showFab: Boolean = true,
    /**
     * 动态流的滚动状态。**必须能从外面传进来**：工作圈进个人主页时整页会被
     * `AnimatedContent` 销毁重建，状态留在这里面就丢了，返回时会跳回顶部
     * （用户 2026-10-25 反馈的「返回后又回到顶部」）。
     */
    listState: LazyListState = rememberLazyListState(),
    /** 从「消息」点帖子进来时要定位的 feed_id；滚到之后由宿主清空 */
    focusFeedId: String? = null,
    onFocusConsumed: () -> Unit = {},
) {
    var commentTarget by remember { mutableStateOf<Pair<MomentItem, String?>?>(null) }
    var commentText by remember { mutableStateOf("") }
    var myCommentsTarget by remember { mutableStateOf<MomentItem?>(null) }
    var commentDeleteTarget by remember { mutableStateOf<Pair<MomentItem, CommentEntry>?>(null) }
    var deleteTarget by remember { mutableStateOf<MomentItem?>(null) }

    // 往下滑 → 顶栏大标题收起为居中小标题（见 ShellScroll）
    ReportListScroll(listState, ShellState.TAB_MOMENTS)

    // 双击底栏「工作圈」回到顶部
    LaunchedEffect(ScrollToTop.tick) {
        if (ScrollToTop.tab == ShellState.TAB_MOMENTS) listState.animateScrollToTopSlow()
    }

    // 滚到底部自动翻页（对齐原来的 onScrolled 逻辑：距末尾 3 条就预取）
    LaunchedEffect(listState, items.size, ended, loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (!loading && !ended && last >= items.size - 3) onLoadMore()
            }
    }

    // 从「消息」点帖子内容进来：把那条动态滚进视野；还没加载到就继续往后翻页找
    // （消息里的帖子可能是很久以前的，不在首页里；最多翻 8 页，避免一直空转）
    var focusTries by remember(focusFeedId) { mutableIntStateOf(0) }
    LaunchedEffect(focusFeedId, items.size, ended, loading) {
        val target = focusFeedId ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.feed.feed_id == target }
        when {
            // 直接 scrollToItem（不做动画）：这一步可能发生在列表刚重建、还没测量的那一帧，
            // 带动画会被随后的布局/恢复滚动顶掉，用户看到的就是「还是停在顶部」
            index >= 0 -> {
                listState.scrollToItem(index)
                onFocusConsumed()
            }
            // 正在加载：等这一页回来再看（**不能**在这里清掉 focusFeedId，否则永远定位不到）
            loading -> Unit
            !ended && focusTries < 12 -> {
                focusTries++
                onLoadMore()
            }
            else -> onFocusConsumed()
        }
    }

    // 不透明根：切 Tab / 发表页推入时两页会重叠，透明根会透出下层
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            refreshTexts = refreshTexts(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    // 有表头时（个人主页 / 相册）：资料卡要先出来，加载态和空态放进列表里，
                    // 不能整页盖一个转圈，否则刚点进来的那一瞬间只剩一个加载圈。
                    //
                    // 第二十四轮（断网白屏）：这个条件以前是 `header != null || items.isNotEmpty()`，
                    // 而「转圈 / 空 / 加载失败」三个 item 全挂在这个 LazyColumn 里面 ——
                    // 于是「没有表头 + 一条数据都没有」连容器都不组合，整页纯黑。
                    // 断网冷启动正好落在这一格：用户看到的是永远不出现的「加载失败」。
                    // 改成 else：容器无条件存在，里面再决定摆转圈、空态还是「加载失败 / 重新加载」。
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
                    ) {
                        if (header != null) {
                            item("header") { header() }
                            if (loading && items.isEmpty()) {
                                item("headerLoading") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 40.dp, bottom = 40.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }
                                }
                            } else if (items.isEmpty()) {
                                item("headerEmpty") {
                                    if (loadFailed) {
                                        LoadFailedHint(onRetry = onRetry)
                                    } else Text(
                                        text = emptyText ?: stringResource(R.string.moments_empty),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 40.dp, bottom = 40.dp),
                                    )
                                }
                            }
                        } else if (loading && items.isEmpty()) {
                            // 首次加载：转圈 + 一行小字。断网时这一屏可能要停十几秒
                            // （等 callTimeout），只有转圈会像「卡死」，配一行说明才像在加载。
                            item("loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 76.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(Modifier.height(14.dp))
                                        Text(
                                            text = stringResource(R.string.moments_loading),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                            }
                        } else if (items.isEmpty()) {
                            item("empty") {
                                if (loadFailed) {
                                    LoadFailedHint(onRetry = onRetry)
                                } else Text(
                                    text = emptyText ?: stringResource(R.string.moments_empty),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 80.dp),
                                )
                            }
                        }
                        itemsIndexed(
                            items,
                            key = { _, item -> item.feed.feed_id ?: item.hashCode().toString() },
                        ) { index, item ->
                            MomentCard(
                                item = item,
                                revision = revision,
                                onDeletePost = { deleteTarget = item },
                                onLike = { onLike(item) },
                                onComment = { commentTarget = item to null },
                                onReply = { userId -> commentTarget = item to userId },
                                onCommentsLongPress = { myCommentsTarget = item },
                                onCommentDelete = { comment -> commentDeleteTarget = item to comment },
                                onImage = onImage,
                                onOpenProfile = onOpenProfile,
                            )
                            // 2026-10-25 用户要求：不同帖子之间加一条分隔线
                            if (index < items.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (filteredCount > 0) {
                            item("filtered") {
                                Text(
                                    text = stringResource(R.string.moments_filtered_hint, filteredCount),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        if (ended) {
                            item("end") {
                                Text(
                                    text = stringResource(R.string.moments_no_more),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showFab) {
            FloatingActionButton(
                onClick = onOpenPublish,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = stringResource(R.string.moments_cd_publish),
                    tint = Color.White,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

    }

    // ---- 发表评论 / 回复：统一输入对话框 ----
    // 2026-10-25 用户反馈：①遮罩只盖住顶栏下方（顶栏没被压暗）；②没有进出场动画。
    // 根因是原来做成页面内浮层。现在改成真正的 Dialog 窗口（showMiuixInput），
    // 遮罩覆盖整屏、动画与学年选择完全一致。
    val replyTarget = commentTarget
    val replyCtx = LocalContext.current
    LaunchedEffect(replyTarget) {
        val target = replyTarget ?: return@LaunchedEffect
        commentTarget = null
        val title = if (target.second.isNullOrEmpty()) {
            replyCtx.getString(R.string.moments_comment_title)
        } else {
            replyCtx.getString(R.string.moments_reply_title, UserInfoCache.nameOf(target.second))
        }
        showMiuixInput(
            context = replyCtx,
            title = title,
            placeholder = replyCtx.getString(R.string.moments_comment_hint),
            confirmText = replyCtx.getString(R.string.common_send),
            dismissText = replyCtx.getString(R.string.common_cancel),
            onConfirm = { body -> onPostComment(target.first, target.second, body) },
        )
    }

    // ---- 删除自己发布的动态（右下角「删除」按钮 / 长按卡片触发）----
    // 统一用 Miuix 对话框（ui/miuix/MiuixDialog.kt），和其它页面弹窗同款且贴底栏
    val pending = deleteTarget
    val ctx = LocalContext.current
    val delTitle = stringResource(R.string.moments_delete_post)
    val delMsg = stringResource(R.string.moments_delete_post_confirm)
    val delConfirm = stringResource(R.string.moments_delete)
    val delCancel = stringResource(R.string.common_cancel)
    LaunchedEffect(pending) {
        val item = pending ?: return@LaunchedEffect
        deleteTarget = null
        showMiuixConfirm(
            context = ctx,
            title = delTitle,
            message = delMsg,
            confirmText = delConfirm,
            dismissText = delCancel,
            onConfirm = { onDeletePost(item) },
        )
    }

    // ---- 删除某条评论（长按评论触发）----
    val pendingComment = commentDeleteTarget
    val delCommentTitle = stringResource(R.string.moments_delete_comment)
    val delCommentMsg = stringResource(R.string.moments_delete_comment_confirm)
    val delCommentOwnOnly = stringResource(R.string.moments_delete_comment_own_only)
    LaunchedEffect(pendingComment) {
        val target = pendingComment ?: return@LaunchedEffect
        commentDeleteTarget = null
        val (moment, comment) = target
        if (comment.user_id != Session.userId) {
            // 服务端只允许删自己的评论，先拦一道，避免无意义的失败请求
            showMiuixInfo(
                context = ctx,
                title = delCommentTitle,
                message = delCommentOwnOnly,
                confirm = delCancel,
            )
        } else {
            showMiuixConfirm(
                context = ctx,
                title = delCommentTitle,
                message = delCommentMsg,
                confirmText = delConfirm,
                dismissText = delCancel,
                onConfirm = { onDeleteComment(moment, comment) },
            )
        }
    }

}


/**
 * 点赞 / 评论的小胶囊：图标 + 数字。
 *
 * 用户反馈「赞与评论没有对应的图标装饰」——原来只有一行纯文字，看不出这是两个动作。
 * 现在左侧固定给一个 15dp 的矢量图标（心形 / 气泡），点赞后心形实心并转暖红。
 */
@Composable
private fun MomentActionChip(
    iconRes: Int,
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (accent) Color(0xFFE94634) else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val fill = if (accent) Color(0x1AE94634) else MiuixTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = tint,
        )
        Spacer(Modifier.width(5.dp))
        Text(text = text, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

@Composable
private fun MomentCard(
    item: MomentItem,
    revision: Int,
    onDeletePost: () -> Unit,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onReply: (String) -> Unit,
    onCommentsLongPress: () -> Unit,
    onOpenProfile: (String) -> Unit = {},
    onCommentDelete: (CommentEntry) -> Unit,
    onImage: (List<String>, Int, android.graphics.Rect?) -> Unit,
) {
    // MomentItem.likes/comments 是普通可变列表（非 Compose 状态），
    // 靠 revision 变化时重新快照，才能让点赞/评论立刻反映到界面
    val likes = remember(item, revision) { item.likes.toList() }
    val comments = remember(item, revision) { item.comments.toList() }
    val likedByMe = remember(item, revision) { item.likedByMe }
    val isMine = remember(item) { item.feed.user_id == Session.userId }

    val user = UserInfoCache.get(item.feed.user_id)
    val name = user?.name ?: stringResource(R.string.moments_anonymous)
    val sub = listOfNotNull(user?.depart_name, user?.duty_name)
        .mapNotNull { it.cleanOrgName() }
        .joinToString(" · ")
    val time = item.feed.create_dt?.let { timeFormat.format(Date(it)) }.orEmpty()

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // 长按卡片 = 我的动态菜单（删除自己发布的内容）；普通点击不拦截，图片/评论照旧
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { if (isMine) onDeletePost() },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanhangAvatar(
                    url = user?.portrait_url,
                    name = name,
                    size = 40.dp,
                    // 点头像 → 这个人的个人主页
                    modifier = Modifier.clickable { item.feed.user_id?.let(onOpenProfile) },
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 点姓名 → 个人主页（评论区里的姓名早就可以点，这里补齐）
                        modifier = Modifier.clickable {
                            item.feed.user_id?.let(onOpenProfile)
                        },
                    )
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = time,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            val text = item.content.text.orEmpty()
            if (text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                // 2026-10-28 第三十一轮（用户：「给工作圈加上长按文字自由复制的功能」）：
                // 正文包进 SelectionContainer —— 长按即选中，可拖把手选任意段，
                // 复制菜单是系统那套（复制 / 全选），不用自己造按钮。
                // 卡片自己的长按（删自己的动态）挂在正文**之外**，两者不抢手势。
                SelectionContainer {
                    Text(text = text, style = MiuixTheme.textStyles.body1)
                }
            }

            val media = item.content.media
                ?.filter { !it.displayUrl.isNullOrBlank() }
                .orEmpty()
            if (media.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MomentImageGrid(media, onImage)
            }

            Spacer(Modifier.height(6.dp))
            // 点赞 / 评论：图钉 + 数字（用户反馈原来只有纯文字，缺装饰图标）。
            // 图标用项目里已有的 Material Symbols 矢量（ic_favorite / ic_mode_comment）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MomentActionChip(
                    iconRes = if (likedByMe) R.drawable.ic_favorite else R.drawable.ic_favorite_outline,
                    text = if (likedByMe) {
                        stringResource(R.string.moments_liked, likes.size)
                    } else {
                        stringResource(R.string.moments_like, likes.size)
                    },
                    accent = likedByMe,
                    onClick = onLike,
                )
                MomentActionChip(
                    iconRes = R.drawable.ic_mode_comment,
                    text = stringResource(R.string.moments_comment_count, comments.size),
                    accent = false,
                    onClick = onComment,
                )
                Spacer(Modifier.weight(1f))
                // 2026-10-25 用户要求：自己发布的动态在右下角给一个「删除」，
                // 纯蓝色文字、不加底色/阴影
                if (isMine) {
                    Text(
                        text = stringResource(R.string.moments_delete),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDeletePost() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // 点赞名单 + 评论区
            if (likes.isNotEmpty() || comments.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MiuixTheme.colorScheme.dividerLine,
                )
                if (likes.isNotEmpty()) {
                    // 2026-10-25 用户反馈：谁点了赞看不出来是「点赞」，补一个和点赞按钮
                    // 同一个爱心图标做前缀
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_favorite),
                            contentDescription = null,
                            tint = Color(0xFFE94634),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 名单里的每个名字都能点：进那个人的个人主页（官方同款交互）
                        val likeText = buildAnnotatedString {
                            likes.forEachIndexed { index, like ->
                                if (index > 0) append("，")
                                val uid = like.user_id
                                val name = UserInfoCache.nameOf(uid)
                                if (uid.isNullOrEmpty()) {
                                    append(name)
                                } else {
                                    withLink(
                                        LinkAnnotation.Clickable(
                                            tag = uid,
                                            styles = TextLinkStyles(
                                                style = SpanStyle(
                                                    color = MiuixTheme.colorScheme.primary,
                                                ),
                                            ),
                                            linkInteractionListener = { onOpenProfile(uid) },
                                        )
                                    ) { append(name) }
                                }
                            }
                        }
                        Text(
                            text = likeText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (comments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val replyInline = stringResource(R.string.moments_reply_inline)
                    val separator = stringResource(R.string.moments_comment_separator)
                    // 2026-10-28 第三十一轮（用户：「深色模式下工作圈评论区的阴影丢失」）：
                    // 浅色下这块靠 Miuix 默认投影浮在卡片上；深色里投影是黑的，打在近黑的底上
                    // 等于没有，评论区就和卡片糊成一片。深色改成「深投影 + 1dp 亮边」：
                    // 亮边是深色下唯一读得出来的层次，投影负责把边缘和卡片拉开。
                    val commentShape = RoundedCornerShape(10.dp)
                    val darkMode = LocalContext.current.isNightMode()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 4.dp,
                                shape = commentShape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.10f),
                                spotColor = Color.Black.copy(alpha = 0.10f),
                            )
                            .then(
                                if (darkMode) {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.07f),
                                        shape = commentShape,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clip(commentShape)
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = onCommentsLongPress,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        comments.forEach { comment ->
                            val uid = comment.user_id
                            val replyTo = comment.reply_to?.takeIf { it.isNotEmpty() && it != uid }
                            // 2026-10-25 用户要求：评论区也要显示头像和姓名，姓名用主色蓝并
                            // 预留点击区域（后续个人主页做好后，点姓名/头像直接进个人主页）。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 轻点 = 回复；长按 = 删除这条评论
                                    .combinedClickable(
                                        onClick = { if (!uid.isNullOrEmpty()) onReply(uid) },
                                        onLongClick = { onCommentDelete(comment) },
                                    ),
                                verticalAlignment = Alignment.Top,
                            ) {
                                LanhangAvatar(
                                    url = UserInfoCache.get(uid)?.portrait_url,
                                    name = UserInfoCache.nameOf(uid),
                                    size = 24.dp,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .clickable { if (!uid.isNullOrEmpty()) onOpenProfile(uid) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = UserInfoCache.nameOf(uid),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable {
                                                if (!uid.isNullOrEmpty()) onOpenProfile(uid)
                                            },
                                        )
                                        if (replyTo != null) {
                                            // 2026-10-25 用户要求：「回复」两个字保持次要灰，
                                            // 只有**姓名**用蓝色（可点击进个人主页的那两个名字）。
                                            // 2026-10-27 第二十轮用户反馈：「A 回复 B」里点前面的 A
                                            // 能进主页、点后面的 B 不行——B 之前是画在一段
                                            // AnnotatedString 里的死文本。现在拆成两段，B 自己
                                            // 带点击区域，轻点就进那个人的主页。
                                            Text(
                                                text = " $replyInline ",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = UserInfoCache.nameOf(replyTo),
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable { onOpenProfile(replyTo) },
                                            )
                                        }
                                    }
                                    // 评论正文同样可选可复制（长按选中）
                                    SelectionContainer {
                                        Text(
                                            text = comment.content.orEmpty(),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                    }
                                    // 2026-10-28 第三十一轮（用户：「把评论的删除功能做到评论的左下角，
                                    // 蓝色字体，点击后询问是否删除，参考帖子删除的实现」）：
                                    // 只有自己的评论露这颗键；点它 = 原来的长按，走同一条
                                    // commentDeleteTarget → showMiuixConfirm 二次确认链路。
                                    if (!uid.isNullOrEmpty() && uid == Session.userId) {
                                        Text(
                                            text = stringResource(R.string.moments_delete),
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onCommentDelete(comment) }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentImageGrid(media: List<MediaItem>, onImage: (List<String>, Int, android.graphics.Rect?) -> Unit) {
    val urls = media.map { it.url?.takeIf { u -> u.isNotBlank() } ?: it.displayUrl.orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        media.chunked(3).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowItems.forEachIndexed { colIndex, mediaItem ->
                    val index = rowIndex * 3 + colIndex
                    // 记录缩略图在窗口里的位置：预览页用它做「从缩略图放大 / 缩回原位」
                    var bounds by remember(index) { mutableStateOf<android.graphics.Rect?>(null) }
                    AsyncImage(
                        model = mediaItem.displayUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                bounds = android.graphics.Rect(
                                    b.left.toInt(), b.top.toInt(),
                                    b.right.toInt(), b.bottom.toInt(),
                                )
                            }
                            .clickable { onImage(urls, index, bounds) },
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
