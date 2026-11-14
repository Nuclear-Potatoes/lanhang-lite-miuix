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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanhang.miuix.R
import com.lanhang.miuix.net.ApiException
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.net.UserPrivacy
import com.lanhang.miuix.ui.miuix.CardActionBar
import com.lanhang.miuix.ui.miuix.CardActionChip
import com.lanhang.miuix.ui.miuix.showMiuixPicker
import com.lanhang.miuix.ui.miuix.pressableRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 账号与安全 → 权限控制。
 *
 * 2026-11-14 第七十三轮第 2 条移植自原版蓝航 `cn.edu.nchu.nahang.ui.PermissionControlActivity`：
 * 「谁能看到我的手机号 / 邮箱 / 头像……」这些可见性开关全部存在学校的用户中心里，
 * 读写走 `GET/POST /api/user/{get,set}UserStatus`（见 [RceApi.getUserPrivacy]）。
 *
 * 档位沿用原版：头像、加好友、群、置顶、语音视频是**三态**（所有人 / 仅老师 / 都不可见），
 * 手机号、邮箱是**两态**（所有人 / 仅老师）。改动只落在内存里，点底部「保存」才提交。
 */
@Composable
fun PrivacyControlScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<UserPrivacy?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(retry) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) { RceApi.getUserPrivacy() }
        result
            .onSuccess { draft = it; loading = false }
            .onFailure { e ->
                loading = false
                error = when {
                    e is ApiException && (e.code == 401 || e.code == 4010) ->
                        context.getString(R.string.privacy_no_login)
                    else -> context.getString(R.string.privacy_load_failed, e.message ?: e.javaClass.simpleName)
                }
            }
    }

    val all = stringResource(R.string.privacy_visible_all)
    val teacher = stringResource(R.string.privacy_visible_teacher)
    val none = stringResource(R.string.privacy_visible_none)
    val cancel = stringResource(R.string.common_cancel)
    // onClick lambda 不是 @Composable 作用域，stringResource 必须先在这里取好
    val tHead = stringResource(R.string.privacy_head)
    val tFriend = stringResource(R.string.privacy_friend)
    val tGroup = stringResource(R.string.privacy_group)
    val tPin = stringResource(R.string.privacy_pin)
    val tMessage = stringResource(R.string.privacy_message)
    val tTel = stringResource(R.string.privacy_tel)
    val tMail = stringResource(R.string.privacy_mail)
    val triItems = listOf(all, teacher, none)
    val twoItems = listOf(all, teacher)

    fun pickTri(title: String, current: Int, apply: (UserPrivacy, Int) -> UserPrivacy) {
        val p = draft ?: return
        showMiuixPicker(
            context = context,
            title = title,
            items = triItems,
            selectedIndex = current.coerceIn(0, 2),
            cancelText = cancel,
        ) { index -> draft = apply(p, index) }
    }

    fun pickTwo(title: String, current: Int, apply: (UserPrivacy, Int) -> UserPrivacy) {
        val p = draft ?: return
        showMiuixPicker(
            context = context,
            title = title,
            items = twoItems,
            selectedIndex = current.coerceIn(0, 1),
            cancelText = cancel,
        ) { index -> draft = apply(p, index) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        val p = draft
        if (loading) {
            item("loading") {
                Text(
                    text = stringResource(R.string.privacy_loading),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                )
            }
        } else if (p == null) {
            item("error") {
                SmallTitle(stringResource(R.string.privacy_control_title))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = error ?: stringResource(R.string.privacy_load_failed_short),
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SimpleRow(title = stringResource(R.string.privacy_retry)) { retry++ }
                }
            }
        } else {
            item("profile") {
                SmallTitle(stringResource(R.string.privacy_section_profile))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    PrivacyValueRow(tHead, triItems[p.headFlag.coerceIn(0, 2)]) {
                        pickTri(tHead, p.headFlag) { q, v -> q.copy(headFlag = v) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PrivacyValueRow(tFriend, triItems[p.friendFlag.coerceIn(0, 2)]) {
                        pickTri(tFriend, p.friendFlag) { q, v -> q.copy(friendFlag = v) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PrivacyValueRow(tGroup, triItems[p.groupFlag.coerceIn(0, 2)]) {
                        pickTri(tGroup, p.groupFlag) { q, v -> q.copy(groupFlag = v) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PrivacyValueRow(tPin, triItems[p.pinFlag.coerceIn(0, 2)]) {
                        pickTri(tPin, p.pinFlag) { q, v -> q.copy(pinFlag = v) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PrivacyValueRow(tMessage, triItems[p.messageFlag.coerceIn(0, 2)]) {
                        pickTri(tMessage, p.messageFlag) { q, v -> q.copy(messageFlag = v) }
                    }
                }
            }
            item("contact") {
                SmallTitle(stringResource(R.string.privacy_section_contact))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    PrivacyValueRow(tTel, twoItems[p.telFlag.coerceIn(0, 1)]) {
                        pickTwo(tTel, p.telFlag) { q, v -> q.copy(telFlag = v) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PrivacyValueRow(tMail, twoItems[p.mailFlag.coerceIn(0, 1)]) {
                        pickTwo(tMail, p.mailFlag) { q, v -> q.copy(mailFlag = v) }
                    }
                }
            }
            item("hide") {
                SmallTitle(stringResource(R.string.privacy_section_hide))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    PrivacySwitchRow(
                        title = stringResource(R.string.privacy_hide_tel),
                        checked = p.hideTelFlag != 0,
                    ) { on -> draft = p.copy(hideTelFlag = if (on) 1 else 0) }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PrivacySwitchRow(
                        title = stringResource(R.string.privacy_hide_office),
                        checked = p.hideOfficeFlag != 0,
                    ) { on -> draft = p.copy(hideOfficeFlag = if (on) 1 else 0) }
                }
            }
            item("save") {
                CardActionBar(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    CardActionChip(
                        text = stringResource(if (saving) R.string.privacy_saving else R.string.privacy_save),
                        onClick = {
                            if (!saving) {
                            saving = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { RceApi.setUserPrivacy(p) }
                                saving = false
                                result
                                    .onSuccess {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.privacy_saved),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    .onFailure { e ->
                                        error = e.message
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.privacy_save_failed,
                                                e.message ?: e.javaClass.simpleName,
                                            ),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                            }
                            }
                        },
                        primary = true,
                        enabled = !saving,
                    )
                }
            }
        }
    }
}

/** 「标题 —— 当前档位 ›」一行 */
@Composable
private fun PrivacyValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "  \u203A",
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 「标题 + 开关」一行 */
@Composable
private fun PrivacySwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 只有一行的可点条目（用于「重试」） */
@Composable
private fun SimpleRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick, corner = 16.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}
