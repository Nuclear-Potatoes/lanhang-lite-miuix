package com.lanhang.miuix.grades

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.isDarkTheme
import com.lanhang.miuix.ui.miuix.liftForDark
import com.lanhang.miuix.ui.miuix.refreshTexts
import com.lanhang.miuix.ui.miuix.showMiuixPicker
import com.lanhang.miuix.ui.shell.ReportListScroll
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.shell.ScrollToTop
import com.lanhang.miuix.ui.shell.animateScrollToTopSlow
import com.lanhang.miuix.ui.shell.ShellState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background

/**
 * 成绩页（Miuix 版）。
 *
 * 布局照 Miuix 的惯例：胶囊切换学期 → 一张统计卡 → 一张成绩列表卡（行内分割线内缩）。
 * 数据依旧走 [GradesRepository]（缓存优先，下拉才联网）。
 */
@Composable
fun GradesScreen(
    modifier: Modifier = Modifier,
    /**
     * 列表滚动位置由**外面**（MineScreen 的 PageListStates）持有。
     * 成绩页是「我的」栈里的二级页，进别页再回来时本 Composable 会被销毁重建，
     * 只有把状态挂到外面才能记住滚到哪了（用户第九轮第 3 条）。
     */
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var semesters by remember { mutableStateOf<List<GradeSemester>>(emptyList()) }
    var scores by remember { mutableStateOf<List<GradeItem>>(emptyList()) }
    var selectedTerm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var emptyText by remember { mutableStateOf<String?>(null) }

    suspend fun loadScores(term: String, network: Boolean) {
        if (term.isEmpty()) return
        if (network) refreshing = true else loading = true
        try {
            var data = withContext(Dispatchers.IO) { GradesRepository.loadCachedScores(term) }
            if (network || data == null) {
                data = withContext(Dispatchers.IO) { GradesRepository.refreshScores(term) }
            }
            scores = data.orEmpty()
            emptyText = if (scores.isEmpty()) context.getString(R.string.grades_empty) else null
        } catch (e: Exception) {
            val msg = context.getString(R.string.grades_load_failed, e.message.orEmpty())
            if (scores.isEmpty()) emptyText = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } finally {
            loading = false
            refreshing = false
        }
    }

    suspend fun loadSemesters() {
        val cached = withContext(Dispatchers.IO) { GradesRepository.loadCachedSemesters() }
        if (!cached.isNullOrEmpty()) {
            semesters = cached
            selectedTerm = pickTerm(cached)
            loadScores(selectedTerm, network = false)
        }
        try {
            val fresh = withContext(Dispatchers.IO) { GradesRepository.refreshSemesters() }
            semesters = fresh
            val term = pickTerm(fresh)
            if (term.isNotEmpty()) {
                selectedTerm = term
                loadScores(term, network = false)
            }
        } catch (e: Exception) {
            if (semesters.isEmpty()) {
                emptyText = context.getString(R.string.grades_load_failed, e.message.orEmpty())
                Toast.makeText(context, emptyText, Toast.LENGTH_SHORT).show()
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadSemesters() }

    // 列表往下滑 → 顶栏收起
    ReportListScroll(listState, ShellState.TAB_MINE)

    // 双击底栏「成绩」回到顶部
    LaunchedEffect(ScrollToTop.tick) {
        if (ScrollToTop.tab == ShellState.TAB_MINE) listState.animateScrollToTopSlow()
    }

    // 页面根必须有底色：切 Tab / 进二级页时两页会重叠，透明根会把下层页面透出来
    Column(modifier = modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        // 学期切换：Miuix 的 TabRow（分段控件）—— 自带滑动指示器动画，
        // 且选中/未选中用同一套字号（之前用 Button+TextButton 混搭，选中态字号被 Button
        // 的默认 textStyle 顶掉，视觉上“选中的反而变小”）
        if (semesters.isNotEmpty()) {
            val selectedIndex = semesters
                .indexOfFirst { it.id.orEmpty() == selectedTerm }
                .coerceAtLeast(0)
            // 标签用学期 ID（2024-2025-1），不用后端的长名称「2024-2025学年第1学期」
            val labels = semesters.map { it.id?.takeIf { id -> id.isNotBlank() } ?: it.name.orEmpty() }
            val ctx = LocalContext.current
            // 2026-10-25 用户反馈「年份交互栏不符合 Miuix 设计规范」：
            // 原来是一排等宽 TabRow 胶囊，3 个学期撑满整行、选中态像个分段控件，
            // 和 Miuix 设置页的行式交互不一致。改成 Miuix 的 ArrowPreference 行：
            // 「学期   2024-2025-1 ›」，点开是 Miuix 选择对话框——和课表「跳转周次」同一套。
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.grades_semester),
                    summary = labels.getOrElse(selectedIndex) { "" },
                    onClick = {
                        showMiuixPicker(
                            context = ctx,
                            title = ctx.getString(R.string.grades_semester),
                            items = labels,
                            selectedIndex = selectedIndex,
                            cancelText = ctx.getString(R.string.common_cancel),
                            onPick = { index ->
                                val term = semesters.getOrNull(index)?.id.orEmpty()
                                if (term.isNotEmpty() && term != selectedTerm) {
                                    selectedTerm = term
                                    Session.gradesTerm = term
                                    scope.launch { loadScores(term, network = true) }
                                }
                            },
                        )
                    },
                )
            }
        }

        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    if (selectedTerm.isNotEmpty()) loadScores(selectedTerm, network = true)
                    else loadSemesters()
                }
            },
            modifier = Modifier.fillMaxSize(),
            refreshTexts = refreshTexts(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading && scores.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        // 2026-10-26 第八轮（用户反馈「成绩页要展示加权平均分、总学分、课程数，
                        // Miuix 化前的版本有这个功能」）：统计卡原来是写在「列表非空」分支里的，
                        // 当前学期还没出成绩时整块消失，用户就只看到一句「本学期暂无成绩」。
                        // 现在统计卡**永远在**（没成绩时加权平均显示 `--`、学分/课程数为 0，
                        // 和改版前 GradesFragment.renderStats() 的口径一致），空态提示排在它下面。
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            item(key = "stats") { GradeStatsCard(scores) }
                            if (scores.isEmpty()) {
                                item(key = "empty") {
                                    GradeEmptyCard(
                                        emptyText = emptyText,
                                        hint = if (semesters.size > 1) {
                                            stringResource(R.string.grades_empty_hint)
                                        } else {
                                            null
                                        },
                                    )
                                }
                            } else {
                                item(key = "listHeader") { Spacer(Modifier.height(2.dp)) }
                                item(key = "list") { GradeListCard(scores) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 上次选择 → 当前学期 → 第一项 */
private fun pickTerm(list: List<GradeSemester>): String {
    val saved = Session.gradesTerm
    val current = list.firstOrNull { it.isCurrent }?.id
    return when {
        saved.isNotEmpty() && list.any { it.id == saved } -> saved
        !current.isNullOrEmpty() -> current
        else -> list.firstOrNull()?.id.orEmpty()
    }
}

/** 本学期没有成绩时的提示卡（统计卡仍在它上面，见调用处注释） */
@Composable
private fun GradeEmptyCard(emptyText: String?, hint: String? = null) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = emptyText ?: stringResource(R.string.grades_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hint,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun GradeStatsCard(scores: List<GradeItem>) {
    val credits = scores.sumOf { it.creditValue ?: 0.0 }
    val graded = scores.filter { it.scoreValue != null }
    val weighted = graded.sumOf { (it.scoreValue ?: 0) * (it.creditValue ?: 1.0) }
    val weightSum = graded.sumOf { it.creditValue ?: 1.0 }
    val avg = if (weightSum > 0) String.format(java.util.Locale.CHINA, "%.1f", weighted / weightSum) else "--"

    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
        ) {
            StatCell(avg, stringResource(R.string.grades_weighted_avg), Modifier.weight(1f))
            StatCell(formatNum(credits), stringResource(R.string.grades_total_credits), Modifier.weight(1f))
            StatCell(scores.size.toString(), stringResource(R.string.grades_course_count), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun GradeListCard(scores: List<GradeItem>) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        scores.forEachIndexed { index, item ->
            GradeRow(item)
            if (index != scores.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 16.dp))
            }
        }
    }
}

@Composable
private fun GradeRow(item: GradeItem) {
    val parts = listOfNotNull(
        item.creditValue?.let { stringResource(R.string.grades_credit, item.credit.orEmpty()) },
        item.nature?.takeIf { it.isNotBlank() } ?: item.attribute,
        item.examMode?.takeIf { it.isNotBlank() },
    ).filter { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.courseName.orEmpty(), style = MiuixTheme.textStyles.body1)
            if (parts.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = parts.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = item.score ?: "--",
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            color = item.scoreValue?.let { scoreColor(it, isDarkTheme()) }
                ?: MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/**
 * 分数配色（语义色，不跟主题走，保证一眼看出高低）。
 *
 * 2026-10-27 第二十轮：`night = true` 时统一提亮一档——深色卡片上原来的
 * `#3482FF` / `#2E9E5B` 都偏暗，看不清是「良好」还是「及格」。
 */
internal fun scoreColor(score: Int, night: Boolean = false): Color {
    val base = when {
        score >= 90 -> Color(0xFF2E9E5B)
        score >= 80 -> Color(0xFF3482FF)
        score >= 60 -> Color(0xFFB26A00)
        else -> Color(0xFFE94634)
    }
    return if (night) base.liftForDark() else base
}

private fun formatNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.CHINA, "%.1f", v)

@Suppress("unused")
private fun itemsOf(list: List<GradeItem>) = list
