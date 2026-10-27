package com.lanhang.miuix.moments

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.miuix.pressableRow
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 工作圈 → 工作圈过滤（第三十一轮新增，移植上游的同名功能）。
 *
 * 交互照 Miuix 的偏好页来：一组一张卡，行与行之间一条发丝分隔线，
 * 开关在右侧；自定义规则用「输入框 + 添加」而不是弹窗 —— 用户往往会连着加两三条，
 * 弹窗每次都要重新等键盘。
 *
 * 所有内容都是**本机过滤**：不改服务端，关掉开关立刻恢复全量，
 * 页脚把这两件事写清楚，避免用户以为「动态被我删了」。
 */
@Composable
fun MomentsFilterScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    // SP 里是真源，这里只是把它读进 Compose 状态（写的时候同时写回 SP）
    var enabled by remember { mutableStateOf(MomentsFilterStore.enabled) }
    var onPresets by remember {
        mutableStateOf(MomentsFilterStore.Preset.entries.filter { MomentsFilterStore.isPresetOn(it) }.toSet())
    }
    var rules by remember { mutableStateOf(MomentsFilterStore.customRules()) }
    var input by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    val addEmpty = stringResource(R.string.moments_filter_add_empty)
    val addTooLong = stringResource(R.string.moments_filter_add_too_long, MomentTextMatcher.MAX_QUERY_LENGTH)
    val addManyWords = stringResource(R.string.moments_filter_add_many_words, MomentTextMatcher.MAX_RULE_WORDS)
    val addManyRules = stringResource(R.string.moments_filter_add_many_rules, MomentsFilterStore.MAX_CUSTOM_RULES)
    val addDuplicate = stringResource(R.string.moments_filter_add_duplicate)

    fun addRule() {
        notice = when (MomentsFilterStore.addCustomRule(input)) {
            MomentsFilterStore.AddResult.OK -> {
                input = ""
                rules = MomentsFilterStore.customRules()
                null
            }
            MomentsFilterStore.AddResult.EMPTY -> addEmpty
            MomentsFilterStore.AddResult.TOO_LONG -> addTooLong
            MomentsFilterStore.AddResult.TOO_MANY_WORDS -> addManyWords
            MomentsFilterStore.AddResult.TOO_MANY_RULES -> addManyRules
            MomentsFilterStore.AddResult.DUPLICATE -> addDuplicate
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        item("switch") {
            FilterSectionLabel(stringResource(R.string.moments_filter_section_main))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.moments_filter_enable_title),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.moments_filter_enable_summary),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            MomentsFilterStore.enabled = it
                        },
                    )
                }
            }
        }

        item("presetsLabel") {
            FilterSectionLabel(stringResource(R.string.moments_filter_section_preset))
        }
        item("presets") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                MomentsFilterStore.Preset.entries.forEachIndexed { index, preset ->
                    if (index > 0) FilterDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(preset.titleRes),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = preset.words.joinToString(" · "),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = preset in onPresets,
                            onCheckedChange = { on ->
                                MomentsFilterStore.setPreset(preset, on)
                                onPresets = MomentsFilterStore.Preset.entries
                                    .filter { MomentsFilterStore.isPresetOn(it) }
                                    .toSet()
                            },
                        )
                    }
                }
            }
        }

        item("customLabel") {
            FilterSectionLabel(stringResource(R.string.moments_filter_section_custom))
        }
        item("customInput") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(
                        value = input,
                        onValueChange = {
                            input = it
                            notice = null
                        },
                        label = stringResource(R.string.moments_filter_add_hint),
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.moments_filter_rule_count,
                                rules.size,
                                MomentsFilterStore.MAX_CUSTOM_RULES,
                            ),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(text = stringResource(R.string.moments_filter_add), onClick = { addRule() })
                    }
                    // 添加失败的原因就地显示，比弹窗轻，也不打断连续添加
                    val tip = notice
                    if (tip != null) {
                        Text(
                            text = tip,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        item("customList") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
            ) {
                if (rules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.moments_filter_custom_empty),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    rules.forEachIndexed { index, rule ->
                        if (index > 0) FilterDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = rule,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                            )
                            // 删除用蓝色文字（与工作圈里删评论/删动态同一套说法）
                            Text(
                                text = stringResource(R.string.moments_delete),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressableRow(
                                        onClick = {
                                            MomentsFilterStore.removeCustomRule(rule)
                                            rules = MomentsFilterStore.customRules()
                                        },
                                        corner = 8.dp,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }

        item("footer") {
            Text(
                text = stringResource(R.string.moments_filter_footer),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            )
            Box(Modifier.height(8.dp))
        }
    }
}

/** 分组标题（与「我的」页里的 `SmallTitle` 同款视觉：左边缩进 28dp、次要色、footnote1） */
@Composable
internal fun FilterSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** 卡内行分隔线（左右缩进 16dp，与「我的」页设置卡一致） */
@Composable
internal fun FilterDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
