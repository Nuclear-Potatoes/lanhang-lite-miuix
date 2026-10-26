package com.lanhang.miuix.mine

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lanhang.miuix.R
import com.lanhang.miuix.net.ContactsRepository
import com.lanhang.miuix.net.Department
import com.lanhang.miuix.net.OrgEntry
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.ui.miuix.LanhangAvatar
import com.lanhang.miuix.ui.miuix.LanhangIcons
import com.lanhang.miuix.ui.miuix.MiuiNavStack
import com.lanhang.miuix.ui.miuix.PageListStates
import com.lanhang.miuix.ui.miuix.rememberPageListStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 下钻层成员数据的内存缓存（第十二轮，用户反馈「切换不同层级时会闪一下」）。
 *
 * 每次切层，`DeptContent` 都是**重新组合**的一份：没有缓存时 `entries` 先回到 null，
 * 屏幕先画一帧「正在加载」再变成列表 —— 返回上一级时尤其明显（父层明明刚看过）。
 * 这里按部门 id 记住已经拿到的条目，重组时**同步**取出来当初始值，列表立刻就有内容；
 * 网络拉取照旧在后台跑，回来了再覆盖（内容一样，视觉上不会动）。
 * 只管内存（进程结束即失效），磁盘缓存仍由 [ContactsRepository] 负责。
 */
private object BranchLevelCache {
    private val map = object : LinkedHashMap<String, List<OrgEntry>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OrgEntry>>?): Boolean =
            size > 24
    }

    fun get(id: String): List<OrgEntry>? = map[id]

    fun put(id: String, data: List<OrgEntry>) {
        map[id] = data
    }
}

/**
 * 通讯录的跨页面状态（2026-10-26 用户反馈后提升到页面之上）。
 *
 * 用户从「通讯录 → 某部门 → 某人 → 个人主页」按返回时，应该回到**那个人所在的部门**，
 * 而不是回「我的」根页、也不是回到通讯录根层。个人主页现在是「我的」这一栈里的独立页面
 * （[MinePage.PROFILE_OTHER]），通讯录会离开组合，所以下钻栈与已加载的树不能再放在
 * `remember` 里，必须由 [MineScreen] 记住并传进来。
 */
@Stable
class ContactsUiState {
    var tree by mutableStateOf<List<Department>?>(null)
    var error by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    /** 重试计数：自增触发重新拉树 */
    var reload by mutableIntStateOf(0)

    /** 下钻栈：空 = 根层（教职工 / 学生 …） */
    val path = mutableStateListOf<Department>()

    /**
     * 退一级目录（顶栏返回箭头 / 系统返回键都走这里）。
     * 已经在通讯录根层时返回 false，让调用方去退「我的」页栈。
     */
    fun popOneLevel(): Boolean {
        if (path.isEmpty()) return false
        path.removeAt(path.lastIndex)
        return true
    }
}

/**
 * 通讯录。
 *
 * 架构对齐官方客户端（反编译 `cn.rongcloud.rce.lib.OrganizationTask`）：
 * 组织架构是**一棵树**（`/departments/tree` 一次性下发 1500+ 节点），
 * 点某一级再拉它的子部门 + 成员（`/departments/{id}/branches`，带 offset/limit 分页）。
 * 所以这里也是三层：根节点列表 → 部门（可逐级下钻）→ 成员资料卡。
 *
 * 页面自身负责取数（`RceApi` 带登录 Cookie），宿主只给「看某个人的主页」这一个出口，
 * 免得把通讯录的数据流塞进「我的」那套状态里。
 */
@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    state: ContactsUiState = remember { ContactsUiState() },
    onOpenProfile: (String) -> Unit = {},
    /**
     * 每一层目录各自一份滚动位置（用户第九轮第 3 条：下钻再返回，上一级目录不该跳回顶部）。
     *
     * 由「我的」页面栈（[PageListStates]）传进来，这样连「去看了某人主页再回来」也不会丢。
     */
    listStates: PageListStates = rememberPageListStates("contacts"),
) {
    val context = LocalContext.current

    var detail by remember { mutableStateOf<OrgEntry?>(null) }

    LaunchedEffect(state.reload) {
        // 已经拉到过树就直接用（从个人主页返回时不该再拉一次）
        if (state.tree != null && state.error == null) return@LaunchedEffect
        state.error = null
        val result = runCatching { withContext(Dispatchers.IO) { ContactsRepository.tree() } }
        result.onSuccess { state.tree = it }
        result.onFailure {
            state.error = it.message ?: context.getString(R.string.contacts_load_failed)
        }
    }

    // 系统返回手势/返回键：在下钻层里先退到上一级目录，别直接退到「我的」根页面
    // （用户 2026-10-26 反馈：点进几层子目录后一划返回就回到「我的」了）
    BackHandler(enabled = state.path.isNotEmpty() && state.query.isBlank()) {
        if (state.path.isNotEmpty()) state.path.removeAt(state.path.size - 1)
    }

    val tree = state.tree
    val path = state.path

    // 下钻栈只表达「当前栈里有哪几层」，方向交给 MiuiNavStack 自己比较深度
    // （第十一轮：以前在这里用 SideEffect 维护 lastDrillSize，转场中途被重组一次就会把方向判成
    //  「进入」，返回时看起来方向反了）
    val query = state.query
    val error = state.error

    val byId = remember(tree) { (tree ?: emptyList()).associateBy { it.id } }
    val roots = remember(tree) { (tree ?: emptyList()).filter { it.parentId.isBlank() } }
    val hits = remember(tree, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else (tree ?: emptyList())
            .filter { it.name.contains(q, ignoreCase = true) || it.fullName.contains(q, ignoreCase = true) }
            .sortedBy { it.memberCount }
            .take(80)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { state.query = it },
                label = stringResourceSafe(R.string.contacts_search_hint),
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                error != null -> ContactsStateBox(
                    message = error!!,
                    action = stringResourceSafe(R.string.contacts_retry),
                ) { state.reload++ }

                tree == null -> ContactsStateBox(
                    message = stringResourceSafe(R.string.contacts_loading),
                    action = null,
                ) {}

                query.isNotBlank() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (hits.isEmpty()) {
                        item("empty") {
                            ContactsStateBox(
                                message = stringResourceSafe(R.string.contacts_no_match),
                                action = null,
                            ) {}
                        }
                    }
                    items(hits, key = { it.id }) { dept ->
                        ContactRow(
                            title = dept.name,
                            subtitle = ancestorsOf(dept, byId)
                                .joinToString(" / ") { it.name }
                                .takeIf { it.isNotBlank() },
                            badge = null,
                            leading = { DeptGlyph() },
                            showChevron = true,
                        ) {
                            path.clear()
                            path.addAll(ancestorsOf(dept, byId))
                        }
                    }
                    item("tip") { Spacer(Modifier.height(12.dp)) }
                }

                else -> MiuiNavStack(
                    key = path.toList(),
                    pageBackground = colorResource(R.color.page_bg),
                    depthOf = { k -> (k as List<*>).size },
                ) { stackKey: Any ->
                    val stack = stackKey as List<Department>
                    val current = stack.lastOrNull()
                    if (current == null) {
                        LazyColumn(
                            state = listStates.of("contacts:root"),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item("head") {
                                SmallTitle(stringResourceSafe(R.string.contacts_root_section))
                            }
                            items(roots, key = { it.id }) { dept ->
                                ContactRow(
                                    title = dept.name,
                                    subtitle = null,
                                    badge = dept.memberCount.takeIf { it > 0 }
                                        ?.let { stringResourceSafe(R.string.contacts_member_count, it) },
                                    leading = { DeptGlyph() },
                                    showChevron = true,
                                ) {
                                    path.clear()
                                    path.add(dept)
                                }
                            }
                            item("tip") {
                                Text(
                                    text = stringResourceSafe(R.string.contacts_tip),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                                )
                            }
                        }
                    } else {
                        val levelKey = "contacts:" + stack.joinToString("/") { it.id }
                        DeptContent(
                            dept = current,
                            levelKey = levelKey,
                            listStates = listStates,
                            listState = listStates.of(levelKey),
                            onOpenDept = { path.add(it) },
                            onOpenMember = { detail = it },
                        )
                    }
                }
            }
        }
    }

    val member = detail
    if (member != null) {
        MemberDialog(
            member = member,
            department = path.lastOrNull()?.name,
            onDismiss = { detail = null },
            onOpenProfile = {
                detail = null
                onOpenProfile(member.id)
            },
        )
    }
}

/* ------------------------------------------------------------------ 部门内容 */

@Composable
private fun DeptContent(
    dept: Department,
    levelKey: String,
    listStates: PageListStates,
    listState: LazyListState,
    onOpenDept: (Department) -> Unit,
    onOpenMember: (OrgEntry) -> Unit,
) {
    // 第十二轮：有缓存就直接有内容，避免切层时先闪一帧「正在加载」
    var entries by remember(dept.id) { mutableStateOf(BranchLevelCache.get(dept.id)) }
    // 首次访问（真没缓存）也先等一下下再显示加载态：正常几百毫秒内就回来了，闪一下反而难看
    var showLoading by remember(dept.id) { mutableStateOf(false) }
    var error by remember(dept.id) { mutableStateOf<String?>(null) }
    var loadingMore by remember(dept.id) { mutableStateOf(false) }
    var more by remember(dept.id) { mutableStateOf(true) }
    var offset by remember(dept.id) { mutableIntStateOf(0) }
    var retry by remember(dept.id) { mutableIntStateOf(0) }

    // 用户第九轮第 3 条的另一处：下钻一层再返回，上一级目录会跳位置。
    // 离开时把位置存起来，等这一层的成员数据重新拉回来后再补回去（列表先空后满会把下标夹走）。
    DisposableEffect(levelKey) {
        onDispose { listStates.savePosition(levelKey, listState) }
    }
    LaunchedEffect(entries != null) {
        if (entries != null) listStates.restorePosition(levelKey, listState)
    }

    suspend fun load(reset: Boolean) {
        if (loadingMore && !reset) return
        loadingMore = true
        if (reset) offset = 0
        val result = runCatching {
            withContext(Dispatchers.IO) { ContactsRepository.branches(dept.id, offset, PAGE_SIZE) }
        }
        result.onSuccess { page ->
            val merged = if (reset) page.data else (entries ?: emptyList()) + page.data
            entries = merged
            BranchLevelCache.put(dept.id, merged)
            offset = merged.size
            more = page.data.isNotEmpty() && merged.size < (page.totalCount.takeIf { it > 0 } ?: merged.size)
            error = null
        }
        result.onFailure { error = it.message }
        loadingMore = false
    }

    LaunchedEffect(dept.id, retry) { load(reset = true) }

    LaunchedEffect(dept.id, entries == null) {
        if (entries != null) {
            showLoading = false
        } else {
            delay(180)
            showLoading = true
        }
    }

    val departments = remember(entries) { entries.orEmpty().filter { it.isDepartment } }
    val members = remember(entries) { entries.orEmpty().filterNot { it.isDepartment } }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // 2026-10-26 第二轮：删掉这行里的「返回上一级」胶囊（用户要求），
        // 上一级只走顶栏的返回箭头 / 系统返回手势（见 MainActivity 里的 CONTACTS 分支）。
        // 这里只留当前部门全名，当作「你在哪」的小标题。
        item("crumb") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dept.fullName.ifBlank { dept.name },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            error != null && entries == null -> item("error") {
                ContactsStateBox(
                    message = error!!,
                    action = stringResourceSafe(R.string.contacts_retry),
                ) { retry++ }
            }

            // 还在加载但还没到 180ms：先什么都不画（一帧「加载中」的闪烁比空等更难看）
            entries == null && !showLoading -> Unit

            entries == null -> item("loading") {
                ContactsStateBox(
                    message = stringResourceSafe(R.string.contacts_loading),
                    action = null,
                ) {}
            }

            else -> {
                if (departments.isNotEmpty()) {
                    item("deptTitle") { SmallTitle(stringResourceSafe(R.string.contacts_sub_section)) }
                    items(departments, key = { "d-" + it.id }) { entry ->
                        ContactRow(
                            title = entry.name,
                            subtitle = null,
                            badge = entry.memberCount.takeIf { it > 0 }
                                ?.let { stringResourceSafe(R.string.contacts_member_count, it) },
                            leading = { DeptGlyph() },
                            showChevron = true,
                        ) {
                            onOpenDept(
                                Department(
                                    id = entry.id,
                                    parentId = entry.parentId,
                                    name = entry.name,
                                    fullName = entry.name,
                                    memberCount = entry.memberCount,
                                )
                            )
                        }
                    }
                }

                if (members.isNotEmpty()) {
                    item("memberTitle") { SmallTitle(stringResourceSafe(R.string.contacts_member_section)) }
                    items(members, key = { "m-" + it.id }) { entry ->
                        ContactRow(
                            title = entry.name.ifBlank { "--" },
                            subtitle = listOfNotNull(
                                entry.dutyName.takeIf { it.isNotBlank() },
                                entry.mobile.takeIf { it.isNotBlank() },
                            ).joinToString("  ·  ").takeIf { it.isNotBlank() },
                            badge = null,
                            leading = {
                                LanhangAvatar(
                                    url = entry.portraitUrl.takeIf { it.isNotBlank() },
                                    name = entry.name,
                                    size = 40.dp,
                                )
                            },
                            showChevron = false,
                        ) { onOpenMember(entry) }
                    }
                }

                if (departments.isEmpty() && members.isEmpty()) {
                    item("empty") {
                        ContactsStateBox(
                            message = stringResourceSafe(R.string.contacts_dept_empty),
                            action = null,
                        ) {}
                    }
                }

                if (more && members.isNotEmpty()) {
                    item("more") {
                        LaunchedEffect(entries?.size) { load(reset = false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResourceSafe(R.string.contacts_loading_more),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                item("tail") { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

/* ------------------------------------------------------------------ 列表行 */

@Composable
private fun ContactRow(
    title: String,
    subtitle: String?,
    badge: String?,
    leading: @Composable () -> Unit,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = LanhangIcons.Chevron,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 部门小图标：自绘「组织架构」符号（两层节点 + 连线） */
@Composable
private fun DeptGlyph() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LanhangIcons.Org,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(21.dp),
        )
    }
}

/* ------------------------------------------------------------------ 成员卡 */

@Composable
private fun MemberDialog(
    member: OrgEntry,
    department: String?,
    onDismiss: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LanhangAvatar(
                        url = member.portraitUrl.takeIf { it.isNotBlank() },
                        name = member.name,
                        size = 56.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = member.name.ifBlank { "--" },
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOfNotNull(
                            member.dutyName.takeIf { it.isNotBlank() },
                            department?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                if (member.mobile.isNotBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(top = 14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResourceSafe(R.string.contacts_phone),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = member.mobile,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResourceSafe(R.string.contacts_view_profile),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .clickable { onOpenProfile() }
                            .padding(vertical = 11.dp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResourceSafe(R.string.contacts_close),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onDismiss() }
                            .padding(vertical = 11.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 工具 */

@Composable
private fun ContactsStateBox(message: String, action: String?, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = LanhangIcons.Contacts,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
            if (action != null) {
                Text(
                    text = action,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .clickable { onClick() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 从根到该节点的完整路径（含自身） */
private fun ancestorsOf(dept: Department, byId: Map<String, Department>): List<Department> {
    val out = ArrayList<Department>()
    var cursor: Department? = dept
    var guard = 0
    while (cursor != null && guard++ < 32) {
        out.add(0, cursor)
        val parentId = cursor.parentId
        cursor = if (parentId.isBlank()) null else byId[parentId]
    }
    return out
}

private const val PAGE_SIZE = 50

/** stringResource 的薄封装，省得每个调用点都写 @Composable 上下文（这里全在 Composable 里用） */
@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String =
    if (args.isEmpty()) androidx.compose.ui.res.stringResource(id)
    else androidx.compose.ui.res.stringResource(id, *args)
