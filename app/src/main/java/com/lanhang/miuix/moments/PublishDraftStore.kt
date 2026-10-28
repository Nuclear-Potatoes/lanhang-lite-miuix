package com.lanhang.miuix.moments

import android.content.Context
import com.lanhang.miuix.schedule.AppContext

/** 发布动态的草稿（第四十五轮，用户第 8 条） */
data class PublishDraft(val text: String, val images: List<String>)

/**
 * 发布页草稿：**用户点了「保存草稿」才存在**，点了「不保存」立刻销毁。
 *
 * 用户原话：「发布工作圈时，如果输入了文字或者导入了图片，返回时应当询问是否保存草稿
 * （是则保留、否则销毁）」。
 *
 * 只存文本与图片 Uri 字符串（不含 bitmap）：恢复时逐条 `Uri.parse`，读不出来的那条直接跳过，
 * 这样即使系统清掉了图片权限也不会崩。图片允许多选 9 张，用换行分隔存成一条字符串。
 */
object PublishDraftStore {
    private const val PREF = "lanhang_publish_draft"
    private const val KEY_TEXT = "text"
    private const val KEY_IMAGES = "images"

    private fun prefs() = AppContext.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(): PublishDraft? {
        val p = prefs()
        val text = p.getString(KEY_TEXT, null).orEmpty()
        val images = p.getString(KEY_IMAGES, null)
            .orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (text.isBlank() && images.isEmpty()) return null
        return PublishDraft(text, images)
    }

    fun save(text: String, images: List<String>) {
        runCatching {
            prefs().edit()
                .putString(KEY_TEXT, text)
                .putString(KEY_IMAGES, images.joinToString("\n"))
                .apply()
        }
    }

    fun clear() {
        runCatching { prefs().edit().clear().apply() }
    }
}
