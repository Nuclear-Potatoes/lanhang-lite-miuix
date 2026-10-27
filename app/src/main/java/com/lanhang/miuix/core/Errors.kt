package com.lanhang.miuix.core

import android.content.Context
import androidx.annotation.StringRes
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/**
 * 沿 `cause` 链收集诊断信息，用于「用户能看懂的操作名 + 能定位的原因」。
 *
 * 关键约束：**`CancellationException` 必须原样上抛**。界面销毁、切换 Tab 都会取消协程，
 * 若在 `catch (e: Exception)` 里把它当成失败弹 toast，表现就是「切页时莫名报错」。
 */
fun Throwable.diagnosticMessage(): String {
    if (this is CancellationException) throw this
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val details = linkedSetOf<String>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        details += current.localizedMessage?.trim()?.takeIf { it.isNotEmpty() }
            ?: current.javaClass.simpleName.takeIf { it.isNotEmpty() }
            ?: current.javaClass.name
        current = current.cause
    }
    return details.joinToString("\n")
}

/** 失败明细：`ApiException` 用其本地化文案，其余异常用原因链 */
fun Throwable.userDetail(context: Context): String =
    (this as? ApiException)?.text?.resolve(context) ?: diagnosticMessage()

/** 「操作名 + 明细」；[operationRes] 是形如「加载失败：%1$s」的模板资源 */
fun Throwable.userMessage(context: Context, @StringRes operationRes: Int): String =
    context.getString(operationRes, userDetail(context))

/** 取消不算失败：调用方应直接 return，不弹提示、不写诊断 */
val Throwable.isCancellation: Boolean
    get() = this is CancellationException || cause is CancellationException
