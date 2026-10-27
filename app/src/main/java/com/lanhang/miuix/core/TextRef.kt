package com.lanhang.miuix.core

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * 一条界面文案的「引用」，而不是字符串本身。
 *
 * 为什么不在抛出/构造的地方直接 `getString`：
 * 1. 语言在那一刻被固化，异常被延迟展示（toast、对话框、诊断报告）时可能已经换了语言；
 * 2. 网络层与仓库层被迫依赖 `R` 的运行时值和全局 `AppContext`，脱离 Android 就无法测试；
 * 3. `AppContext` 尚未 init 时直接崩。
 *
 * 约定：**只有 UI 层（Fragment / Activity / Adapter / Renderer）可以 [resolve]**，
 * 其他层只负责传递与拼装。桌面小部件是例外——`RemoteViews` 拿不到资源引用，
 * 所以必须在 `ScheduleWidgetData` 组装快照时就 resolve 成 String。
 */
sealed interface TextRef {

    /** 资源引用；[args] 里可以再嵌套 [TextRef]，resolve 时会先展开 */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : TextRef {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    /** 外部原文：服务端 msg、异常 message。**不翻译**，原样展示 */
    data class Raw(val text: String) : TextRef

    /** 多段拼接，默认换行 */
    data class Joined(val parts: List<TextRef>, val separator: String = "\n") : TextRef
}

fun TextRef.resolve(context: Context): String = resolve(context.resources)

fun TextRef.resolve(resources: Resources): String = when (this) {
    is TextRef.Raw -> text
    is TextRef.Res -> resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
    is TextRef.Joined -> parts.joinToString(separator) { it.resolve(resources) }
}

/** 诊断/日志用标签：不含用户语言，便于在 diagnostics.log 里稳定检索 */
fun TextRef.logLabel(): String = when (this) {
    is TextRef.Raw -> text
    is TextRef.Res -> "#$id"
    is TextRef.Joined -> parts.joinToString(" | ") { it.logLabel() }
}

private fun Any.resolveArg(resources: Resources): Any =
    if (this is TextRef) resolve(resources) else this
