package com.lanhang.miuix.moments

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作圈搜索页（第三十一轮新增，移植上游「工作圈关键词搜索」）。
 *
 * 只搜索**已经加载到本机的动态**：工作圈是分页接口，服务端没有提供搜索能力，
 * 所以这里不做「搜全站」的假承诺 —— 界面上直接写清楚范围，用户继续下滑加载后再搜即可。
 *
 * 匹配规则与「工作圈过滤」共用 [MomentTextMatcher]：全角转半角、忽略宽窄写、
 * 多个词用空格分隔表示**同时包含**（AND 收窄）。命中片段铺一层主题色底。
 */
@Composable
fun MomentSearchScreen(
    items: List<MomentItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
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
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 6.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.feed.feed_id ?: it.hashCode().toString() }) { item ->
                val uid = item.feed.user_id.orEmpty()
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        LanhangAvatar(
                            url = UserInfoCache.get(uid)?.portrait_url,
                            name = UserInfoCache.nameOf(uid),
                            size = 30.dp,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = UserInfoCache.nameOf(uid),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = accent,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                val time = item.feed.create_dt?.let { timeFormat.format(Date(it)) }.orEmpty()
                                if (time.isNotEmpty()) {
                                    Text(
                                        text = time,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            Text(
                                text = highlight(item.content.text.orEmpty(), words, accent),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
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

/** 把命中的片段铺上主题色底（透明度 28%，深色下也读得清字） */
private fun highlight(
    text: String,
    words: List<String>,
    accent: androidx.compose.ui.graphics.Color,
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
