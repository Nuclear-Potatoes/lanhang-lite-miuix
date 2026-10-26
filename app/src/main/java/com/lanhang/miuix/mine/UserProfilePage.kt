package com.lanhang.miuix.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanhang.miuix.R
import com.lanhang.miuix.moments.CommentEntry
import com.lanhang.miuix.moments.MomentItem
import com.lanhang.miuix.moments.MomentsScreen
import com.lanhang.miuix.moments.UserInfoCache
import com.lanhang.miuix.net.StaffInfo
import com.lanhang.miuix.net.cleanOrgName
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 个人主页（自己 / 别人共用）。
 *
 * 版式按用户给的截图：**头像 → 学号 → 姓名 → 邮箱**（学校那一行用户说可以不保留，
 * 只在拿得到院系时挂在头像下面当副标题），再往下就是这个人发过的全部动态。
 *
 * 动态列表整段复用 [MomentsScreen]（带下拉刷新 / 翻页 / 点赞 / 评论 / 删除），
 * 只是把「发表」按钮关掉（showFab = false），并把资料卡片塞进表头。
 */
@Composable
fun UserProfilePage(
    staff: StaffInfo?,
    userId: String,
    isSelf: Boolean,
    items: List<MomentItem>,
    loading: Boolean,
    refreshing: Boolean,
    ended: Boolean,
    revision: Int,
    emptyText: String,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLike: (MomentItem) -> Unit,
    onPostComment: (MomentItem, String?, String) -> Unit,
    onDeleteComment: (MomentItem, CommentEntry) -> Unit,
    onDeletePost: (MomentItem) -> Unit,
    onImage: (List<String>, Int, android.graphics.Rect?) -> Unit,
    /** 主页里再点头像/名字 → 进那个人的主页（由宿主决定怎么导航） */
    onOpenProfile: (String) -> Unit = {},
    /** 别人的主页才有「发私信」（自己主页传 null） */
    onMessage: (() -> Unit)? = null,
    /** 滚动位置由外面的「我的」页面栈持有（用户第九轮第 3 条） */
    listState: LazyListState = rememberLazyListState(),
) {
    MomentsScreen(
        listState = listState,
        items = items,
        loading = loading,
        refreshing = refreshing,
        ended = ended,
        emptyText = emptyText,
        revision = revision,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        onLike = onLike,
        onPostComment = onPostComment,
        onDeleteComment = onDeleteComment,
        onDeletePost = onDeletePost,
        onImage = onImage,
        onOpenPublish = {},
        onOpenProfile = onOpenProfile,
        header = {
            ProfileHeader(
                staff = staff,
                isSelf = isSelf,
                fallbackId = userId,
                onMessage = onMessage,
            )
        },
        showFab = false,
    )
}

/** 资料卡片 + 「TA 的动态」小标题 */
@Composable
fun ProfileHeader(
    staff: StaffInfo?,
    isSelf: Boolean,
    fallbackId: String,
    onMessage: (() -> Unit)? = null,
) {
    val name = staff?.name?.takeIf { it.isNotBlank() }
        ?: if (isSelf) Session.name.orEmpty() else ""
    val staffNo = staff?.staffNo?.takeIf { it.isNotBlank() }
        ?: if (isSelf) Session.username else ""
    // 院系：depart_name 优先；extra.org 有时是纯数字组织编码，那种不显示
    val department = staff?.depart_name.cleanOrgName() ?: staff?.org.cleanOrgName()
    // 头像统一用证件照：/staffs/{id} 偶尔不带 portrait_url，就回落到工作圈的批量接口缓存，
    // 保证「个人主页」和「工作圈里那条动态」是同一张脸
    val avatarUrl = listOfNotNull(
        staff?.portraitUrl,
        UserInfoCache.get(fallbackId)?.portrait_url,
        if (isSelf) Session.avatarUrl else null,
    ).firstOrNull { it.isNotBlank() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LanhangAvatar(
                        url = avatarUrl,
                        name = name.orEmpty(),
                        size = 64.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.moments_anonymous),
                            style = MiuixTheme.textStyles.title2,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!department.isNullOrBlank()) {
                            Text(
                                text = department,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                ProfileInfoRow(stringResource(R.string.profile_label_staff_no), staffNo.orEmpty())
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                ProfileInfoRow(stringResource(R.string.profile_label_name), name.orEmpty())
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                ProfileInfoRow(stringResource(R.string.profile_label_email), staff?.email.orEmpty())
                val mobile = staff?.maskedMobile
                if (!mobile.isNullOrBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    ProfileInfoRow(stringResource(R.string.profile_label_phone), mobile)
                }
                if (!isSelf && onMessage != null) {
                    Spacer(Modifier.padding(top = 6.dp))
                    TextButton(
                        text = stringResource(R.string.profile_message),
                        onClick = onMessage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SmallTitle(
            if (isSelf) {
                stringResource(R.string.profile_section_album)
            } else {
                stringResource(R.string.profile_section_feeds)
            }
        )
    }
}

@Composable
private fun ProfileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value.ifBlank { "--" },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
