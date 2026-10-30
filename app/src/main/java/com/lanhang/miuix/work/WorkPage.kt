package com.lanhang.miuix.work

import com.lanhang.miuix.ui.miuix.pressableRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanhang.miuix.R
import com.lanhang.miuix.net.WorkApp
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.OaWebViewActivity
import com.lanhang.miuix.ui.miuix.LanhangIcons
import com.lanhang.miuix.ui.miuix.SchoolIcons
import com.lanhang.miuix.ui.miuix.rememberStickyListState
import com.lanhang.miuix.ui.miuix.markStickyListLanded
import com.lanhang.miuix.ui.miuix.stickyListLanded
import com.lanhang.miuix.ui.shell.ReportListScroll
import com.lanhang.miuix.ui.shell.ScrollToTop
import com.lanhang.miuix.ui.shell.animateScrollToTopSlow
import com.lanhang.miuix.ui.shell.ShellState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Email
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Weeks
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.FolderFill
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.MoveFile
import top.yukonga.miuix.kmp.icon.extended.NotesFill
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Answer
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Location
import top.yukonga.miuix.kmp.icon.extended.MapAlbum
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Years
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.MindMap
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 每行几个应用。
 *
 * 2026-10-26 第四轮：用户反馈「工作界面中图标太大，改小一点，一行 6 个」，
 * 从 4 列 / 66dp 图形收到 **6 列 / 44dp 图形**（一屏能看到更多分类，卡片也更紧凑）。
 */
private const val GRID_COLUMNS = 6

/**
 * 宫格图标的边长（跟着 [GRID_COLUMNS] 一起定的，改一个记得看另一个）。
 *
 * 2026-10-26 第五轮：44dp 收到 **40dp**，名称从 15.6sp/两行 32dp 收到 **11sp/两行 28dp**，
 * 图与字之间留 6dp——原来字太大、图标太小挤在一起，用户说「标题与图标不太协调」。
 */
private val GLYPH_SIZE = 40.dp

/**
 * 宫格名称字号 / 行高 / 总高（三者要一起改，固定行高才能保证同一行的图标与文字对齐）。
 *
 * 2026-10-26 第九轮（用户第 1 条：「工具一页的名称不要省略号，显示不全就换行」）：
 * 原来只给两行 28dp，「数据中心机房托管设备申请」这类 12 字的名字在 6 列里一行只放得下 5 个字，
 * 两行也塞不进 → 末尾被截成「数据中心…」。现在放到**三行**，字号同时收到 10.5sp，
 * 6 列一格约能放 5 个字/行、三行 15 字，服务端目录里最长的名字也能完整显示。
 */
private val TILE_LABEL_SIZE = 10.5.sp
private val TILE_LABEL_LINE = 13.sp

/**
 * 三行名称的**实测**总高。
 *
 * 10.5sp 的汉字行盒实测约 16dp（13sp 的 lineHeight + 首末行的字形上下留白），
 * 一开始按 3×13sp = 40dp 给高度，结果第三行被裁掉、Compose 在第二行末尾补了「…」，
 * 和用户要求的「换行显示完整」正好相反。48dp = 3×16dp 才真的放得下三行。
 */
private val TILE_LABEL_HEIGHT = 48.dp

/**
 * 顶部「常用功能」：用户 2026-10-26 第五轮点名要提到最前面的五个，一列一个。
 *
 * 每个元素是一组**名称关键词**（按顺序取第一个命中的应用）；目录本身仍来自 `POST /apps/all`，
 * 命中不到就不显示——绝不为了凑数写死假功能。命中过的应用会从下面的分类里摘掉，避免重复出现。
 */
/** 服务端目录里整组不展示的分组（第十二轮：系统应用里只有一个无效的「通知服务」） */
private val HIDDEN_SECTIONS = listOf("系统应用")

/** 无论如何都不展示的应用名（无效功能，点开是空的） */
private val HIDDEN_APPS = listOf("通知服务")

/**
 * 「常用功能」的槽位定义（顺序即展示顺序）。
 *
 * 2026-10-27 第十六轮：用户要求把原项目的校园网一键认证搬进常用功能、并命名为「校园网登录」，
 * 同时用原生页替换掉原来那个 H5 的「座位预约」。所以槽位分成两种：
 *  - [CommonSlot.Server]：从服务端 `/apps/all` 目录里按关键词挑（挑不到就不显示，不造假功能）；
 *  - [CommonSlot.Native]：App 自己实现的原生页（[WorkRoute]），永远在。
 */
private sealed interface CommonSlot {
    data class Server(val keys: List<String>) : CommonSlot
    data class Native(val id: String) : CommonSlot
}

private val COMMON_SLOTS = listOf(
    CommonSlot.Server(listOf("查用电")),
    CommonSlot.Server(listOf("缴电费")),
    CommonSlot.Native(NATIVE_CAMPUS_NET_ID),
    // 座位预约：服务端那个应用只用来占位（名字、图标照旧），点开走原生页
    CommonSlot.Server(listOf(SEAT_APP_MARK, "图书馆")),
    CommonSlot.Server(listOf("智慧教务")),
)

/**
 * 「工作」二级页：办事大厅应用目录。
 *
 * 2026-10-27 第二十六轮：功能清单**本地化**（[WorkCatalog]），不再联网拉 `POST /apps/all`。
 *
 * 布局照用户要求改成**宫格**（原版蓝航就是一个图标一个名字的宫格），不再是竖排列表：
 * 每个分类一张 Miuix 卡片，卡片内标题 + 宫格（每行 [GRID_COLUMNS] 个），图标沿用 Lite 版自己画的那套
 * （浅粉彩底 + 同色相线性图形），不拉服务端的彩色 logo。
 *
 * 原来的「本科生 / 研究生 / 校领导」标签已整体去掉——服务端目录本身没有适用范围字段，
 * 那三个标签只是按名字关键词猜的，既不准也多余。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun WorkPage(
    staffNo: String = "",
    userType: Int? = null,
    modifier: Modifier = Modifier,
    /** 打开 App 自己的原生功能页（校园网登录 / 图书馆座位预约），由 [WorkFragment] 的页面栈承载 */
    onOpenNative: (WorkRoute) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var sections by remember { mutableStateOf<List<Pair<String?, List<WorkApp>>>>(emptyList()) }
    var portal by remember { mutableStateOf("") }
    // 本地清单没有加载过程，初值就是 false（否则会闪一帧转圈）
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var busyApp by remember { mutableStateOf<String?>(null) }

    // 2026-10-27 第二十六轮（用户：「将工作页面的功能区本地化，不要联网获取工作中的功能列表」）：
    // 清单来自 [WorkCatalog] 的本地表 → 进页面立刻有内容，断网也是同一屏；功能本身（点开后那个 H5）
    // 照旧联网，这里只是不再为「功能列表」发请求。
    // 门户地址只读登录时缓存的配置，不再打 `/configuration/all`（那也是首屏转圈的原因之一）。
    LaunchedEffect(reload) {
        error = ""
        sections = WorkCatalog.sections
        portal = Session.workUrl
        loading = false
    }

    // 课表、成绩是 App 自己的原生 Tab，工作页不再重复列这些应用
    // 第十二轮（用户反馈）：服务端目录里的「系统应用」分组没有可用功能（其中「通知服务」点进去是空的），
    // 整组不展示；同名应用在任何分组里也一并滤掉。
    val usable = sections
        .filterNot { (title, _) -> title != null && HIDDEN_SECTIONS.any { title.contains(it) } }
        .map { (title, apps) ->
            title to apps.filterNot { app ->
                app.isNativeDuplicate() || HIDDEN_APPS.any { app.displayName.trim() == it }
            }
        }

    // 顶部「常用功能」：从目录里按关键词挑出用户点名的五个（挑不到的就不显示，不造假功能）
    val allApps = usable.flatMap { it.second }
    val nativeApps = remember {
        mapOf(
            NATIVE_CAMPUS_NET_ID to WorkApp(
                id = NATIVE_CAMPUS_NET_ID,
                name = context.getString(R.string.work_campus_net_title),
                description = context.getString(R.string.work_campus_net_title),
                // 没有 openUrl = 不会走 OA WebView，点开由 openApp 分派到原生页
                homePageUrl = "",
            ),
        )
    }
    val common = COMMON_SLOTS.mapNotNull { slot ->
        when (slot) {
            is CommonSlot.Native -> nativeApps[slot.id]
            is CommonSlot.Server -> allApps.firstOrNull { app ->
                slot.keys.any { app.displayName.contains(it, true) }
            }
        }
    }
    val commonKeys = common.map { it.key() }.toSet()
    // 撞上常用功能的条目不重复出现在下面的分类里（分类因此变空就整块不画）
    val sectionsBelow = usable
        .map { (title, apps) -> title to apps.filterNot { it.key() in commonKeys } }
        .filter { it.second.isNotEmpty() }

    // 图标色相按「显示顺序」分配（黄金角铺开），不再由应用名 hash 决定：
    // hash 会撞出一片近似色，用户反馈「图标颜色重复率太高」。
    // 同一个应用会在多个分类里出现（南航要闻 / 掌上学工 …），只认**第一次**出现的位置：
    // 若让后面的位置覆盖，前排两个图标就会拿到相邻的色相，又变回一片同色。
    val hueSlots = LinkedHashMap<String, Int>()
    val orderedApps = common + sectionsBelow.flatMap { it.second }
    orderedApps.forEach { app ->
        val key = app.key()
        if (!hueSlots.containsKey(key)) hueSlots[key] = hueSlots.size
    }
    val orderedKeys = orderedApps.map { it.key() }

    // 图标：整页互不重复（用户第九轮第 1 条）。按「常用 + 全部分类」的显示顺序分配，
    // 只依赖应用集合，滚动/搜索都不用重算。
    val glyphIds = remember(orderedKeys) { assignGlyphIds(orderedApps) }

    val keyword = query.trim()
    val visible = sectionsBelow.mapNotNull { (title, apps) ->
        val matched = apps.filter { app ->
            keyword.isEmpty() ||
                app.displayName.contains(keyword, true) ||
                app.description.orEmpty().contains(keyword, true)
        }
        if (matched.isEmpty()) null else title to matched
    }
    val total = sectionsBelow.sumOf { it.second.size }
    val shown = visible.sumOf { it.second.size }
    val openApp: (WorkApp) -> Unit = { app ->
        val openUrl = app.openUrl
        if (app.id == NATIVE_CAMPUS_NET_ID) {
            // 校园网登录：App 自己的原生页（密码存 Keystore，点一次就认证）
            onOpenNative(WorkRoute.CAMPUS_NET)
        } else if (app.displayName.contains(SEAT_APP_MARK)) {
            // 座位预约：接管服务端那个 H5 入口，换成原生选座页
            onOpenNative(WorkRoute.LIBRARY_SEAT)
        } else if (!openUrl.isNullOrBlank()) {
            // 2026-10-27 第十八轮（用户：「学生邮箱、校园网账号设置打开后无响应，原界面卡死，
            // 而且其他应用也打不开」）：
            // 以前这里先跑一遍网关预检（OkHttp 20s connect + 30s read），期间 `busyApp` 把**所有**
            // 应用一起锁死，界面上又没有任何反馈 —— 点下去就是「卡死」。
            // 现在立刻开页，把落地地址的预检搬进 OaWebViewActivity，在它自己的加载态里异步做，
            // 且不再有任何「同时只能开一个」的全局锁。
            OaWebViewActivity.open(
                context = context,
                url = openUrl,
                appToken = null,
                title = app.displayName,
                cookie = null,
                preflightUrl = openUrl,
                appId = app.id,
            )
        }
    }

    // 2026-10-26 用户反馈：工作页滑到顶栏收起，切个 Tab 再回来就跳回最顶端、还闪一下。
    // 根因：Tab 切换走 detach/attach，view 重建 → rememberLazyListState() 从 0 开始；
    // 而且下面那段 landed 逻辑也会复位成「首次加载」，再补一枪 scrollToItem(0)。
    // 现在改用进程级粘性状态，并且只在「本来就停在顶部」时才归位。
    val listState = rememberStickyListState("tab_work")
    // 第十一轮：这里原来误传了 TAB_MINE，工作页滚动永远改不动自己那一格进度，
    // 于是「工作」Tab 的顶栏根本不收放（用户：「工作页顶栏缩小动画缺失」）
    ReportListScroll(listState, ShellState.TAB_WORK)

    // 首次落位（每个进程每张表只做一次，见 ui/miuix/PageListStates.kt 的 stickyListLanded）：
    // 数据到位时，若用户**还没亲手拖过**列表、页面却不在顶部，就对齐回顶部（保险）。
    // 真正让页面停在「最近使用」的是上面两个常驻槽位（以前条件插入会把下标顶走）。
    // 为什么不能只用局部 remember 状态：切 Tab 回来时整棵组合重建，局部状态是新的，
    // 会把「已恢复的滚动位置」误当首次加载而抹掉（§18.2 的坑）→ 必须用进程级标记。
    var userDragged by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                userDragged = true
            }
        }
    }
    LaunchedEffect(loading, common.size, portal.isNotBlank()) {
        if (loading || stickyListLanded("tab_work")) return@LaunchedEffect
        markStickyListLanded("tab_work")
        if (!userDragged &&
            (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
        ) {
            listState.scrollToItem(0)
        }
    }

    // 双击底栏「工作」回到顶部（原来这里错写成 TAB_MINE，工作 Tab 双击一直没反应）
    com.lanhang.miuix.ui.shell.ScrollToTopEffect(ShellState.TAB_WORK) {
        listState.animateScrollToTopSlow()
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 30.dp),
        state = listState,
    ) {
        // 顶部「常用功能」：一列一个（用户 2026-10-26 第五轮要求）。
        // 搜索时让位给结果列表，避免「搜了却看不到搜到的分类」。
        // ★ 槽位常驻（2026-10-26 第十四轮）：以前是「数据到了才插到最前面」，
        // LazyColumn 以当前首个可见项为锚，插入会把下标从 0 顶到 1、2 → 页面看起来
        // 自己往下滚了一屏（停在「最近使用」），ReportListScroll 只看下标（>0 即收起），
        // 顶栏于是立刻收起。槽位常驻、内容为空时高度 0，就没有下标位移。
        item("common") {
            if (common.isNotEmpty() && keyword.isEmpty()) {
                WorkCommonCard(
                    apps = common,
                    hueSlots = hueSlots,
                    glyphIds = glyphIds,
                    busyApp = busyApp,
                    onOpen = openApp,
                )
            }
        }

        // 槽位常驻，理由同上（办事大厅卡片也是异步到位）。
        item("portal") {
            if (portal.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 第十九轮：整行可点要裁成卡片圆角，不然按压是一个直角方块
                            .pressableRow(onClick = {
                                // 同 openApp：预检搬进 WebView 页，点下去立刻有反馈
                                OaWebViewActivity.open(
                                    context = context,
                                    url = portal,
                                    appToken = null,
                                    title = context.getString(R.string.work_portal_title),
                                    cookie = null,
                                    preflightUrl = portal,
                                    appId = null,
                                )
                            })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = LanhangIcons.Work,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.work_portal_title),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.work_portal_summary),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            text = "›",
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        item("search") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.work_search_hint),
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        when {
            loading -> item("loading") { WorkStateBox(stringResource(R.string.work_loading), null) }
            error.isNotEmpty() -> item("error") {
                WorkStateBox(error, stringResource(R.string.work_retry)) { reload++ }
            }
            shown == 0 -> item("empty") {
                WorkStateBox(
                    if (total == 0) stringResource(R.string.work_empty)
                    else stringResource(R.string.work_empty_filter),
                    if (total == 0) stringResource(R.string.work_retry) else null,
                ) { if (total == 0) reload++ }
            }
            else -> {
                visible.forEach { (title, apps) ->
                    item("section-${title ?: "loose"}") {
                        WorkSectionCard(
                            title = title?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.work_section_loose),
                            apps = apps,
                            hueSlots = hueSlots,
                            glyphIds = glyphIds,
                            busyApp = busyApp,
                            onOpen = openApp,
                        )
                    }
                }
                item("count") {
                    Text(
                        text = stringResource(R.string.work_count, shown, total),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }
        }
    }
}

/**
 * 顶部「常用功能」卡片：一列一个功能（图标 + 名称 + 说明 + ›）。
 *
 * 用户 2026-10-26 第五轮：「工作中顶部加入一个常用功能，将查用电、缴电费、校园网一键认证、
 * 图书馆座位预约、智慧教务这五个功能提到前面，可以一列一个功能」。
 * 行高 56dp、图标 36dp，跟下面的宫格比更「列表化」，一眼能看出是快捷入口。
 */
@Composable
private fun WorkCommonCard(
    apps: List<WorkApp>,
    hueSlots: Map<String, Int>,
    glyphIds: Map<String, String>,
    busyApp: String?,
    onOpen: (WorkApp) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    WorkSectionLabel(stringResource(R.string.work_common_title))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            apps.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 第十九轮：圆角对齐卡片（16dp），按压高亮才跟卡片边缘一致
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpen(app) }
                        .alpha(if (busyApp == app.id) 0.45f else 1f)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppGlyph(
                        app = app,
                        size = COMMON_GLYPH_SIZE,
                        hue = hueOf(app, hueSlots),
                        glyphId = glyphIds[app.key()],
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = app.label(),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "›",
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
    }
}

/**
 * 分组标题（2026-10-26 第七轮）。
 *
 * 用户反馈「『最近使用』『教学办公』这些标题和页面不太协调」：原来标题是卡片**里面**的
 * `title4`（20sp 加粗、左内距 6dp），而卡片本身只有 8dp 外边距 —— 又大又贴边，
 * 和卡片里的 40dp 图标 + 11sp 名称完全不是一个层级。
 * 现在按 Miuix「分组标题」的规矩来：放在卡片**外面**，`body2` 次要色，
 * 左边与卡片内容对齐（卡片 12dp 外边距 + 16dp 内边距），上下留 12/6dp。
 */
@Composable
private fun WorkSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 28.dp, end = 24.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** 一个分类：标题 + 卡片 + 宫格（每行 [GRID_COLUMNS] 个，末行补占位保持左对齐） */
@Composable
private fun WorkSectionCard(
    title: String,
    apps: List<WorkApp>,
    hueSlots: Map<String, Int>,
    glyphIds: Map<String, String>,
    busyApp: String?,
    onOpen: (WorkApp) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    WorkSectionLabel(title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            apps.chunked(GRID_COLUMNS).forEach { rowApps ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowApps.forEach { app ->
                        AppTile(
                            app = app,
                            busy = busyApp == app.id,
                            hue = hueOf(app, hueSlots),
                            glyphId = glyphIds[app.key()],
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(app) },
                        )
                    }
                    repeat(GRID_COLUMNS - rowApps.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    }
}

/**
 * 宫格单元：图标 + 名称。
 *
 * 名称固定占**两行**高度：应用名是各业务处室自己起的，动辄十来个字
 * （「数据中心机房托管设备申请」这类）。6 列时一行约 68dp、能放 6 个字，
 * 12 个字两行刚好放完；行高固定，同一行仍然对得齐。
 */
@Composable
private fun AppTile(
    app: WorkApp,
    busy: Boolean,
    hue: Float,
    glyphId: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp, horizontal = 2.dp)
            .alpha(if (busy) 0.45f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppGlyph(app = app, hue = hue, glyphId = glyphId)
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label(),
            style = MiuixTheme.textStyles.footnote2,
            fontSize = TILE_LABEL_SIZE,
            lineHeight = TILE_LABEL_LINE,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // 名字优先换行显示完整；三行都放不下（服务端目录里没有这种）才轮到省略号
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                // 固定行高才能保证同一行的图标 / 名称上下对齐（三行 × 13sp ≈ 40dp）
                .height(TILE_LABEL_HEIGHT),
        )
    }
}

/**
 * 应用图标统一由本地绘制，不再拉服务端的彩色位图——那批 logo 是各厂商自己拼的截图风，
 * 摆在一起跟 Miuix 那张脸完全不搭。
 *
 * 画法跟课表胶囊同一套语言：色相由应用名固定（黄金角散开，同名同色），
 * 底色是低饱和浅色、图形是同色相加深（暗色主题则反过来）；图形本身取 Miuix 线性图标，
 * 按名称关键词归类，一个关键词都没命中就退回「首字」，仍然保证每个应用都能区分。
 */
@Composable
private fun AppGlyph(app: WorkApp, size: Dp = GLYPH_SIZE, hue: Float, glyphId: String? = null) {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val container = Color.hsv(hue, if (dark) 0.34f else 0.26f, if (dark) 0.30f else 0.97f)
    val tint = Color.hsv(hue, if (dark) 0.45f else 0.66f, if (dark) 0.94f else 0.50f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = glyphOf(glyphId)
        if (glyph != null) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.52f),
            )
        } else {
            Text(
                text = app.displayName.trim().take(1).ifBlank { "·" },
                style = MiuixTheme.textStyles.title4,
                fontSize = (size.value * 0.42f).sp,
                color = tint,
            )
        }
    }
}

/** 「常用功能」卡里的图标边长（比宫格略小，配合 56dp 行高） */
private val COMMON_GLYPH_SIZE = 36.dp

/**
 * 图标色相：按**显示顺序**用黄金角铺开（相邻图标差 ~137°，全表不重复）。
 *
 * 2026-10-26 第五轮：原来色相由应用名 hash × 137.5 决定，hash 一撞就出现一片近似色，
 * 用户反馈「图标与图标颜色重复率太高」。改成按顺序取，同一行 6 个必然两两不同。
 */
private fun hueOf(app: WorkApp, hueSlots: Map<String, Int>): Float {
    val index = hueSlots[app.key()] ?: 0
    return (18f + index * 137.508f).mod(360f)
}

/** 去重 / 取色相用的稳定键：有 id 用 id，没有就退回名称 */
/**
 * 展示给用户的名称。
 *
 * 第十九轮用户要求「工作里所有功能的名字和点进去之后顶栏的内容一致」：
 * 座位预约已经被我们换成了原生页，顶栏写的是「图书馆座位预约」，
 * 所以这里也按同一个 string 显示，避免出现「名字叫座位预约、进去叫别的」。
 */
@Composable
private fun WorkApp.label(): String =
    if (displayName.contains(SEAT_APP_MARK)) stringResource(R.string.library_seat_title)
    else displayName.ifBlank { "--" }

private fun WorkApp.key(): String = id.orEmpty().ifBlank { displayName }

private fun String.hitsAny(vararg words: String): Boolean =
    words.any { this.contains(it, ignoreCase = true) }

/**
 * 图标池：id → 图形。
 *
 * **为什么用 id 而不是直接比 `ImageVector`**：`MiuixIcons.X` 是懒加载的 getter，
 * 同一个图标取两次不保证是同一个实例，拿来当集合元素去重会漏。用字符串 id 去重才稳。
 *
 * 池子要比服务端目录的应用数（50 个）大，才能保证「整页不出现重复图标」（用户第九轮第 1 条）。
 */
private val GLYPH_POOL: Map<String, ImageVector> = linkedMapOf(
    // —— 照学校原图标重画的那一套（见 SchoolIcons，第二十轮）——
    "teach" to SchoolIcons.Teach,
    "calendar" to SchoolIcons.Calendar,
    "chart" to SchoolIcons.Chart,
    "clipboard" to SchoolIcons.Clipboard,
    "bolt" to SchoolIcons.Bolt,
    "boltYuan" to SchoolIcons.BoltYuan,
    "card" to SchoolIcons.Card,
    "wallet" to SchoolIcons.Wallet,
    "handYuan" to SchoolIcons.HandYuan,
    "wifi" to SchoolIcons.Wifi,
    "globe" to SchoolIcons.Globe,
    "nodes" to SchoolIcons.Nodes,
    "gauge" to SchoolIcons.Gauge,
    "monitor" to SchoolIcons.Monitor,
    "megaphone" to SchoolIcons.Megaphone,
    "seat" to SchoolIcons.Seat,
    "book2" to SchoolIcons.Book,
    "group" to SchoolIcons.Group,
    "stadium" to SchoolIcons.Stadium,
    "shield" to SchoolIcons.Shield,
    "door" to SchoolIcons.Door,
    "faceGate" to SchoolIcons.FaceGate,
    "faceCamera" to SchoolIcons.FaceCamera,
    "flask" to SchoolIcons.Flask,
    "personX" to SchoolIcons.PersonX,
    "personCheck" to SchoolIcons.PersonCheck,
    "flag" to SchoolIcons.Flag,
    "cap" to SchoolIcons.Cap,
    "mail2" to SchoolIcons.Mail,
    "student" to SchoolIcons.StudentAffairs,
    "news" to SchoolIcons.News,
    "wrench" to SchoolIcons.Wrench,
    "mailbox" to SchoolIcons.Mailbox,
    "sliders" to SchoolIcons.Sliders,
    "bulb" to SchoolIcons.Bulb,
    "tv" to SchoolIcons.Tv,
    // —— Miuix 自带（备用 / 无对应原图标的应用）——
    "grid" to MiuixIcons.GridView,
    "weeks" to MiuixIcons.Weeks,
    "notes" to MiuixIcons.Notes,
    "lock" to MiuixIcons.Lock,
    "email" to MiuixIcons.Email,
    "book" to MiuixIcons.ContactsBook,
    "report" to MiuixIcons.Report,
    "alarm" to MiuixIcons.Alarm,
    "messages" to MiuixIcons.Messages,
    "promotions" to MiuixIcons.Promotions,
    "bankcards" to MiuixIcons.BankCards,
    "contacts" to MiuixIcons.Contacts,
    "community" to MiuixIcons.Community,
    "play" to MiuixIcons.Play,
    "folder" to MiuixIcons.Folder,
    "ok" to MiuixIcons.Ok,
    "layers" to MiuixIcons.Layers,
    "clock" to MiuixIcons.WorldClock,
    "link" to MiuixIcons.Link,
    "circle" to MiuixIcons.ContactsCircle,
    "info" to MiuixIcons.Info,
    "file" to MiuixIcons.File,
    // —— 备用池：语义规则没用到的，补给「想用的图标被别的应用先占了」的那些 ——
    "fileDownload" to MiuixIcons.FileDownloads,
    "folderFill" to MiuixIcons.FolderFill,
    "addFolder" to MiuixIcons.AddFolder,
    "convertFile" to MiuixIcons.ConvertFile,
    "moveFile" to MiuixIcons.MoveFile,
    "notesFill" to MiuixIcons.NotesFill,
    "tasks" to MiuixIcons.Tasks,
    "answer" to MiuixIcons.Answer,
    "reply" to MiuixIcons.Reply,
    "send" to MiuixIcons.Send,
    "edit" to MiuixIcons.Edit,
    "create" to MiuixIcons.Create,
    "copy" to MiuixIcons.Copy,
    "paste" to MiuixIcons.Paste,
    "rename" to MiuixIcons.Rename,
    "sort" to MiuixIcons.Sort,
    "filter" to MiuixIcons.Filter,
    "search" to MiuixIcons.Search,
    "scan" to MiuixIcons.Scan,
    "image" to MiuixIcons.Image,
    "photos" to MiuixIcons.Photos,
    "album" to MiuixIcons.Album,
    "music" to MiuixIcons.Music,
    "mic" to MiuixIcons.Mic,
    "phone" to MiuixIcons.Phone,
    "location" to MiuixIcons.Location,
    "map" to MiuixIcons.MapAlbum,
    "store" to MiuixIcons.Store,
    "help" to MiuixIcons.Help,
    "tune" to MiuixIcons.Tune,
    "translate" to MiuixIcons.Translate,
    "years" to MiuixIcons.Years,
    "months" to MiuixIcons.Months,
    "recent" to MiuixIcons.Recent,
    "stopwatch" to MiuixIcons.Stopwatch,
    "timer" to MiuixIcons.Timer,
    "theme" to MiuixIcons.Theme,
    "favorites" to MiuixIcons.Favorites,
    "pin" to MiuixIcons.Pin,
    "unpin" to MiuixIcons.Unpin,
    "unlock" to MiuixIcons.Unlock,
    "share" to MiuixIcons.Share,
    "download" to MiuixIcons.Download,
    "uploadCloud" to MiuixIcons.UploadCloud,
    "import" to MiuixIcons.Import,
    "refresh" to MiuixIcons.Refresh,
    "update" to MiuixIcons.Update,
    "backup" to MiuixIcons.Backup,
    "cloud" to MiuixIcons.CloudFill,
    "list" to MiuixIcons.ListView,
    "sidebar" to MiuixIcons.Sidebar,
    "mindMap" to MiuixIcons.MindMap,
    "merge" to MiuixIcons.Merge,
    "split" to MiuixIcons.VerticalSplit,
    "settings" to MiuixIcons.Settings,
    "carrier" to MiuixIcons.Carrier,
    "block" to MiuixIcons.Blocklist,
    "all" to MiuixIcons.All,
    "remove" to MiuixIcons.Remove,
    "clear" to MiuixIcons.Clear,
    "select" to MiuixIcons.SelectAll,
)

/**
 * 同义替代：首选图标被别的应用先占了时，按这张表继续找**意思仍然对得上**的图标，
 * 而不是随手从备用池里捞一个（否则「网络故障报修」会拿到一个音符）。
 * 表里列到的 id 都没空位了，才落到备用池。
 */
private val GLYPH_ALTERNATIVES: Map<String, List<String>> = mapOf(
    "teach" to listOf("teach", "grid", "weeks"),
    "calendar" to listOf("calendar", "weeks", "clock"),
    "chart" to listOf("chart", "notes", "report"),
    "clipboard" to listOf("clipboard", "file", "tasks"),
    "bolt" to listOf("bolt", "boltYuan", "theme"),
    "boltYuan" to listOf("boltYuan", "wallet", "card"),
    "card" to listOf("card", "bankcards", "wallet"),
    "wallet" to listOf("wallet", "card", "bankcards"),
    "handYuan" to listOf("handYuan", "wallet", "card"),
    "wifi" to listOf("wifi", "link", "globe"),
    "globe" to listOf("globe", "link", "wifi"),
    "nodes" to listOf("nodes", "layers", "mindMap"),
    "gauge" to listOf("gauge", "stopwatch", "timer"),
    "monitor" to listOf("monitor", "file", "list"),
    "megaphone" to listOf("megaphone", "promotions", "messages"),
    "seat" to listOf("seat", "book2", "group"),
    "book2" to listOf("book2", "book", "notes"),
    "group" to listOf("group", "contacts", "community"),
    "stadium" to listOf("stadium", "alarm", "layers"),
    "shield" to listOf("shield", "ok", "lock"),
    "door" to listOf("door", "lock", "block"),
    "faceGate" to listOf("faceGate", "scan", "lock"),
    "faceCamera" to listOf("faceCamera", "scan", "photos"),
    "flask" to listOf("flask", "help", "tune"),
    "personX" to listOf("personX", "block", "clear"),
    "personCheck" to listOf("personCheck", "ok", "select"),
    "flag" to listOf("flag", "promotions", "pin"),
    "cap" to listOf("cap", "years", "notes"),
    "mail2" to listOf("mail2", "email", "send"),
    "student" to listOf("student", "contacts", "circle"),
    "news" to listOf("news", "report", "file"),
    "wrench" to listOf("wrench", "report", "tune"),
    "mailbox" to listOf("mailbox", "messages", "reply"),
    "sliders" to listOf("sliders", "tune", "settings"),
    "bulb" to listOf("bulb", "help", "info"),
    "tv" to listOf("tv", "play", "photos"),
    "grid" to listOf("grid", "list", "sidebar"),
    "weeks" to listOf("weeks", "list"),
    "notes" to listOf("notes", "notesFill", "tasks"),
    "bolt" to listOf("bolt", "theme", "update"),
    "lock" to listOf("lock", "unlock", "block"),
    "email" to listOf("email", "send"),
    "book" to listOf("book", "notes", "layers"),
    "report" to listOf("report", "tasks", "convertFile"),
    "alarm" to listOf("alarm", "timer", "stopwatch"),
    "messages" to listOf("messages", "reply", "answer", "send"),
    "promotions" to listOf("promotions", "share", "download"),
    "bankcards" to listOf("bankcards", "store"),
    "contacts" to listOf("contacts", "circle", "all"),
    "community" to listOf("community", "help"),
    "play" to listOf("play", "mic", "photos"),
    "folder" to listOf("folder", "folderFill", "addFolder"),
    "ok" to listOf("ok", "select"),
    "layers" to listOf("layers", "mindMap", "merge", "split"),
    "clock" to listOf("clock", "recent", "years", "months"),
    "link" to listOf("link", "update", "refresh", "carrier"),
    "circle" to listOf("circle", "contacts", "all"),
    "info" to listOf("info", "help", "list"),
    "file" to listOf("file", "convertFile", "moveFile", "import"),
)

/**
 * 通用兜底：语义图标全被占完（比如六个「…申请」挤在一起）时，先在这几个「文档 / 申请」类的
 * 图标里找空位，实在没了才从整池里拿。比随机捞一个音符/时钟好看得多。
 */
private val GENERIC_DOC_IDS = listOf(
    "file", "convertFile", "moveFile", "import", "copy", "paste", "edit", "create",
    "notes", "notesFill", "tasks", "report", "list", "favorites", "answer", "send",
)

/** 备用池的取值顺序（就是上面这张表的声明顺序） */
private val GLYPH_IDS: List<String> = GLYPH_POOL.keys.toList()

/** 名称 → 图标 id；返回 null 表示没什么语义可挖，交给备用池 */
/**
 * 名称 → 图标 id。
 *
 * 2026-10-27 第二十轮用户要求：「工作的图标重绘依据是学校的原图标，依照学校的原图标进行重绘，
 * 主要优化线条流畅度，基本样式保留」——所以规则不再按「大致语义」抓 Miuix 图标，而是逐个对上
 * 学校办事大厅里那枚图标的形状（见 [SchoolIcons]）。顺序从具体到宽泛，
 * 免得「研究生休学审批」被「研究生」抢去学士帽、「研究生临时困难补助」被抢成学位服。
 */
private fun WorkApp.glyphId(): String? {
    val n = displayName
    return when {
        n.hitsAny("办事大厅", "一站式") -> "grid"
        // —— 教学 ——
        n.hitsAny("教务") -> "teach"
        n.hitsAny("课表", "课程", "选课") -> "calendar"
        n.hitsAny("成绩", "绩点", "分数") -> "chart"
        n.hitsAny("质量", "评教", "评价") -> "clipboard"
        // —— 用电 / 缴费 ——
        n.hitsAny("缴电费", "缴费", "充值") -> "boltYuan"
        n.hitsAny("用电", "电量", "水电") -> "bolt"
        n.hitsAny("一卡通", "校园卡", "银行卡") -> "card"
        n.hitsAny("财务", "报销", "经费") -> "wallet"
        n.hitsAny("补助", "资助", "困难", "贷款", "勤工") -> "handYuan"
        n.hitsAny("奖学金", "创新创业", "申报奖") -> "cap"
        // —— 门户 / 身份 ——
        n.hitsAny("邮箱", "邮件") -> "mail2"
        n.hitsAny("学工", "辅导员", "航小导", "AI", "助手") -> "student"
        n.hitsAny("门禁照片", "照片更新", "人脸采集") -> "faceCamera"
        n.hitsAny("宿舍门禁", "人脸门禁") -> "faceGate"
        n.hitsAny("门禁", "闸机", "通行", "进出", "校门") -> "door"
        n.hitsAny("休学", "停学", "离职") -> "personX"
        n.hitsAny("复学", "重新入学") -> "personCheck"
        // —— 场馆 / 生活 ——
        n.hitsAny("图书馆", "借阅", "书香", "学术资源") -> "book2"
        n.hitsAny("座位") -> "seat"
        n.hitsAny("研讨室") -> "group"
        n.hitsAny("场馆", "体育", "场地", "操场") -> "stadium"
        // —— 网络 / 信息化 ——
        n.hitsAny("测速") -> "gauge"
        n.hitsAny("显示我的IP", "IP地址") -> "monitor"
        n.hitsAny("校园网", "一键认证", "上网认证", "wifi", "WiFi") -> "wifi"
        n.hitsAny("账号", "密码", "上网") -> "globe"
        n.hitsAny("算力", "高性能", "集群", "托管") -> "nodes"
        n.hitsAny("实验") -> "flask"
        n.hitsAny("仪器", "机房", "设备") -> "sliders"
        n.hitsAny("文档库", "知识", "资料") -> "bulb"
        // —— 服务 / 报修 / 通知 ——
        n.hitsAny("报修", "维修", "故障") -> "wrench"
        n.hitsAny("信箱", "意见", "留言", "反馈") -> "mailbox"
        n.hitsAny("摊点", "活动", "布置", "宣传品") -> "flag"
        n.hitsAny("新闻", "稿件", "要闻", "公告", "宣传部") -> "news"
        n.hitsAny("直播", "视频", "录播", "电视") -> "tv"
        n.hitsAny("通知", "服务") -> "megaphone"
        n.hitsAny("安全", "应急", "平安", "一键通") -> "shield"
        // —— 审批类兜底：一只夹板 + 勾 ——
        n.hitsAny("申请", "审批", "申报", "报审", "报备", "备案", "流程", "出差",
                  "请假", "销假", "证明", "许可", "证书") -> "clipboard"
        else -> null
    }
}

/**
 * 学校原图标那套 id：语义相同时允许复用。
 *
 * 第二十轮改了图标来源之后，「新闻稿件报审」和「南航要闻」本来就是同一个意思，
 * 硬塞两个不同图标反而更乱；而 Miuix 备用池（音符/时钟这种）依然保持全页不重复，
 * 免得又回到第九轮那种「一堆随机图标」的观感。色相按顺序错开，一对同义图标也不会糊在一起。
 */
private val SCHOOL_GLYPH_IDS: Set<String> = setOf(
    "teach", "calendar", "chart", "clipboard", "bolt", "boltYuan", "card", "wallet",
    "handYuan", "wifi", "globe", "nodes", "gauge", "monitor", "megaphone", "seat",
    "book2", "group", "stadium", "shield", "door", "faceGate", "faceCamera", "flask",
    "personX", "personCheck", "flag", "cap", "mail2", "student", "news", "wrench",
    "mailbox", "sliders", "bulb", "tv",
)

private fun assignGlyphIds(apps: List<WorkApp>): Map<String, String> {
    val used = HashSet<String>()
    val out = LinkedHashMap<String, String>()
    apps.forEach { app ->
        val key = app.key()
        if (out.containsKey(key)) return@forEach
        val preferred = app.glyphId()
        // 先试首选，再试它的同义替代，最后才从备用池里拿一个没人用的
        val semantic = GLYPH_ALTERNATIVES[preferred].orEmpty().ifEmpty { listOfNotNull(preferred) }
        val chosen = semantic.firstOrNull { id ->
            if (id in SCHOOL_GLYPH_IDS) return@firstOrNull true
            used.add(id)
        }
            ?: GENERIC_DOC_IDS.firstOrNull { used.add(it) }
            ?: GLYPH_IDS.firstOrNull { used.add(it) }
            ?: preferred
            ?: "grid"
        out[key] = chosen
    }
    return out
}

/** 名称 → 图形；返回 null 表示用首字兜底 */
private fun glyphOf(id: String?): ImageVector? = id?.let { GLYPH_POOL[it] }

/** 课表、成绩这些 App 里已经有原生 Tab 的应用，工作页不必再列一遍 */
private val NATIVE_DUPLICATE_WORDS = listOf("课表", "课程", "成绩")

private fun WorkApp.isNativeDuplicate(): Boolean =
    NATIVE_DUPLICATE_WORDS.any { name.orEmpty().contains(it) }

@Composable
private fun WorkStateBox(text: String, action: String?, onAction: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { onAction() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = action,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
