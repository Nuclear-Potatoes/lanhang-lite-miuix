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
import com.lanhang.miuix.ui.miuix.pressableRow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
                        OaWebViewActivity.open(
                            context = context,
                            url = URL_CHANGE_PASSWORD,
                            appToken = null,
                            title = context.getString(R.string.account_change_password),
                        )
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AccountRow(
                    title = stringResource(R.string.account_change_mobile),
                    summary = stringResource(R.string.account_change_mobile_summary),
                    onClick = {
                        OaWebViewActivity.open(
                            context = context,
                            url = URL_CHANGE_MOBILE,
                            appToken = null,
                            title = context.getString(R.string.account_change_mobile),
                        )
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

/** 一行「标题 + 说明 + ›」，与设置页其它行同一套尺寸 */
@Composable
private fun AccountRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            text = "  \u203A",
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
