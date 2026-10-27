package com.lanhang.miuix.campusnet

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.core.userDetail
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.showMiuixConfirm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 校园网一键认证（2026-10-27 第十六轮，逻辑移植自原项目 `lanhang-lite` 的 campusnet 包、
 * 界面按 Miuix 规范重写）。
 *
 * 底层就是深澜 Srun 5.x 那套 JSONP 三段式（`get_challenge` → `srun_portal` → `rad_user_info`），
 * 见 [SrunApi]。这里只负责：
 * 1. 展示在线状态（IP / 在线时长 / 流量）；
 * 2. 账号密码输入 + 「记住密码」（密码走 Android Keystore AES-GCM，只有本机能解，明文不落盘）；
 * 3. 立即认证 / 下线（两个动作都必须**先连上 NCHU_Wireless**，否则 [SrunApi.status] 直接报错）。
 *
 * 认证只发生在用户点按钮时：**不做后台自动重连**（用户没要求，也不该偷偷联网）。
 */
@Composable
fun CampusNetScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<SrunApi.NetStatus?>(null) }
    var statusError by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf(CampusNetStore.username.ifBlank { Session.username }) }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(CampusNetStore.remember) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }

    suspend fun refresh() {
        refreshing = true
        try {
            status = withContext(Dispatchers.IO) { SrunApi.status(context.applicationContext) }
            statusError = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            status = null
            statusError = e.userDetail(context)
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        // 密码从 Keystore 里解（放 IO，别在主线程做 crypto）
        password = runCatching {
            withContext(Dispatchers.IO) { CampusNetStore.password() }
        }.getOrNull().orEmpty()
        refresh()
    }

    val online = status?.online == true

    fun login() {
        if (busy) return
        val account = username.trim()
        if (account.isEmpty()) {
            info = context.getString(R.string.campus_net_empty_account)
            return
        }
        if (password.isEmpty()) {
            info = context.getString(R.string.campus_net_empty_password)
            return
        }
        busy = true
        info = ""
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    if (remember) CampusNetStore.savePassword(password) else {
                        CampusNetStore.clearPassword()
                        true
                    }
                }
                if (remember && !saved) info = context.getString(R.string.campus_net_save_failed)
                val result = withContext(Dispatchers.IO) {
                    CampusNetAuth.authenticate(context.applicationContext, account, password)
                }
                info = context.getString(
                    if (result.alreadyOnline) R.string.campus_net_already_online
                    else R.string.campus_net_login_ok
                )
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                info = context.getString(R.string.campus_net_failed, e.userDetail(context))
            } finally {
                busy = false
            }
        }
    }

    fun logout() {
        if (busy) return
        showMiuixConfirm(
            context = context,
            title = context.getString(R.string.campus_net_logout_confirm_title),
            message = context.getString(R.string.campus_net_logout_confirm_message),
            confirmText = context.getString(R.string.campus_net_logout),
            dismissText = context.getString(R.string.common_cancel),
            onConfirm = {
                busy = true
                info = ""
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            CampusNetAuth.logout(
                                context.applicationContext,
                                status?.username?.takeIf { it.isNotBlank() } ?: username.trim(),
                            )
                        }
                        info = context.getString(R.string.campus_net_logout_ok)
                        refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        info = context.getString(R.string.campus_net_failed, e.userDetail(context))
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        // ---------------- 状态 ----------------
        SectionLabel(stringResource(R.string.campus_net_tag))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (refreshing && status == null) {
                        InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = when {
                            refreshing && status == null -> stringResource(R.string.work_loading)
                            statusError.isNotEmpty() -> stringResource(R.string.campus_net_status_unknown)
                            online -> stringResource(R.string.campus_net_status_online)
                            else -> stringResource(R.string.campus_net_status_offline)
                        },
                        style = MiuixTheme.textStyles.title4,
                        color = if (online) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        text = stringResource(R.string.campus_net_refresh),
                        onClick = { if (!refreshing) scope.launch { refresh() } },
                        enabled = !refreshing,
                    )
                }
                val detail = when {
                    statusError.isNotEmpty() -> statusError
                    online -> {
                        val ip = status?.ip.orEmpty().ifBlank { "--" }
                        val hours = (status?.onlineSeconds ?: 0L) / 3600
                        val minutes = ((status?.onlineSeconds ?: 0L) % 3600) / 60
                        val traffic = trafficText(status?.bytes ?: 0L, context)
                        context.getString(
                            R.string.campus_net_online_detail,
                            ip,
                            context.getString(R.string.campus_net_duration, hours, minutes),
                            traffic,
                        )
                    }
                    status != null -> stringResource(R.string.campus_net_offline_detail)
                    else -> ""
                }
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ---------------- 账号 ----------------
        SectionLabel(stringResource(R.string.campus_net_account_section))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.campus_net_account),
                    maxLines = 1,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.campus_net_password),
                    maxLines = 1,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.campus_net_remember),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(checked = remember, onCheckedChange = { remember = it })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.campus_net_credential_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ---------------- 动作 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.campus_net_tag),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    // 只放一颗按钮会让这张卡左边空一大块，所以左边补一句状态，右边才是动作
                    Text(
                        text = stringResource(
                            if (online) R.string.campus_net_status_online
                            else R.string.campus_net_status_offline
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (online) {
                    TextButton(
                        text = stringResource(R.string.campus_net_logout),
                        onClick = { logout() },
                        enabled = !busy,
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.campus_net_login),
                        onClick = { login() },
                        enabled = !busy,
                    )
                }
            }
        }

        if (info.isNotEmpty()) {
            Text(
                text = info,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }

        // ---------------- 自助服务 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                // 第十九轮：整卡可点时也要裁成卡片圆角，不然按下去是直角方块
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    // 自助服务还是那张 H5（改密码 / 看在用设备），交给既有 WebView 页
                    com.lanhang.miuix.ui.OaWebViewActivity.open(
                        context,
                        SELF_SERVICE_URL,
                        null,
                        context.getString(R.string.campus_net_self_service),
                        null,
                    )
                },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.campus_net_self_service),
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.campus_net_self_service_subtitle),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.campus_net_hint),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
    }
}

/** 小标题：和「我的 / 设置」里的分组标题一致（footnote1 + 次级色 + 左侧缩进） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** 流量：MB 以下按 MB，超过 1024MB 用 GB */
private fun trafficText(bytes: Long, context: android.content.Context): String {
    val mb = bytes / (1024 * 1024)
    return if (mb >= 1024) {
        context.getString(R.string.campus_net_traffic_gb, (mb / 1024).toInt())
    } else {
        context.getString(R.string.campus_net_traffic_mb, mb.toInt())
    }
}

/** 自助服务入口（需在校园网内，校外会在统一身份认证回跳处中断） */
private const val SELF_SERVICE_URL = "http://srunwebserver.device.nchu.edu.cn:8800/site/nchu"
