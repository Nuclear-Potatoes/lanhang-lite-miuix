package com.lanhang.miuix.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanhang.miuix.R
import com.lanhang.miuix.ui.OaWebViewActivity
import com.lanhang.miuix.ui.miuix.SettingRow
import com.lanhang.miuix.ui.miuix.pressableRow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.rememberCoroutineScope
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学校统一身份认证里的「修改密码」页。
 *
 * 2026-11-14 第七十三轮第 2 条：用户要求「在设置-账号功能下加入修改密码、修改手机号」，
 * 并说「具体细节可以参考原蓝航 app」。原版 `SettingActivity` 的 `liv_setting_password`
 * 点下去并没有本地表单，而是打开同一个 `RceWebViewActivity` 加载这个地址；
 * 改密码/换手机号都要短信校验，属于统一身份认证的地盘，搬原生表单反而做不全。
 */
private const val URL_CHANGE_PASSWORD = "https://go.nchu.edu.cn/?id=79"

/** 同上，「修改手机号」用的地址（原版 `liv_setting_mobile`） */
private const val URL_CHANGE_MOBILE = "https://go.nchu.edu.cn/?id=83"

/**
 * 设置 → 账号与安全。
 *
 * 三行：修改密码、修改手机号（都跳学校统一身份认证的 H5）、权限控制（本地二级页）。
 * 顶栏标题与返回键由 [com.lanhang.miuix.mine.MineFragment] 的栈统一管理。
 */
@Composable
fun AccountSecurityScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOpenPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        item("security") {
            SmallTitle(stringResource(R.string.account_security_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                AccountRow(
                    title = stringResource(R.string.account_change_password),
                    summary = stringResource(R.string.account_change_password_summary),
                    onClick = {
                        scope.launch {
                            val fresh = withContext(Dispatchers.IO) {
                                runCatching { RceApi.getLdapToken() }.getOrNull()
                                    ?.takeIf { it.isNotBlank() }
                            }
                            fresh?.let { Session.ldapToken = it }
                            OaWebViewActivity.open(
                                context = context,
                                url = URL_CHANGE_PASSWORD,
                                appToken = fresh,
                                title = context.getString(R.string.account_change_password),
                            )
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AccountRow(
                    title = stringResource(R.string.account_change_mobile),
                    summary = stringResource(R.string.account_change_mobile_summary),
                    onClick = {
                        scope.launch {
                            val fresh = withContext(Dispatchers.IO) {
                                runCatching { RceApi.getLdapToken() }.getOrNull()
                                    ?.takeIf { it.isNotBlank() }
                            }
                            fresh?.let { Session.ldapToken = it }
                            OaWebViewActivity.open(
                                context = context,
                                url = URL_CHANGE_MOBILE,
                                appToken = fresh,
                                title = context.getString(R.string.account_change_mobile),
                            )
                        }
                    },
                )
            }
        }
        item("privacy") {
            SmallTitle(stringResource(R.string.account_privacy_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                AccountRow(
                    title = stringResource(R.string.privacy_control_title),
                    summary = stringResource(R.string.privacy_control_summary),
                    onClick = onOpenPrivacy,
                )
            }
        }
    }
}

/**
 * 一行「标题 + 说明 + 箭头」。
 *
 * 第八十一轮：不再自己排版，直接走 [SettingRow]——和「设置」页同一套字号 / 行高 / 箭头，
 * 原来这里的 17sp Medium + 文本「›」比设置页更粗更矮，两页放在一起风格明显不一致。
 */
@Composable
private fun AccountRow(title: String, summary: String, onClick: () -> Unit) {
    SettingRow(title = title, summary = summary, onClick = onClick)
}
