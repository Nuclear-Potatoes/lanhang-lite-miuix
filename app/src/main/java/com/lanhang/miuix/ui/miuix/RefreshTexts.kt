package com.lanhang.miuix.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lanhang.miuix.R

/**
 * Miuix `PullToRefresh` 的提示文案。
 *
 * 库自带的默认值是写死的英文（"Pull down to refresh" / "Release to refresh" …），
 * 中文界面下拉刷新时就会冒出 "release to refresh"（用户 2026-10-24 反馈）。
 * 所有用 PullToRefresh 的页面都必须显式传这个函数，别再吃默认值。
 */
@Composable
fun refreshTexts(): List<String> = listOf(
    stringResource(R.string.refresh_pull),
    stringResource(R.string.refresh_release),
    stringResource(R.string.refresh_refreshing),
    stringResource(R.string.refresh_done),
)
