package com.lanhang.miuix.moments

import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.net.cleanOrgName
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作圈搜索页（第三十一轮新增，第三十二轮按用户要求改成**整条动态**）。
 *
 * 只搜索**已经加载到本机的动态**：工作圈是分页接口，服务端没有提供搜索能力，
 * 所以这里不做「搜全站」的假承诺 —— 界面上直接写清楚范围，用户继续下滑加载后再搜即可。
 *
 * 匹配规则与「工作圈过滤」共用 [MomentTextMatcher]：全角转半角、忽略宽窄写、
 * 多个词用空格分隔表示**同时包含**（AND 收窄）。命中片段铺一层主题色底。
 *
 * 结果不是「只把命中的那段文字抠出来」，而是**和动态流里那张卡一模一样**的完整内容：
 * 头像 / 姓名 / 时间 / 全文 / 图片九宫格 / 赞与评论数。点头像或蓝色姓名 → 个人主页；
 * 点卡片其它地方 → 回动态流并定位到这条（赞、评论都在那边做，绝不在这里摆假按钮）。
 */
@Composable
fun MomentSearchScreen(
    items: List<MomentItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPost: (MomentItem) -> Unit = {},
    onImage: (List<String>, Int, Rect?) -> Unit = { _, _, _ -> },
    listState: LazyListState = rememberLazyListState(),
) {
    val words = remember(query) { MomentTextMatcher.words(query.take(MomentTextMatcher.MAX_QUERY_LENGTH)) }
    val results = remember(items, words) {
        if (words.isEmpty()) emptyList() else items.filter { item ->
            val text = item.content.text.orEmpty()
            MomentTextMatcher.matchesAll(text, words) ||
                MomentTextMatcher.contains(UserInfoCache.nameOf(item.feed.user_id), words.first())
        }
    }
    val timeFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }
    val accent = MiuixTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.moments_search_hint),
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Text(
            text = when {
                words.isEmpty() -> stringResource(R.string.moments_search_typing)
                results.isEmpty() -> stringResource(R.string.moments_search_empty)
                else -> stringResource(R.string.moments_search_count, results.size)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.feed.feed_id ?: it.hashCode() }) { item ->
                SearchResultCard(
                    item = item,
                    words = words,
                    accent = accent,
                    timeText = item.feed.create_dt?.let { timeFormat.format(Date(it)) }.orEmpty(),
                    onOpenProfile = onOpenProfile,
                    onOpenPost = onOpenPost,
                    onImage = onImage,
                )
            }
            if (results.isNotEmpty()) {
                item("scope") {
                    Text(
                        text = stringResource(R.string.moments_search_scope, items.size),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 一条完整的动态（搜索结果的载体）。
 *
 * 版式与动态流里的 `MomentCard` 保持一致：40dp 头像、姓名 + 时间、全文、九宫格、
 * 赞/评论计数。区别只有两处：
 * 1. 姓名用主色（用户说的「蓝色姓名」）—— 明确它可点；
 * 2. 赞/评论只是**计数**，不做假按钮；要互动就点卡片回动态流定位。
 */
@Composable
private fun SearchResultCard(
    item: MomentItem,
    words: List<String>,
    accent: Color,
    timeText: String,
    onOpenProfile: (String) -> Unit,
    onOpenPost: (MomentItem) -> Unit,
    onImage: (List<String>, Int, Rect?) -> Unit,
) {
    val uid = item.feed.user_id
    val user = UserInfoCache.get(uid)
    val name = user?.name ?: stringResource(R.string.moments_anonymous)
    val text = item.content.text.orEmpty()
    val media = item.content.media
        ?.filter { !it.displayUrl.isNullOrBlank() }
        .orEmpty()

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 5.dp)
            // 整卡可点：回动态流并定位到这条（互动在那边完成）。clip 必须在 clickable 之前，
            // 否则按压高亮是个直角方块（AGENTS.md 第十九轮硬规则）。
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpenPost(item) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanhangAvatar(
                    url = user?.portrait_url,
                    name = name,
                    size = 40.dp,
                    modifier = Modifier.clickable { uid?.let(onOpenProfile) },
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { uid?.let(onOpenProfile) },
                    )
                    val sub = listOfNotNull(user?.depart_name, user?.duty_name)
                        .mapNotNull { it.cleanOrgName() }
                        .joinToString(" · ")
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
                    text = timeText,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            if (text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(
                        text = highlight(text, words, accent),
                        style = MiuixTheme.textStyles.body1,
                    )
                }
            }

            if (media.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MomentImageGrid(media, onImage)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_favorite_outline),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.height(14.dp).width(14.dp),
                )
                Text(
                    text = stringResource(R.string.moments_like, item.likes.size),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_mode_comment),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.height(14.dp).width(14.dp),
                )
                Text(
                    text = stringResource(R.string.moments_comment_count, item.comments.size),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.moments_search_open_post),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 把命中的片段铺上主题色底（透明度 28%，深色下也读得清字） */
private fun highlight(
    text: String,
    words: List<String>,
    accent: Color,
): AnnotatedString {
    val hits = MomentTextMatcher.hits(text, words)
    if (hits.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        hits.forEach { hit ->
            if (hit.start in 0 until hit.end && hit.end <= text.length) {
                addStyle(SpanStyle(background = accent.copy(alpha = 0.28f)), hit.start, hit.end)
            }
        }
    }
}
