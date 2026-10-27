package com.lanhang.miuix.core

import java.io.IOException

/**
 * 接口或数据层的失败。
 *
 * 文案以 [TextRef] 形式携带，**不在抛出点解析成字符串**：这样语言在展示时才确定，
 * 抛出方也不需要持有 `Context`。
 *
 * [message] 只用于日志与崩溃报告（`ApiException(code): <可读标签>`），不要拿它当界面文案；
 * 界面层用 `Throwable.userMessage(context, 操作名资源)` 或 `ApiException.text.resolve(context)`。
 */
class ApiException(
    val code: Int,
    val text: TextRef,
    cause: Throwable? = null,
) : IOException("ApiException($code): ${text.logLabel()}", cause)
