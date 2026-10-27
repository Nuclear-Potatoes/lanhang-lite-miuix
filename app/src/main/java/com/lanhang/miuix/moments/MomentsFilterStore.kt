package com.lanhang.miuix.moments

import android.content.Context
import android.content.SharedPreferences
import com.lanhang.miuix.R
import com.lanhang.miuix.session.Session

/**
 * 工作圈「关键词过滤」的设置与判定。
 *
 * 从上游（Micheal-Reber/lanhang-lite）移植，语义原样保留：
 *  - 只做**本机显示层的隐藏**，不动服务端任何内容；关掉开关立刻恢复全量；
 *  - **自己发的不滤**（自己说的话自己总要看得到）；
 *  - **寻物 / 招领帖永不误伤**（[MomentTextMatcher.isProtected]）：样本里 18% 是找东西、
 *    还东西的帖子，字面特征和广告完全重合，宁可漏一条广告也不能把「捡到耳机」吞掉；
 *  - 预设只默认开「广告推广」：另外三档实测都有误伤（「有偿代拿」是同学互助、
 *    「转让」可能是正常内容），交给用户自己勾；
 *  - 自定义规则一条里可以写多个词（空格分隔），**同时包含**才算命中（AND，收窄）。
 *
 * 与上游的差别只有存储细节：本项目的字符串资源在自己的 `R` 里，其余键名/上限/顺序照抄。
 */
object MomentsFilterStore {

    /** 预设分档。词表来自上游 120 条真实样本的命中统计 */
    enum class Preset(
        val key: String,
        val titleRes: Int,
        val words: List<String>,
        val defaultOn: Boolean,
    ) {
        /** 高置信广告词：实测命中 10 条里 9 条确为广告 */
        AD(
            "ad", R.string.moments_filter_preset_ad,
            listOf(
                // 卖货话术
                "原价", "福利价", "特惠", "学生价", "领卷", "优惠券", "秒杀", "包邮", "返红包",
                "代购", "开团", "接单", "下单",
                // 兼职刷单
                "兼职", "招募", "日结", "刷单", "代刷", "代考",
                // 引流
                "加vx", "加v", "加微信", "vx",
                // 培训招生
                "招生", "集训", "实训", "训练营",
            ),
            defaultOn = true,
        ),

        /** 「有偿」会打到同学之间的跑腿互助，所以默认关 */
        PART_TIME(
            "part_time", R.string.moments_filter_preset_part_time,
            listOf("招人", "家教", "助教", "客服", "有偿", "招聘"),
            defaultOn = false,
        ),

        SECOND_HAND(
            "second_hand", R.string.moments_filter_preset_second_hand,
            listOf("出二手", "收二手", "带价来", "出闲置", "转让", "面交"),
            defaultOn = false,
        ),

        HOUSING(
            "housing", R.string.moments_filter_preset_housing,
            listOf("出租", "合租", "转租", "单间", "求租"),
            defaultOn = false,
        ),
    }

    /** 命中的规则：预设档位或自定义规则，二选一 */
    data class RuleHit(val preset: Preset?, val customRule: String?)

    /** 添加自定义规则的结果（每一种都要给用户一句不同的提示，否则「点了没反应」） */
    enum class AddResult { OK, EMPTY, TOO_LONG, TOO_MANY_WORDS, TOO_MANY_RULES, DUPLICATE }

    private const val FILE = "moments_filter"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESETS = "presets"
    private const val KEY_CUSTOM = "custom_rules"
    private const val KEY_SEEDED = "seeded"

    private const val SEPARATOR = '\n'

    /** 自定义规则条数上限：再多就是自己给自己找麻烦，且每条都要对每条帖子跑一遍 */
    const val MAX_CUSTOM_RULES = 30

    private var sp: SharedPreferences? = null

    /** 在 `App.onCreate` 里调一次（Application Context，不会泄漏） */
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = prefs
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            // 首次运行只开默认档，并写盘 —— 这样用户关掉之后不会被「默认值」重新打开
            savePresets(Preset.entries.filter { it.defaultOn }.map { it.key }.toSet())
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    // ------------------------------------------------------------------ 开关

    /**
     * 总开关。**默认关闭**：这是会自动藏内容的开关，必须由用户亲手打开 ——
     * 「装好就少了几条动态」只会让人以为 App 出了 bug（2026-10-28 第三十一轮修正）。
     */
    var enabled: Boolean
        get() = sp?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) {
            sp?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    fun isPresetOn(preset: Preset): Boolean = preset.key in enabledPresetKeys()

    fun setPreset(preset: Preset, on: Boolean) {
        val keys = enabledPresetKeys().toMutableSet()
        if (on) keys.add(preset.key) else keys.remove(preset.key)
        savePresets(keys)
    }

    private fun enabledPresetKeys(): Set<String> {
        val raw = sp?.getString(KEY_PRESETS, null)
        if (raw == null) {
            return Preset.entries.filter { it.defaultOn }.map { it.key }.toSet()
        }
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    private fun savePresets(keys: Set<String>) {
        sp?.edit()?.putString(KEY_PRESETS, keys.joinToString(SEPARATOR.toString()))?.apply()
    }

    // ------------------------------------------------------------------ 自定义规则

    fun customRules(): List<String> {
        val raw = sp?.getString(KEY_CUSTOM, null) ?: return emptyList()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
    }

    fun addCustomRule(input: String): AddResult {
        val rule = input.trim().replace(Regex("\\s+"), " ")
        if (rule.isEmpty()) return AddResult.EMPTY
        if (rule.length > MomentTextMatcher.MAX_QUERY_LENGTH) return AddResult.TOO_LONG
        if (MomentTextMatcher.words(rule).size > MomentTextMatcher.MAX_RULE_WORDS) {
            return AddResult.TOO_MANY_WORDS
        }
        val list = customRules().toMutableList()
        if (list.any { it.equals(rule, ignoreCase = true) }) return AddResult.DUPLICATE
        if (list.size >= MAX_CUSTOM_RULES) return AddResult.TOO_MANY_RULES
        list.add(rule)
        saveRules(list)
        return AddResult.OK
    }

    fun removeCustomRule(rule: String) {
        val list = customRules().toMutableList()
        if (list.remove(rule)) saveRules(list)
    }

    private fun saveRules(list: List<String>) {
        sp?.edit()?.putString(KEY_CUSTOM, list.joinToString(SEPARATOR.toString()))?.apply()
    }

    // ------------------------------------------------------------------ 判定

    /** 当前生效的关键词条数（预设词 + 自定义规则条数），给副标题用 */
    fun activeKeywordCount(): Int {
        val presetWords = Preset.entries.filter { isPresetOn(it) }.sumOf { it.words.size }
        return presetWords + customRules().size
    }

    /** 当前有没有「实际在起作用」的规则（开关开着但一条规则都没勾 = 不过滤） */
    fun hasActiveRules(): Boolean = Preset.entries.any { isPresetOn(it) } || customRules().isNotEmpty()

    /**
     * 这条动态是否该被藏起来；命中就返回**为什么**，否则 null。
     *
     * @param text 正文（已拼好；不含作者名 —— 作者是自己的话直接豁免）
     * @param authorId 动态作者 userId
     */
    fun match(text: String, authorId: String?): RuleHit? {
        if (!enabled) return null
        if (text.isBlank()) return null
        // 自己发的永不过滤
        if (!authorId.isNullOrEmpty() && authorId == Session.userId) return null
        // 寻物 / 招领一律放过
        if (MomentTextMatcher.isProtected(text)) return null
        for (preset in Preset.entries) {
            if (!isPresetOn(preset)) continue
            if (MomentTextMatcher.matchesAny(text, preset.words)) return RuleHit(preset, null)
        }
        for (rule in customRules()) {
            val words = MomentTextMatcher.words(rule)
            if (words.isNotEmpty() && MomentTextMatcher.matchesAll(text, words)) {
                return RuleHit(null, rule)
            }
        }
        return null
    }

    /** 设置页「工作圈过滤」那一行的副标题 */
    fun summary(context: Context): String {
        if (!enabled) return context.getString(R.string.moments_filter_summary_off)
        val count = activeKeywordCount()
        if (count <= 0) return context.getString(R.string.moments_filter_summary_none)
        return context.getString(R.string.moments_filter_summary_on, count)
    }
}
