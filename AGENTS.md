# AGENTS.md — 蓝航Lite（com.lanhang.miuix）

南昌航空大学官方 App「蓝航」（`cn.edu.nchu.nahang`，UA 标识 `NchuApp/1.0.14`）的第三方轻量客户端。
功能范围：账号登录 + 课表原生渲染（周视图/网格）+ 成绩（学期切换/统计）+ 工作圈（融云圈动态）+ 设置（亮/暗/跟随系统主题、课表导出 .ics/.csv/分享）；底栏四 Tab = **课表 / 工作 / 工作圈 / 我的**（2026-10-26 第六轮把原「成绩」与「我的 → 工作」互换：工作升为一级 Tab，成绩进「我的」二级页）；「我的」下挂**成绩** / 通讯录 / 消息 / **私信（融云 IM 2.9，2026-10-26 接入）** / 身份码 / 相册 / 个人资料 / 设置。不做 OA 应用列表。
（成绩曾被用户移除，2026-10-21 应用户要求加回。）

---

## 25. 推仓库与自动发版（2026-10-27，用户明确要求）

用户原话：

> 「帮我push到这个仓库 https://github.com/Nuclear-Potatoes/lanhang-lite-miuix ，并且用 action 推送 release，
> 以后如果我说需要 push 的话就自动 push+release，如果我不说则不需要这么做」

### 25.1 触发条件（常驻规则）

* 用户**明确说「push」/「推上去」/「发版」** → 才执行「同步源码 → 提交 → 推送 → 打 tag → Actions 出 Release」；
* 用户没说 → **只改代码、只构建、只安装验证，绝不联网推送**。

### 25.2 推送怎么走（本机网络只有这条路通）

GitHub 的 HTTPS 会 TLS 失败（本机代理 `127.0.0.1:7897` 对 github.com 不可用），**只有 SSH over 443 通**：

```powershell
# 本地克隆（已有）：D:\数据\Deepseek Harness工作区\lanhang-miuix\push\repo
# origin = ssh://git@ssh.github.com:443/Nuclear-Potatoes/lanhang-lite-miuix.git
& git -C $repo push origin main
& git -C $repo tag v1.0.0
& git -C $repo push origin v1.0.0     # 这一步触发 Actions 发版
```

`gh` CLI 在本机**没登录**（不能建仓库/改 Secrets），所以工作流只用 `secrets.GITHUB_TOKEN`
（默认就有），不需要任何自定义 Secret。

### 25.3 发版签名：`ci/debug.keystore` 随仓库提交

`app/build.gradle.kts` 早已支持「四个 `-PlanhangRelease*` 属性齐全才用正式签名，否则回落 debug 签名」。
CI 里传的就是仓库内的 `ci/debug.keystore`（= 本机 `~/.android/debug.keystore` 同一份），于是：

* CI 产出的 APK 与本机 `assembleRelease` 的 APK **同签名** → 能覆盖安装、不丢登录态；
* `.gitignore` 里加了 `!ci/debug.keystore` 例外；`*.jks` 仍然不入库。

### 25.4 发版清单（顺序别乱）

1. 改 `versionCode` / `versionName`（`app/build.gradle.kts`）与 `VERSION.md` 那一行（发行说明从这行摘）；
2. 本地 `assembleRelease` + 装机验证（每一轮都必须）；
3. `scratch/export_src.py` → `scratch/zip_src.py` 导出干净源码树（**必须带上 `.github/`、`ci/`、`gradlew*`、`gradle/wrapper/`**）；
4. 同步到 `push/repo`（保留 `.git`）、提交、`push origin main`；
5. `git tag vX.Y.Z` + `push origin vX.Y.Z` → Actions 自动出 Release；
6. 用 `curl https://api.github.com/repos/Nuclear-Potatoes/lanhang-lite-miuix/actions/runs?per_page=1`（免登录可用）确认 run 成功、Release 有资产。

### 25.5 仓库现状（2026-10-27 首次打通后记录）

* 仓库里原来的 `.github/workflows/blank.yml`（用户网页建的 "Build & Release APK"，依赖
  `ANDROID_KEYSTORE_BASE64` 等未必存在的 Secrets、且用 JDK 21）已**删除**，只保留 `release.yml`；
* `release.yml` 里签名是「有 Secrets 用 Secrets，没有就用 `ci/debug.keystore`」，两条路都验证过前者逻辑、后者实跑；
* 已实测：tag `v1.0.0` → Actions 成功 → Release 页有 `lanhang-miuix-1.0.0.apk` + `SHA256SUMS.txt`；
  把 CI 产物装到已装真机/模拟器上 **覆盖安装成功、没掉登录态**（签名指纹与本地一致）；
* 发布页：`https://github.com/Nuclear-Potatoes/lanhang-lite-miuix/releases`。

---

## 24. 第二十二轮反馈（2026-10-27）：个人主页收成**一份代码**（1.0.0 / versionCode 51）

用户原话：

> 「第一，从通讯录搜索A进入A主页，顶栏显示A的名字而非A的个人主页。第二，在A的个人主页点击蓝色姓名/头像
> 进入B的个人主页时，缺少过渡动画，第三，进入B的个人主页后，点击返回会直接返回通讯录首页而非A的个人主页，
> 顶栏显示也是B的名字而非B的个人主页。这应该说明通讯录中的个人主页实现和工作圈中的个人主页实现不一样。
> 把所有个人主页的实现都统一成一份代码，即与工作圈的个人主页实现一致。第三，从我的-相册进入我发过的所有动态后，
> 点击其他人的蓝色名字/头像会进入自己的个人主页。」

### 24.1 病根：主页被写了两份，而且两份都不是「栈」

| 入口 | 旧实现 | 症状 |
| --- | --- | --- |
| 工作圈 | `MomentsFragment` 里一份 `profileUid` **单槽位** | A 里点 B 只是换槽位，没有压栈 → 返回直接回列表 |
| 我的（通讯录 / 消息 / 私信 / 相册） | `MineFragment` 里另一份 `otherUserId` 单槽位 | 同上；且顶栏标题写的是**姓名**，不是「X 的个人主页」 |
| 我的 - 相册 | `MineScreen` 给 `MomentsScreen` 传了 `onOpenProfile = { onOpenProfile() }` | 把**自己**的开页回调当参数，忽略 uid → 点谁都是自己的主页 |

「单槽位」是根因：主页只有「当前是谁」一个变量，压第二层时把第一层覆盖掉，返回自然回不到 A。

### 24.2 新硬规则：个人主页只有 `profile/ProfileHost.kt`

`app/src/main/java/com/lanhang/miuix/profile/ProfileHost.kt` 是**唯一**实现，四个部件：

```kotlin
ProfileStackState          // 主页栈：uids + forward 方向 + push/reset/pop/clear/snapshot/restore
ProfileSlice / ProfileStore // 每个 uid 的资料 + 动态分页 + 乐观点赞评论（按 uid 缓存）
ProfileStackHost(stack, store, onTitle, onOpenProfile, onMessage, onImage, emptyText)
profileTitle(context, name) // 「X 的个人主页」/「个人主页」
```

宿主页面**只做三件事**，别再多写一份：

```kotlin
// 1. 开页：本页已在主页层 → push（自带推入转场），否则 reset 后再进 PROFILE_OTHER
if (page == MinePage.PROFILE_OTHER) profileStack.push(uid) else profileStack.reset(uid)
// 2. 返回：pop() 返回 true 说明还有上一层（回 A），false 才真的退页面
if (!profileStack.pop()) { /* 退页 */ }
// 3. 顶栏：ProfileStackHost 回调 onTitle() → showToolbarSub(profileTitle(...))，
//    但**必须**先判断「当前页面还是不是主页」，否则退场动画期间它会把刚进去的聊天页顶栏改回去
```

* 标题统一 `R.string.profile_title_of`（「%s 的个人主页」），名字没回来先显示「个人主页」；
* 转场统一 `MiuiNavStack(key = 当前 uid, forward = stack.forward)` —— **key 是 uid**，所以 A→B 是推入、B→A 是推出；
* 相册 / 我的主页里的「点自己」→ 走「我的主页」（`MinePage.PROFILE`），点别人才进 `PROFILE_OTHER`；
* `ProfileStore` 的点赞/评论是**乐观更新**（工作圈那套），失败回滚 + toast；宿主用 `onMutated` 回调把自己的 `revision++`。

---

## 23. 第十九轮反馈（2026-10-27）：按压高亮是个灰方块、图书馆座位预约改名

用户原话：

> 「第一，ui设计问题，比如在图书馆座位这一页，点击阅览室/开始时间等功能时，阴影没完全覆盖这一栏，而是呈现一个
> 正方形，阴影范围不正确，排查修复应用中所有类似问题。第二，图书馆座位顶栏改为图书馆座位预约，在工作常用功能中
> 的名字也一起改掉，保证工作中所有功能的名字和点击进入后顶栏的内容一致」

### 23.1 硬规则：卡片里的「整行可点」必须用 `Modifier.pressableRow`

裸 `Modifier.clickable { }` 的按压高亮（indication）是按**行自己那个矩形**画的：

* 四个角是**直角**，而外面的 `Card` 是 16dp 圆角 —— 按下去屏幕上就浮起一个灰方块，跟卡片边缘对不上；
* 卡片还带 `insideMargin` 时，高亮只覆盖**内容宽度**，左右各缩进一圈，看起来像「这一栏没被完全盖住」。

所以第十九轮加了统一入口 `ui/miuix/PressableRow.kt`：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .pressableRow(onClick = onClick, corner = 16.dp)   // 先裁圆角，再挂 clickable
        .padding(horizontal = 16.dp, vertical = 13.dp),   // 行内 padding 写在它后面
) { … }
```

约定（照着抄就行）：

1. **padding 在 `pressableRow` 之后**，高亮才会把 padding 一起盖住 = 整行；
2. `corner` 跟**卡片圆角**一致（Miuix `Card` 是 16dp），圆角对不齐一眼就能看出来；
3. 想让高亮贴到卡片左右边缘（MIUI 的整行压暗），就把这张卡的 `insideMargin` 横向设 0，宽度靠行内 padding 补回来 —— 图书馆座位的信息卡（阅览室/开始时间/时长）就是这么改的，文字 x 坐标和以前完全一致；
4. **整卡可点**（`Card(modifier = … .clickable { }`)）同理：`clickable` 前面必须 `.clip(RoundedCornerShape(16.dp))`；
5. `onClick = null` 表示这一行不可点，此时只裁剪、不挂点击。

本轮按这条规则改过的位置：图书馆座位信息三行、校园网「自助服务」整卡、我的（资料卡 + 所有设置行）、
通讯录联系人行、消息列表行、私信会话行、工作页「打开办事大厅」行；工作页常用功能行 / 应用宫格原本已裁过圆角，
这次把 12dp 统一成 16dp。头像是圆/小图标（`clip(CircleShape)`、`clip(RoundedCornerShape(9.dp))`）的一律不用动。

### 23.2 名称一致性：工作入口名 = 点进去的顶栏名

* 座位页顶栏 `library_seat_title`：`图书馆座位` → **`图书馆座位预约`**（`values` / `values-en` 同步）。
* 工作页常用功能里那条 OA 目录项服务端叫「座位预约」，和原生页顶栏对不上。现在 `WorkPage.kt` 里所有展示名都走
  `WorkApp.label()`：命中 `SEAT_APP_MARK`（座位预约）时返回同一个 `R.string.library_seat_title`，两边永远一字不差。
* 顺手修掉另一处不一致：「打开办事大厅」卡片点进去顶栏原来写「工作台」，现在用 `work_portal_title`，和卡片同名。

工作页入口 ↔ 顶栏实测对照（第十九轮逐条点开核对）：

| 常用功能入口 | 点进去的顶栏 |
|---|---|
| 查用电 / 缴电费 / 智慧教务（本科生） | 同名（`OaWebViewActivity.open(title = app.displayName)`） |
| 校园网登录 | 校园网登录（`work_campus_net_title`） |
| 图书馆座位预约 | 图书馆座位预约（`library_seat_title`） |
| 打开办事大厅 | 打开办事大厅（`work_portal_title`） |
| 最近使用里的 掌上学工 / 学生邮箱 / 宿舍门禁 / 校园一卡通 / 新闻稿件报审 | 同名（同上，走 `displayName`） |

---

## 22. 第十八轮反馈（2026-10-27）：学生邮箱白屏、我的预约转场方向、图书馆缺区域、页面留白

用户原话：

> 「首先，校园网测试正常。但工作页面中学生邮箱、校园网账号设置页面打开后无响应，直接在原界面卡死，且无法打开
> 其他应用，排查所有工作应用，避免该问题。第二，图书馆座位功能中我的预约二级菜单过渡动画方向与其他的过渡动画
> 不一致。第三，图书馆预约中缺少一块区域，没办法预约该区域的座位，图书馆一楼分为102自习室和1楼学习区，
> 具体可以参考 http://lib-zw.lib.nchu.edu.cn/#!/Seat/Index/searchSeats?... （注意，需要将你的浏览器 ua 改为
> 微信 UA 才能正常访问该链接），参考链接中的内容做图书馆座位预约这个功能。第四，工作页面、校园网登录、图书馆
> 座位预约的界面布局不够美观，与屏幕两侧贴得太近，可以参考我的页面中与屏幕两侧的间距设计页面」

### 22.1 硬规则：「onPageFinished」不等于「看得见」，白板必须用**正文高度**判定

学生邮箱（`https://mail.stu.nchu.edu.cn/mail/`，Roundcube elastic 皮肤）打开后是**纯白**，用户以为又卡死。
实测 WebView 里 DOM 是完整的：`innerText` 有「统一身份认证 / 账号密码登录 / 南昌航空大学学生邮箱」，
`document.styleSheets.length=3`、`scripts.length=14`、viewport `417x747` —— 但
**`document.body.getBoundingClientRect().height === 0`**，`elementFromPoint(200,400)` 只命中 `<html>`。
元素都在、一个像素都不占，屏幕自然全白。curl 侧同一 URL 是 200 / 7721 字节，换 UA、去掉伪造请求头都一样，
所以这不是服务端问题，是 **WebView 侧百分比高度被量成 0**。

现在的做法（`ui/OaWebViewActivity.kt` 的 `BLANK_CHECK_JS` + `onPageFinished`）：

1. 页面 finished 后量 `正文长度 | 正文高度`；
2. 高度 ≤ 2px 就先注入 `html,body{height:auto!important;min-height:100%!important}` 再量一次 ——
   学生邮箱就是靠这一条从纯白变成能看见登录框的；
3. 补完还是 ≤ 2px 才判定白板，露出「页面可能是空白的 / 重新加载 / 用浏览器打开」。

**别再拿 `innerText` 长度当唯一判据**：`bits=34` 的邮箱页原来会被判成「有内容」，于是既不给提示也不重试。

### 22.2 硬规则：`AndroidView` 会盖住同一 Column 里排在它前面的 Compose 内容

图书馆座位页的「区域」胶囊原来放在座位图那张 `Card` 里、`SeatMapHost`（`AndroidView`）之前，
`uiautomator` 能 dump 到胶囊节点（`[197,966][713,1012]`），截图里却什么都看不到 —— 座位图那块真实 View
把同列上方 Compose 画的像素盖掉了。所以**不要把 Compose 控件压在 `AndroidView` 前面指望它可见**：
要么控件放到 `AndroidView` 之后，要么干脆挪到**另一张 Card**（现在「区域」是一张独立卡）。

### 22.3 硬规则：图书馆一层的所有区域在 `allContent` 的 `com.CatCon` 里，`data` 只有「推荐的那一个」

用户说「缺少一块区域」的根因：`/Seat/Index/searchSeats` 返回里 `data` 只带服务端推荐的一间，
真正的区域列表在

```
allContent.children[i]                    // ui_type = com.CatCon
  └── children（com.BlockList 对象，不是数组）
        └── children[]                    // 每个区域一个 ht.Seat.RecommendSeatItem
              ├── roomName
              └── seatMap{ info{id,title,plan,width,height}, POIs[] }
```

`LibrarySeatApi.parseAreas()` 把每个区域都解析出来（`图书馆一楼` 实测两个：
一楼自习区（位置上有插座）104 座 / 自习室（102）292 座），`data` 只作为老结构的兜底。
区域切换后重新渲染 `SeatMapHost`，预约仍然只提交 `beginTime/duration/seats[0]/seatBookers[0]`，
不需要 `space_id`，所以任何区域的座位号都能直接约。

### 22.4 硬规则：二级页的转场方向靠 `WorkRoute.depth`，深度相同就判不出方向

「我的预约」以前和「图书馆座位」同为 `depth = 1`，`MiuiNavStack` 拿不到「更深」的信号，
于是进场方向与其它二级页不一致。现在 `LIBRARY_SEAT=1`、`LIBRARY_BOOKINGS=2`、`LIBRARY_SIGN_CODE=3`，
推入时子页从右侧滑入、父页左移 24%，返回时完全镜像（用 `screenrecord` 抽帧验证：
`f046` 父页左移、`f049` 子页从右侧进入）。

### 22.5 硬规则：页面横向留白统一 12dp（与「我的」页一致）

用户反馈「与屏幕两侧贴得太近」。工作页、校园网登录、图书馆座位三处的 `Card` 外框一律
`padding(horizontal = 12.dp)`，实测卡片左右边缘都在 `x=35..1185`（1220 宽），和「我的」页完全一致。
新增页面照抄这条，不要自己发明 16/20dp。

### 22.6 一张卡里只有一颗按钮时，左边要补状态文案

校园网登录页的「动作」卡原来只放一颗右对齐的「下线 / 立即认证」，左边空一大块。
现在左边显示 `campus_net_status_online/offline`（已认证 / 未认证），右边才是按钮，行内
`Spacer(Modifier.weight(1f))` 撑开。

### 22.7 工作页各应用实测（第十八轮，`emulator-5554`，学号 260106336 会话）

| 应用 | 实际打开 | 结果 |
| --- | --- | --- |
| 查用电 | `m.app.nchu.edu.cn/hq/RemainEle.aspx` | 正常（bits 154 / 高 475） |
| 缴电费 | `ykt-wechat.../elecpay/queryelec.html` | 页面正常，服务端回「json参数不能为空」——该 H5 需要入口参数，非卡死 |
| 校园网登录 | 原生页 | 正常，在线状态/IP/流量都在 |
| 座位预约 | 原生页 | 正常，两个区域可切换 |
| 智慧教务（本科生） | `h5.jwc.nchu.edu.cn/#/chooseTypeLogin` | 正常（登录页） |
| 掌上学工 | `xgc-sys.xgc.nchu.edu.cn:8080/index_xs.html` | 正常（Hi，焦普田 + 各服务宫格） |
| 校园一卡通 | `ykt-wechat.../wechat/home/index.html` | 正常 |
| 学生邮箱 | `mail.stu.nchu.edu.cn/mail/` | **白屏已修**（见 22.1） |
| 网络认证自助服务 | `passport.nchu.edu.cn`（统一身份认证） | 正常（有图有导航） |

## 21. 第十六轮反馈（2026-10-27）：诊断崩溃、群聊消失、校园网登录与图书馆座位（原生页移植）

用户原话：

> 「第一，设置-关于-诊断与日志点击即崩溃。第二，私信栏中没有群聊功能了，修复这个问题。第三，原项目更新了一个
> 校园网功能，能做到输入密码后自动登录，将这个功能添加到工作的常用功能中，命名为校园网登录，运行底层逻辑可以
> 完全参考这个项目，但是 ui 界面应该严格遵守 miuix 设计规范，再移植这个项目中的图书馆座位预约功能，替换掉
> 工作-常用功能中座位预约的功能，要求同上，逻辑可以参考原项目，但 ui 设计必须 miuix 化」

### 21.1 硬规则：`Dialog` 必须挂在 **Activity** 上，`AppContext.instance` 只能做 Application 级的事

「诊断与日志」点击即崩，崩溃栈是 `android.view.WindowManager$BadTokenException: Unable to open window --
token null is not valid; is your activity running?`，抛在 `Dialog.show()`。根因：`MineFragment.showDiagnostics()`
把 `AppContext.instance`（Application）当 Context 传给 `showMiuixDialog`，Application 没有 window token。
同一类错在 `MineFragment` 里还有 6 处（主题/语言选择、导出、导出进度、退出登录确认）。

- 现在两处都改了：**7 个调用点全部换成 `requireActivity()`**，并且 `ui/miuix/MiuixDialog.kt` 加了兜底
  `object DialogHost`（`App.registerActivityLifecycleCallbacks` 里记住当前 resumed 的 `ComponentActivity`，
  `resolveDialogHost(context)` = `context.findComponentActivity() ?: DialogHost.current()`），
  宿主为空时 **不 show** 只打一行 `MiuixDialog` 日志 —— 宁可没弹窗，也不能把用户打崩。
- 新代码写对话框：`showMiuixDialog/showMiuixConfirm/showMiuixPicker/showMiuixInput/showMiuixProgress`
  的第一个参数一律是**当前 Activity**（`requireActivity()`、Compose 里的 `LocalContext.current` 也可以，
  它来自 Activity）。

### 21.2 硬规则：判断「幽灵会话」不能只看预览为空，群聊没有消息也是正常的

「私信里没有群聊了」的根因在 `ImRepository.refreshConversations()`：
`mapped.partition { it.preview.isBlank() }` 把**任何预览为空**的会话当成幽灵会话丢掉，
而 `previewOf()` 对 `UnknownMessage` / `latestMessage == null`（新建的群、还没人说话）返回空串 ——
于是群聊被整条删除（第十二轮清幽灵消息时留下的坑）。

现在分区条件是 `preview.isBlank() && !it.isGroup`（只清「没有群属性又没有预览」的僵尸单聊），
并且 `ImScreens.previewText()` 给群聊补了占位文案：空群显示 `im_preview_empty_group`（还没有消息），
不支持的内容显示 `im_preview_unsupported`（[其他消息]）。

### 21.3 「校园网登录」：逻辑整包移植，界面 100% Miuix，**不许自动重连**

- 逻辑层（`campusnet/`，从原项目 `com.lanhang.lite.campusnet` 平移，包名换成本项目）：
  `SrunCrypto`（深澜的 xEncode / base64 / HMAC-MD5）、`SrunApi`（`get_challenge` → `srun_portal` →
  `rad_user_info` 三段 JSONP，含 callback 解析、AC 探测、域名回退）、`CampusNetAuth`（认证/下线的门面）、
  `CampusNetStore`（学号/AC/域名/记住密码，密码用 **Android Keystore AES-GCM** 加密后存 SP，
  解出来只在本机）。
- 界面 `campusnet/CampusNetScreen.kt`：状态卡（已认证/未认证/无法连接 + IP/在线时长/流量 + 刷新）、
  账号卡（学号 + 密码 + 记住密码 Switch + 加密说明）、动作卡（立即认证 / 下线，下线走 `showMiuixConfirm`）、
  自助服务入口（照旧那张 H5，走 `OaWebViewActivity`）。**没有任何一条后台自动认证/自动重连代码**：
  用户点按钮才发请求，这是「不许造假功能」的延伸。
- 常用功能入口不是服务端应用：`work/WorkRoute.kt` 定义 `NATIVE_CAMPUS_NET_ID = "native:campus_net"`，
  `WorkPage.COMMON_SLOTS` 里的 `CommonSlot.Native` 用字符串资源 `work_campus_net_title` 现造一个
  `WorkApp`（`homePageUrl = ""`，永远不走 WebView），点击由 `openApp` 拦下来推原生页。

### 21.4 「图书馆座位预约」：接管服务端那个 H5 入口，换成原生选座页

- 逻辑层（`library/`）：`SeatModels.kt`（房间/规则/座位/预约/动作枚举）、`LibrarySeatApi.kt`
  （洄图 SDUI 协议：`/Space/Category/list`、`/Seat/Index/searchSeats`、`/Seat/Index/booking`、
  `/Seat/Index/myBookingList`、`/Seat/Index/mySignQRCode` 等，登录复用 `RcApi.getLdapToken` 换 `lib-zw` 会话）、
  `SeatMapView.kt`（自绘座位图 + 双指缩放 + 拖动 + 平面图背景，**配色由外部注入 ARGB**，不读 Material 主题）。
- 界面：`library/LibrarySeatScreen.kt`（房间/开始时间/时长三行选择器 → 座位图 → 预约确认）、
  `library/SeatBookingsScreen.kt`（我的预约，按订单状态机给 签到/暂离/续座/签退/取消）、
  `library/SeatSignCodeScreen.kt`（服务端画好的二维码，可刷新）。
- 时段规则**不写死**：`SeatRules.startHours(nowHour, defaultBeginHour)` / `durationHours(startHour)`
  都由服务端下发的开闭馆时间与 max_duration 算；没时段就老实说「今天已无可用时段」。
- 服务端那个叫「座位预约」的 H5 应用**只用来占位**（名字、图标照旧）：`openApp` 里
  `app.displayName.contains("座位预约")` → 推 `WorkRoute.LIBRARY_SEAT`，在 OA 分支之前拦掉。

### 21.5 硬规则：工作 Tab 里的新页面**不许新开 Activity、不许自绘顶栏**

`WorkFragment` 从「只挂一个 `WorkPage`」改成**自带页面栈**：`mutableStateListOf<WorkRoute>` +
`MiuiNavStack(key = page, depthOf = { it.depth })`（转场仍是全应用唯一那份），
顶栏仍只调 `showToolbarMain(工作)` / `showToolbarSub(title, onBack = { pop() })`，
`BackHandler(enabled = stack.size > 1)` 管返回键（栈底交系统退出）。
以后工作页再加原生功能，**照这个模式加一条 `WorkRoute` + 一个 `CommonSlot.Native`**，别另起炉灶。

### 21.6 座位图在 Compose 里的两个坑（`AndroidView` + 自绘 View）

1. **`AndroidView` 不会裁掉越界绘制**：座位图的 View 若把内容画得比自己的边界大（比如给格子设了 7dp 下限、
   内容比视口宽），会直接画到卡片外面去 —— 实测图例「空闲/已占/已选」被座位盖住。
   现在 `onMeasure` 在有高度约束时按「整张平面图都装得下」算基准格（`min(视口宽/列数, 视口高/行数)`，
   **不设下限**），要看清细节就双指放大（缩放上限 4×）。
2. **座号字号必须跟着格子走**：原来固定 `dp(8)`，一格两座（「95 94」）时会糊成「103102」。
   现在 `textSize = min(8dp, 格子宽 × 0.46)`。

### 21.7 本轮怎么验的

模拟器（旧包 `com.lanhang.lite` 有登录态，临时把 `applicationId` 改回旧包名构建，验完改回 `com.lanhang.miuix`）：

- 我的 → 关于 → 诊断与日志：Miuix 面板正常弹出（关闭 / 分享），`adb logcat -b crash -d` 全空 ✓
- 我的 → 私信：群聊回来了（`2601063` 带「群聊」标签、预览「张敏佳：还没有消息」）✓
- 工作 → 常用功能：查用电 / 缴电费 / **校园网登录** / **座位预约** / 智慧教务 —— 顺序与用户要求一致 ✓
- 工作 → 校园网登录：状态卡真实拉到 `已认证 · IP 10.86.80.106 · 在线 244 小时 21 分 · 流量 54 GB` ✓
- 工作 → 座位预约：真实房间「图书馆一楼」、08:00 / 1 小时、座位图 + 图例 + 空闲 81 座；
  我的预约里能看到历史订单（系统签退结束 / 已签退）✓
- 两次截图目录：`artifacts/screenshots/r17-campus*.png`、`r17-seat*.png`、`r17-bookings.png`、`r17-im.png`、`r17-diag.png`

> 验证限制：模拟器连不到 `NCHU_Wireless`，「输入密码 → 自动登录」这条链路只能靠代码保证
> （`SrunApi` 的三段式与上游一致，页面会把服务端的错误原文透出来）。

---

## 20. 第十五轮反馈（2026-10-26）：工作页首次进入会「自己滚到最近使用」、顶栏异常收起

用户原话：

> 「初始状态下第一次进入应用，没有做出滑动动作时，从课表页跳到工作页，没有到页面顶端，而是直接到最近
> 使用了，导致顶栏异常收缩起来。『切到 tab 时不应保留旧滚动位置』→ 切到 tab 时应保留旧滚动位置，
> 但是在没有滚动的初始状态应到页面顶端。」

### 20.1 硬规则：LazyColumn 顶部「异步卡片」必须**常驻 item 槽位**

`work/WorkPage.kt` 顶部两张卡片（`item("common")` 常用功能、`item("portal")` 办事大厅）以前写成
`if (数据就绪) { item("k") { … } }` —— 数据一到就**插到最前面**。LazyColumn 是**以当前首个可见项为锚**
保位置的：插入一张卡片后，锚点那一项的下标从 0 变成 1（插两张就是 2），于是

- 页面看起来「自己往下滚了一屏」，停在「最近使用」那一屏（用户看到的第 1 个现象）；
- `ReportListScroll` 只看下标（`firstVisibleItemIndex > 0` → `1f`），立刻把顶栏收成小标题
  （用户看到的第 2 个现象「顶栏异常收缩」）。

正确写法是**槽位常驻、内容按条件显隐**（空内容高度 0，没有下标位移）：

```kotlin
item("common") {
    if (common.isNotEmpty() && keyword.isEmpty()) WorkCommonCard(…)
}
item("portal") {
    if (portal.isNotBlank()) Card(…) { … }
}
```

用脚本改这两个块时**只能交换 `if (…) {` 和 `item("k") {` 这两行的顺序**（两种写法的花括号数量与收尾
两行完全相同，零重排、零风险）。别去「重新缩进整块」：第一版补丁就是那样做的，把 `Card{}` 的主体吃掉了。
新页面凡是「数据异步到位才出现的顶部卡片」，一律先给空槽位。

### 20.2 首次落位保险：靠**进程级**标记，不靠局部 `remember`

`WorkPage` 里另有一道保险：数据到位时若用户**还没亲手拖过**列表、页面却不在顶部，就 `scrollToItem(0)`。

- 「用户拖过没有」用 `listState.interactionSource.interactions` 收集 `DragInteraction.Start`；
- 「是不是首次落位」必须用 `ui/miuix/PageListStates.kt` 的 `stickyListLanded(key)` /
  `markStickyListLanded(key)`（**进程级** Set）。不能用局部 `var landed by remember`：切 Tab 是
  detach/attach，整棵组合重建，局部变量复位成「首次」，于是把已经恢复出来的滚动位置又抹掉
  —— 正是 §18.2 / 第十三轮那个「切回来跳回顶部还闪一下」的复发路径。

### 20.3 本轮怎么验的（可复现）

模拟器（已登录的旧包 `com.lanhang.lite`，临时把 `applicationId` 改回旧包名构建，验完改回）：

1. `am force-stop` → 冷启动（**必须是新进程**，`StickyStates` 才是空的）→ 课表加载出真实数据；
2. 点「工作」：400ms 与 3s 两张截图都是**大标题「工作」+「常用功能」卡片在最顶**，
   没有「先收起来再弹回」的闪动（`artifacts/screenshots/r16-work-400ms.png` / `r16-work-settled.png`）；
3. 在工作页滑两下（顶栏收起）→ 点课表 → 点工作：两张截图 **sha256 完全相同**
   （`r16-work-scrolled.png` = `r16-work-restored.png`）→ 位置记忆没被破坏，第十三轮的行为保住了。

---

## 19. 第十四轮反馈（2026-10-26）：顶栏收放收成「唯一一份代码」+ 包名迁移 `com.lanhang.miuix`

用户原话：

> 「从课表（顶栏展开）切到工作（顶栏收起）时，字体发生了变化是正确的，但是顶栏范围没有发生变化。将应用中
> 所有类似场景（tab 间的切换、一级菜单到二级菜单）只要涉及顶栏收缩扩张的场景的代码都统一为从我的-工作圈之间
> 的切换。我的-工作圈之间的切换动效没有问题，统一成这份代码，未来随时调用」
> 追加：「另外 改一下包名，新包名为 com.lanhang.miuix，整理好一份源码以便我提交到新github仓库」

### 19.1 硬规则：顶栏收放只准调 `rememberBarCollapse`

`ui/shell/ShellBars.kt` 新增并**固化**为全应用唯一入口：

```kotlin
@Composable
fun rememberBarCollapse(shape: Float, scroll: Float): Float   // 返回 0..1 的收放度
```

- `shape` = 形态档（0 大标题 / 1 紧凑条，由 `target` 决定，自带 300ms tween）；
  `scroll` = 一级页列表滚动的收起程度（只有 `large && !hasBack` 的页面才喂它）。
- 顶栏本体**只认这一个数**：`height = lerp(96dp, 56dp, collapse)`、`fontSize = lerp(32sp, 18sp, collapse)`、
  副标题透明度/高度、左侧按键透明度、标题居中位移全部由它插值 → 高度与字号**不可能再各走各的**。
- 调用侧就三行：
  ```kotlin
  val shape = if (target >= 0.5f && latch >= 0.995f) target else morph.value   // 同帧闩锁，见 §18.1
  val scrollTerm = if (spec.large && !spec.hasBack) ShellScroll.progress else 0f
  val collapse = rememberBarCollapse(shape, scrollTerm)
  ```
- **任何新页面/新场景（新 Tab、新二级页、新弹层）都不许再写收放动画**：`showToolbarMain/showToolbarSub`
  改标题即可；要动「收放手感」只改 `object BarAnimation`（时长/曲线）或 `rememberBarCollapse`，
  不许在页面里另起一套。

### 19.2 为什么以前会「字体变了、顶栏范围没变」

历史实现里收放度是 `maxOf(base, scrollSmooth.value)`——两个写者：`base` 走形态 tween、`scrollSmooth`
走换 Tab 的 tween，由两个不同 effect 驱动，可能落在不同帧上，肉眼就是「字缩了、条没缩」。
现在**只有一个 `Animatable`、一个 goal**（`maxOf(shape, scroll)`）：换 Tab 时整档 tween、同页手指滚动时 `snapTo`，
于是高度、字号、内容区起点（`fragmentContainer` 的 top 跟着 `shellTopBar` 的测量高度走）在同一条曲线上同步变化。

### 19.3 本轮怎么验的（可复用探针）

临时在 `LanhangTopBar` 打 `Log.i("BarDbg", "c=… barPx=…")`（`onSizeChanged` 取**真实测量高度**），
再在 `MainActivity` 给 `fragmentContainer` 挂 `addOnLayoutChangeListener` 打 `container.top`：

- 我的（已收起，`wm size 1220x1500` 才滚得动）→ 课表 → 我的：`collapse` 逐帧 0→1、`barPx` 281→164、
  `container.top` 351→234，**每帧只降 1px**（真在动画，不是跳变）✓
- 探针用完必须删；当时打补丁的脚本留在 `scratch/probe_bar.py` 供对照。

### 19.4 包名迁移 `com.lanhang.lite` → `com.lanhang.miuix`（本轮已做，不可回退）

- 改三处：`app/build.gradle.kts` 的 `namespace` + `applicationId`、源码目录
  `app/src/main/java/com/lanhang/lite` → `.../com/lanhang/miuix`、以及 76 个文本文件里的包引用（kt/xml/kts/md）。
  清单用的是相对类名（`.App` / `.ui.MainActivity`），不用改；脚本 `scratch/rename_pkg.py` 末尾会打印残留（必须为「无」）。
- **新包名 = 全新应用**：旧包 `com.lanhang.lite` 的数据（登录态、课表缓存）**不继承**，装完要重新登录；
  `pm install -r` 只对同包名有效。两个包可以并存（模拟器上就是 `com.lanhang.lite` + `com.lanhang.miuix` 同时装着）。
- 版本同步到 **0.22.0 / versionCode 45**（包名迁移按大更新算，见 `VERSION.md` 规则）。
- 提交用源码树：`lanhang-miuix\lanhang-miuix-src\`（已剔除 `build/`、`.gradle/`、`local.properties`、
  截图与抓包产物），`lanhang-miuix-src.zip` 是它的压缩包。

---

## 18. 第十三轮反馈（2026-10-26）：顶栏在「不同页面之间」的衔接（硬规则）

用户原话：

> 「第一，在我的页（顶栏缩小之后）进入二级菜单后，标题依旧会突然放大一下再缩小。修复这个问题。第二，在工作
> 页面滑动到顶栏缩小后，切到其他 tab 时再切回工作，工作就回到了最顶端，还会闪动一下，参考我的页面进入二级
> 菜单后顶栏的动画，修复这个问题。主要需要解决在不同页面切换时，顶栏的衔接问题，要以上面的为例拓展到应用
> 的所有类似场景」

### 18.1 硬规则一：闩锁必须**同帧**生效，不能等 effect

`ui/shell/ShellBars.kt`：`collapse = maxOf(base, scroll)`，其中 `scroll` 只对「大标题形态且没有返回键」的
一级页生效。所以从**已经滚到收起**的一级页进二级页时，`scroll` 会瞬间归零，只有 `base` 能救场，
而 `base` 来自 `morph`（上一页是 0）。

- ❌ 错：只在 `LaunchedEffect(target)` 里 `snapTo(target)` —— effect 在**这一帧画完之后**才跑，
  那一帧就是用户看到的「标题突然放大一下」。
- ✅ 对：`val base = if (target >= 0.5f && lastCollapse >= 0.995f) target else morph.value`
  （`lastCollapse` 是 `SideEffect` 记下的**上一帧真正画出来的档位**）。同一帧就按目标画，才是「直接变换文字」。

### 18.2 硬规则二：一级列表的滚动状态必须**跨 Tab 切换存活**

Tab 切页是 `Fragment.detach()/attach()`：fragment 实例留着，**view 被销毁重建**，整棵 Compose 组合重来，
`rememberLazyListState()` 从 0 开始 → 画面跳回顶端，顶栏跟着闪。

- `ui/miuix/PageListStates.kt` 里新增进程级 `StickyStates.listStates`：
  `rememberStickyListState("tab_work")` / `PageListStates(namespace).of(key)` 都从这个 map 取**同一个实例**。
  工作页、工作圈动态流、我的根页与各二级页、通讯录全部走它。key 必须稳定（`tab_work` / `tab_moments` /
  `mine#<MinePage>` / `contacts#<deptId>`）。
- 光恢复位置还不够：`WorkPage` 里「首次加载完成后 `scrollToItem(0)`」（为了让插在最前面的「常用功能」可见）
  的 `landed` 标志也是 `remember` 出来的，重建后复位 → 又把恢复的位置抹掉。
  **归位必须加前置判断**：只有 `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`（本来就停在顶部）
  才 `scrollToItem(0)`。
- 顶栏档位本身早就按 Tab 分账（`ShellScroll.perTab`），列表位置一恢复，`ReportListScroll` 的补报
  就会把档位写回 1，切回来的第一帧就是收起态，不闪。

### 18.3 硬规则三：切 Tab 的档位跳变要 tween，手指滚动要即时

`LanhangTopBar` 里滚动项过一个 `Animatable scrollSmooth`：

```kotlin
LaunchedEffect(scroll, env) {                     // env = ShellScroll.activeTab
    if (env != envSeen) {                         // 换 Tab：整档跳变 → 平滑过渡（与转场同拍）
        envSeen = env
        scrollSmooth.animateTo(scroll, tween(BarAnimation.DURATION_MS, easing = BarAnimation.Easing))
    } else if (abs(scroll - scrollSmooth.value) > 0.002f) {
        scrollSmooth.snapTo(scroll)               // 同一页里的滚动：立刻跟手，不许动画
    }
}
```

- **不要**用「动画进行中」布尔标志位分流：实测 effect 被取消（换 Tab 抢跑）时标志没复位，
  之后所有 `snapTo` 都被跳过，顶栏再也收不起来。一个 `LaunchedEffect(scroll, env)` 自带取消语义，安全。
- 只在 `activeTab` 变化时补动画；`large`/`hasBack` 变化时**不能**也动画滚动项，否则 `base` 在放大、
  滚动项在缩小，两边叠加又变成「先放大再缩小」（正是用户讨厌的效果）。

### 18.4 本轮怎么验的（照做）

模拟器 `wm size 1220x1500` 把屏幕压矮，页面才滚得动（本机 2656 高时「我的」「工作」一屏装得下，
根本复现不了用户的问题）。再按老办法把 `NavMotion.DURATION_MS` 临时改 **2000** 逐帧看，`screenrecord` 在这台
模拟器上偶发录出黑帧，改用**连续 `screencap` 定点抓帧**（150/350/700/1200/2000ms）更可靠：

- 我的（已收起）→ 消息：五帧里顶栏全程紧凑，只有标题交叉淡化 ✓
- 工作（已收起）→ 工作圈 → 工作：回到工作时列表停在原位置（`校园服务` 那一行没变），顶栏全程紧凑 ✓
- 验完必须改回 300 再出包，并 `wm size reset`。

---

## 17. 第十二轮反馈（2026-10-26）：通讯录切层闪烁、顶栏二次收放、幽灵私信、无效系统应用、缴电费返回

用户原话：

> 「第一，通讯录中切换不同层级时会闪一下。第二，在我的界面中下滑（顶栏已经缩回）再点入消息二级菜单，顶栏会
> 闪一下再缩小，没有必要，且效果割裂，进入任何页面都要判断上一级的顶栏是否回缩，如果回缩了的话就不需要再做
> 放大再缩小这个效果，直接变换文字即可。第三，安装一段时间之后会收到【并不支持的信息】通知，在群聊中也有类似
> 通知，修复幽灵私信。第四，工作-系统应用中有一个无效的通知服务，去掉系统应用这个选项」
> 追加：「缴电费依旧会卡在内部网页无法退出至上一级，修复这个问题」

### 17.1 通讯录切层闪一下（`mine/ContactsScreen.kt`）
根因：每次下钻/返回都重新组一个 `DeptContent`，`entries` 从 `null` 起步，先闪一帧「正在加载」再出列表。
修法：新增进程内 `object BranchLevelCache`（LRU，最多 24 层），`entries` 用缓存值同步初始化；加载框再加
180ms 延迟才出现（`LaunchedEffect(dept.id, entries == null)`）。磁盘缓存仍归 `ContactsRepository`，这里只省重组开销。

### 17.2 顶栏「放大再缩小」（`ui/shell/ShellBars.kt`）
根因：`collapse = max(base, scroll)`，而 `scroll` 只对 `large && !hasBack` 的一级页生效。从已收起的「我的」
进二级页时 scroll 立刻归零、`morph` 还是 0 → 顶栏先弹回大号再收。
修法：`lastCollapse` 记住上一帧的收放度；目标已收起且上一帧已收起时 `morph.snapTo(target)`，**只做文字交叉淡化**。
这条是用户明确要求的通用规则：「进入任何页面都要判断上一级的顶栏是否回缩，回缩了就直接变换文字」。

### 17.3 幽灵私信 /「并不支持的信息」（`im/ImRepository.kt`）
根因：`UnknownMessage` 和未知内容被映射成「[暂不支持的消息]」气泡，并且同样走 `notifyIncoming`，
于是每次收到插件类自定义消息都会凭空多出一条会话 + 一条通知。
修法：`ImMessage.of` 对这两类返回 `null`（不建气泡、不推通知）；`refreshConversations` 把预览为空的会话
`partition` 出来并 `clearMessagesUnreadStatus` 清角标；`InformationNotificationMessage` 正常取正文。

### 17.4 工作-系统应用里的无效项（`work/WorkPage.kt`）
服务端下发的 `系统应用` 分组里只有无用的「通知服务」。UI 层用 `HIDDEN_SECTIONS` / `HIDDEN_APPS` 过滤掉整组，
数据层不动（工作页数据完全来自服务端，本地不留副本）。

### 17.5 缴电费卡在内部网页（`ui/OaWebViewActivity.kt`，本轮真机问题，本地复现后重写）
本地起了个测试站点（`scratch/oa_test_server.py`：`/start → /pay → /result`，而 `/result` 302 跳回 `/pay`）
把故障复现出来，`OaBack` 日志显示历史长成 `start → pay → result → pay`，返回弹到看不见的 `result` 又被弹回，
兜底只好直接退出 —— 也就是用户看到的「卡在内部网页」。三处叠加原因：
1. `onPageStarted` 一上来就 `cancelBackFallback()`，600ms 兜底在真正需要时早已不存在；
2. 为了给每个请求带伪装头，每次跳转都 `loadUrl`，而 `goBack()` 之后页面只要自己重定向回来，历史又长一条；
3. 302 重定向同样会进 `shouldOverrideUrlLoading`（不是 `doUpdateVisitedHistory` 独有），
   把用户根本没看见的中间页也记进了历史。

现在的实现**不再依赖 WebView 的历史语义**：
- `pageStack` 自己记页面栈：`shouldOverrideUrlLoading` 里入栈；A→B→A（`!hasGesture()` 且栈里倒数第二条就是目标）
  时把看不见的 B 删掉；`doUpdateVisitedHistory` 把栈顶对齐到重定向后的真实落地地址。
- 返回三条确定性规则：①同一地址 3s 内再按一次 → 直接 `finish()`（单页应用 / 页面自己跳回来）；
  ②栈里还有上一页 → `loadUrl(上一页)` 并把「刚离开的地址」记下来，2s 内非手势跳回就拦掉；
  ③栈底 → `finish()`，回到「工作」。
- 日志 `Log.i("OaBack", …)` 只打去查询串的地址（OA 的 url 常带 signtext/token，不许进 logcat）。

实测（模拟器 + `adb reverse` 到本地站点）：`/start → /pay → /result(302 回 /pay)` 后，
第一次返回回到 `/start`，第二次返回退出页面；`screenrecord` 帧序列也确认没有卡死。

> 验证限制：模拟器无外网，`缴电费` 真实地址打不开，只能用等价拓扑的本地站点验证；工作页数据同理，
> `系统应用` 的过滤与线上 IM 幽灵消息只能靠代码保证。

---

## 16. 第十一轮反馈（2026-10-26）：顶栏动画统一、转场去圆角、通讯录方向、工作页返回与课表空白

用户原话（分两次给）：

> 「在动画代码的基础上修正一下，取消过渡动画中的圆角效果，顶栏的动画代码也要统一，参考「我的」页面中进入和
> 退出二级菜单时顶栏文字与顶栏本身的动画效果，现在 tab 之间切换时顶栏的动画效果不自然，所有顶栏相关动画也
> 统一成一份代码，参考我的页面中的动画效果。将统一后的代码记住，以后任何页面任何场景，无论是新添加的还是
> 以前的场景，都要复用这一段代码，以便未来统一管理。」
> 「第一，工作页顶栏缩小动画缺失…将这些动画全部统一，所有相关场景重复调用这段代码。第二，部分工作功能点击
> 返回后无法退出页面，一直在原地卡着，点返回按钮也无效，比如缴电费页面反复返回无效，修复所有工作功能中的类似
> 问题。第三，又出现了从某个 tab 页返回后课表页显示一片空白的问题，彻查这个问题原因。」
> 「另外，通讯录中返回动画没有和其他返回动画统一，运动方向反了，修正问题。」

### 16.1 顶栏动画：全应用只剩一份（唯一入口）

`ui/shell/ShellBars.kt`：

```kotlin
object BarAnimation {                       // 唯一规范
    const val DURATION_MS = NavMotion.DURATION_MS   // 与页面转场同拍
    val Easing = NavMotion.Easing
}
```

`LanhangTopBar` 内部只有一个进度 `morph: Animatable`，`LaunchedEffect(target) { animateTo(tween(BarAnimation…)) }`，
`collapse = maxOf(morph.value, ShellScroll.progress)`，**高度 96↔56dp、字号 32↔18sp、副标题透明度、左侧按键
透明度、标题横向居中量全部由这一个数插值**；标题文字用同一时长/曲线交叉淡化。

- **`enum class BarMotion { INSTANT, MORPH, FADE }` 与 `ShellState.barMotion` 已删除**，`showToolbarMain(title)` /
  `showToolbarSub(title, onBack, …, onHome)` **不再接受任何动效参数**。任何新页面/新场景只要调这两个函数，
  顶栏就自动是这套动画 —— 这就是「以后所有场景复用同一段代码」的落地方式。
- 「瞬时到位」不再需要枚举：`Animatable` 的初值 = 目标值，首帧天然不播动画。
- `MainActivity.applyTitle(tab)`（切 Tab）、`switchTab` 里的同 Tab 早退分支、`MineFragment.syncToolbar()`、
  `MomentsFragment.syncToolbar()` 都只改 `shell.title/subtitle/backAction`，没有一行自己的顶栏动画。
- **轮次反转，别再翻回去**：第九轮（§14.1）用户要求「切 Tab 顶栏不要伸缩」（当时是 INSTANT 硬切）；
  第十一轮用户说「现在 tab 之间切换时顶栏的动画效果不自然」—— 硬切就是那个「不自然」。
  现在切 Tab 与进/出二级页**同一条 tween**（标题 300ms 交叉淡化），这是最新口径。

### 16.2 转场取消圆角

`ui/miuix/NavPush.kt` 的 `MiuiNavStack` 里**没有** `clip = RoundedCornerShape(...)` 了（`CORNER_DP` 一并删除）。
前景页始终整幅方形滑入/滑出。屏幕圆角是系统的事，页面不许自己裁——用户明确要求取消。

### 16.3 转场方向：要么显式 `forward`，要么容器自己比深度（通讯录方向反了的根治）

`MiuiNavStack` 现在签名是
`MiuiNavStack(key, forward: Boolean? = null, modifier, pageBackground, depthOf: (Any) -> Int = { 1 }, content)`：

- `forward != null` → 用调用方给的方向（「我的」= `uiState.forward`；工作圈 = `pageForward`）。
- `forward == null` → 容器在 key 变化的那一刻算 `depthOf(key) > depthOf(shown)`，并把这个方向**锁进一次转场**
  （存进 `dir` 状态，动画全程不变）。

**踩过的坑（通讯录）**：旧写法是调用侧
`var lastDrillSize by remember { mutableIntStateOf(path.size) }; SideEffect { lastDrillSize = path.size }`
再传 `forward = path.size >= lastDrillSize`。只要状态在一次转场中间被重组合一次（异步数据、顶栏标题回来都会），
`SideEffect` 就把 `lastDrillSize` 提前写成新值，方向翻成「推入」——用户看到的正是「通讯录返回动画方向反了」。
所以：**方向不允许依赖调用侧的可变状态**，要么显式传，要么交给 `depthOf`。

### 16.4 Tab 与页面同拍

`MainActivity.TAB_SWAP_MS` 从写死的 220 改成 `= NavMotion.DURATION_MS.toLong()`（`val`，不是 `const`）。
Tab 横移、课表翻周/切视图的 `slideSwap`、顶栏、二级页转场现在都是 300ms 同一条曲线。
改速度只改 `NavMotion.DURATION_MS` 一处。

### 16.5 顶栏收起的「上报」必须传对本页 Tab

列表页统一 `ReportListScroll(listState, 本页 Tab)`。**工作页曾经误传 `ShellState.TAB_MINE`**，
进度写进了「我的」的账本，前台顶栏读 `TAB_WORK` 那一格永远是 0 → 工作页怎么滑顶栏都不收
（用户：「工作页顶栏缩小动画缺失」）。已改回 `TAB_WORK`。
新增列表页时，`tab` 参数必须是**本页所属的底栏 Tab**，写错不会有任何报错，只是「动画不见了」。

### 16.6 工作功能（OA WebView）返回键必须退得出去

`ui/OaWebViewActivity.kt`。旧逻辑：`shouldOverrideUrlLoading` 对**每次跳转**都 `loadUrl(url, headers)`
（为了给每个请求带 `Token/Version/DeviceID` 伪装头）。问题在于 `goBack()` 也会走这里，于是「回退」被当成
一次新跳转重新 load，历史里又追加一条 → `canGoBack()` 永远 true 而页面看着没动，
**缴电费这类页面点多少次返回都停在原地**。现在：

1. `restoringHistory` 标记：回退期间 `shouldOverrideUrlLoading` 返回 `false`（交给 WebView 走自己的历史）；
   `onPageStarted` 里清标记。
2. 兜底：`goBack()` 后 600ms 检查 `copyBackForwardList().currentIndex` 有没有真的变小，没变小就 `finish()`。
   ——所以「退不出去」在这页不可能再出现。
3. 同一 URL 的重复跳转（hash 路由/重定向）返回 `true` 不重载，避免历史里堆重复条目。
4. 非 `http(s)`（tel:、mailto:、唤起别的 App）交系统 `ACTION_VIEW`。

工作页所有功能都从 `WorkPage` 的 `OaWebViewActivity.open(...)` 进入，改这一处即覆盖全部工作功能。

### 16.7 课表「切回一片空白」：原因与三层防线

排查结论（`adb logcat -s ScheduleUi` 可复现）：

- 课表内容层有两个（`contentGrid` 网格 / `contentWeek` 周视图），**空态提示 `tvEmpty` 只住在周视图层里**。
  只要出现「当前模式是网格 / 但数据没加载出来（或视图被复用后可见层与 `showingGrid` 不一致）」，
  屏幕上就是标题栏正常 + 下方一片空白，而且**连一句提示都看不到**。
  冷启动日志实证：`renderGrid: 无数据 week=1 scheduleData=false`（配置在渲染之后才读缓存）。
- 老代码在数据为空时 `?: return`（先 `removeAllViews()` 再 return）——把层清空后静默退出，
  这正是「一片空白」的直接来源。

现在三道防线（改动都在 `schedule/ScheduleFragment.kt`）：

1. `renderGrid()` / `renderWeekView()` 数据为空时**往当前可见层里画提示**（`schedule_no_data_hint`：课表数据还没加载出来 / 下拉可以重新拉取），
   不再静默 return；`refresh()` 失败也改成 `renderAll()`（不再点亮那个住在隐藏层里的 `tvEmpty`）。
2. `onTabSelected()` 先按 `showingGrid` **重新对齐两层可见性**（+ `headerState.showingGrid = showingGrid`），再 `renderAll()`；
   `MainActivity.switchTab` 的「同 Tab 再点」分支也给课表补了这次调用。
3. 每次切回本页渲染后 `post { ensureContentNotEmpty() }` 自检：内容层为空就再画一次，仍为空则亮提示 + 收掉 loading。
4. 附带修掉冷启动闪烁：`onViewCreated` 改成**先 `loadCacheOrRefresh()` 再 `applyThemeStyle()`**（原来顺序相反，首帧必然无数据）。

### 16.8 本轮怎么验的（照做）

1. 临时把 `NavMotion.DURATION_MS` 改 **2000** → 构建安装 → `screenrecord --time-limit 9`
   → `ffmpeg -vf fps=4,scale=330:-1,tile=6x6` 拼图逐帧看。
2. 实测结论（截图 `artifacts/screenshots/v42-2x`、`v42-3x`）：
   - 我的 → 设置推入：二级页方形整幅滑入、**没有圆角**、一级页左移压暗 ✓
   - 设置 → 我的返回：子页向右退出、一级页从 -24% 归位、顶栏 56→96dp 同步展开 ✓
   - 通讯录下钻/返回：方向与二级页一致（进入从右、返回向右退出）✓
   - 切 Tab：顶栏标题与内容同拍交叉淡化（拼图里能看到「课表/我的」叠影），不再硬切 ✓
3. 验完**必须改回 300** 再出包（本轮就是这样做最终 APK 的）。

## 15. 第十轮反馈（2026-10-26）：转场动画全部重写，收成一份代码

用户原话：「把应用内所有涉及过渡动画的部分全部重写，类似的场景归为一段代码，动画设计要求参照 miuix skill，
我给你提供一个示例：二级菜单进入时：二级页面从右侧向左无透明度淡入滑入，主界面向左移动 24% 并 18% 压暗，
二级页面仅在动画过程中裁切屏幕大小圆角，二级菜单内的所有元素都要一起移动，而非各自有不同的方向」。

### 15.1 现在只有一处定义（不许再手写）

`app/src/main/java/com/lanhang/miuix/ui/miuix/NavPush.kt`：

- `object NavMotion`：`DURATION_MS = 300`、`Easing = FastOutSlowInEasing`、`PARENT_TRAVEL = 0.24f`、
  `PARENT_DIM = 0.18f`。**要改手感只改这里**。（`CORNER_DP` 已于第十一轮删除：转场不做圆角。）
- `@Composable fun MiuiNavStack(key: Any, forward: Boolean, pageBackground: Color, content: @Composable (Any) -> Unit)`：
  页面栈的**唯一**转场容器。以前同一个「二级页推入」有三套实现（我的/通讯录用 `AnimatedContent + slide`、
  工作圈发表用 600ms 临界阻尼弹簧 + 25% 视差淡入、Tab 用 View 级 translation），几何/时长/曲线各不相同，
  同一个动作在不同页面观感不一样，还经常「一半在动一半不动」。现在三处页面栈全部换成它。

### 15.2 几何（推入与返回是同一套公式的镜像）

`p` 恒为 0→1：

| 元素 | 推入（forward=true） | 返回（forward=false） |
|---|---|---|
| 前景页（上面那张纸） | `translationX = (1-p)·W`（+1 屏宽 → 0） | `translationX = p·W`（0 → +1 屏宽） |
| 背景页（一级页） | `translationX = -0.24·p·W`（0 → -24%） | `translationX = -0.24·(1-p)·W`（-24% → 0） |
| 背景页压暗 | `alpha = 0.18·p` | `alpha = 0.18·(1-p)` |
| 前景页圆角 | **无**（第十一轮删除；屏幕圆角交系统，页面不许自己裁） | 同 |

**踩过的坑（务必不要「优化」回去）**：最早把返回也写成 `-0.24·(1-f)·W`（用一个 `f = 1-p` 反算两端），
结果返回动画结束时父页停在 `-24%` 回不来，用户看到的就是「二级菜单退出时内容区域依旧向左动」。
推入/返回**必须**写成两个显式分支。

### 15.3 调用侧约定

- 新页面栈：`MiuiNavStack(key = 当前页, forward = 这次是推入还是返回) { key -> when (key) { … } }`。
  不要再写 `AnimatedContent` + `slideInHorizontally` / `miuixPushTransform`
  （`miuixPushTransform` 已删除）。
- **方向必须显式传，或交给容器比较深度**：`MineFragment` 用 `uiState.forward`；工作圈用 `pageForward`；
  通讯录只传 `depthOf = { (it as List<*>).size }`，由 `MiuiNavStack` 记住上一次真正播过的 key 现算
  （**禁止**再在调用侧用 `SideEffect` 维护「上一次层数」那种可变状态，见 §16.3）。
- 每个页面自己铺**不透明底色**（两页重叠时不能透出下面那一页）；**不要**给页面加静态圆角 `clip`，
  圆角只属于动画，由 `MiuiNavStack` 负责。
- 顶栏：**已无档位可选**，全部场景共用 `ui/shell/ShellBars.kt` 的 `object BarAnimation`
  + `LanhangTopBar` 的单一进度（见 §16.1）。`BarMotion` 枚举已删除。
- Tab 之间是**平级分页**语义（`runSwap`：纯平移、无压暗无视差），时长 `TAB_SWAP_MS`
  = `NavMotion.DURATION_MS`（见 §16.4），不要再往上套 24%/18%。

### 15.4 怎么验（慢速法，以后照做）

临时把 `NavMotion.DURATION_MS` 改成 `2000` → 构建安装 → `screenrecord` 录一次推入/返回 →
`ffmpeg -vf fps=5,scale=220:-1,tile=6x5` 拼图逐帧看几何 → 确认后改回 `300` 再出包。
300ms 的动画在 30fps 录屏里只有 9 帧，拼图看不出方向对错（本轮就是靠慢速法才抓到父页偏移的）。

### 15.5 本轮已知问题（与动画无关，待排查）

- 「工作圈」列表和「消息」页此刻拉不到数据：「消息」停在「正在加载消息…」，工作圈整页空白
  （`items` 为空且 `emptyText` 为空串时就是这样）。通讯录/课表/成绩正常 → 是动态（moments）后端
  或 moments 会话令牌的问题，不是本轮 Compose 改动。下次开工先确认这里，必要时重登。

## 14. 第九轮反馈（2026-10-26）：工作页图标与名称 / 顶栏只收放不横移 / 页面记忆滚动位置

用户的五条（原文）：

1. 「工具一页不要有名称的省略（如...）显示不全考虑换行，并且工具一页图标不要重复，重绘图标。」
2. 「还是会偶发出现我所说的动画问题，顶栏向右内容向左，可以多次测试找动画问题，该问题偶发出现。」
3. 「在页面底部进入二级菜单后返回，一级菜单会回到页面顶部。比如我的页面中进入关于页面，再返回，会直接回到页面顶部，以此类推修复类似的所有问题。」
4. 「顶栏的文字缩小与顶栏本身阴影收缩不同步，文字缩小与顶栏区域的收缩应同步进行。」
5. 「tab页间切换时可以不加入顶栏区域的伸缩效果，直接切换即可。」

### 14.1 硬规则：顶栏不许有横向位移

「顶栏向右、内容向左」的根因不是曲线，是**两条互不相关状态写入**：顶栏以前读
`ShellState.slideDir/slideFast/slideInstant`，内容侧读自己的 `forward`（MineState.forward /
contacts 路径长度比较）。推入动画没走完就按返回、或异步标题回来触发第二次 `syncToolbar`，
两者就会各按各的方向动，于是偶尔反着来 —— 而且**只在抢跑时出现**，所以「偶发」。

现在的规则：

- 顶栏**只做收放**（高度 + 字号 + 副标题透明度 + 左侧按键透明度 + 标题居中位移），**不做任何横向平移**。
- 「这次怎么动」收成一个枚举 `BarMotion { INSTANT, MORPH, FADE }`（`ui/shell/ShellState.kt`）：
  `INSTANT` = 切 Tab / 从二级页返回（一步到位，不伸缩不滑）；`MORPH` = 推入二级页（播放收放）；
  `FADE` = 同一页里换标题（只淡切文字）。
- 方向对不对**只由内容侧那一个** `miuixPushTransform(forward)` 决定，别再给顶栏塞方向参数。
- `INSTANT` 下不要读 `Animatable.value`：`snapTo` 要等 `LaunchedEffect` 跑，那一帧会按上一次的旧进度画一版
  （肉眼是「抽一下」）。`LanhangTopBar` 里 `val base = if (motion == INSTANT) target else morph.value`。

### 14.2 硬规则：文字与顶栏必须同一个进度驱动

以前大标题 / 紧凑条是**两套组件**（`CollapsingTitleBar` ↔ Miuix `SmallTopAppBar`），换档是离散的，
只有 `animateContentSize` 在补高度，字号却是跳的 → 用户看到「文字缩小和顶栏收缩不同步」。
现在 `ShellBars.kt` 只有一个自绘 `Box`：`height = lerp(96dp, 56dp, collapse)`、
`fontSize = lerp(32sp, 18sp, collapse)`，`collapse = max(进度, 列表滚动)`。要动就一起改这两个 lerp。

### 14.3 页面栈里每页一份滚动状态（`ui/miuix/PageListStates.kt`）

`AnimatedContent(targetState = page)` 只组合当前那一页，离开时整棵子树销毁，
页面里 `rememberLazyListState()` 跟着没了 → 进过二级页再返回，一级页跳回顶部（本轮第 3 条）。

- 所有「我的」栈里的列表页把 `listState` 当参数收（`GradesScreen` / `IdCardScreen` /
  `MessagesScreen` / `ImConversationsScreen` / `ImChatScreen` / `UserProfilePage` → `MomentsScreen`），
  由 `MineScreen` 用 `pageLists.of(MinePage.X)` 提供；`LazyColumn` 里**不要再写**
  `LaunchedEffect(page) { scrollToItem(0) }`（那正是跳到顶部的另一半原因）。
- 数据**异步重载**的页面（通讯录下钻）还要额外存档：列表会先变空，`LazyListState` 会被夹到别的下标。
  用 `PageListStates.savePosition(key, state)`（`DisposableEffect { onDispose { … } }` 里存）
  + `restorePosition(key, state)`（数据回来后再补），见 `ContactsScreen.DeptContent`。

### 14.4 工作页（办事大厅）图标不许重复

应用名与图标都不是本地资产，全靠规则推：

- 名称：`maxLines = 3`、`fontSize = 10.5.sp`、`lineHeight = 13.sp`、固定高 `48.dp`。
  高度别按 `3 × lineHeight` 算：汉字行盒实测约 16dp/行，按 39~40dp 给会被裁成两行 + 「…」（本轮第一个坑）。
- 图标：`GLYPH_POOL`（id → `ImageVector`，约 90 个）→ `WorkApp.glyphId()` 语义关键词 →
  `GLYPH_ALTERNATIVES`（同义替代，比如「报修」可以是 report/tasks/convertFile）→
  `GENERIC_DOC_IDS`（文档类通用兜底）→ 整池剩的。
  `assignGlyphIds()` 用 `used` 集合**贪心保证整页不重复**；`ImageVector` 是懒加载 getter，
  不能拿来当集合元素去重，必须用字符串 id。

## 13. 第八轮反馈（2026-10-26）：转场时长 / 返回方向 / 返回时顶栏 / 成绩统计

> 本节放在文件最前面：本文件已经超过工作区指令预算（65 KB），加载时只取前 ~65 KB，
> 写在文末的新章节读不到。新增「踩坑 + 硬规则」请往这里加，别往文档尾部追加。

### 13.1 二级页转场禁止再用弹簧，只用有限时长的 tween

用户实机反馈「所有设置内二级菜单的动画有概率全部异常，内容页卡在屏幕中不退出」
「通讯录上下级目录在屏幕上叠加」。根因是 `ui/miuix/NavPush.kt` 里用了
`spring(dampingRatio = 1f, stiffness = 146f)` 做整屏位移：要 **700~900ms** 才停，
中途两页并排挂在屏幕上（用户截图就是一屏两页、各占一半），被打断时还会停在半路。
现在统一 `NAV_DURATION_MS = 280` + `FastOutSlowInEasing`。**要改手感只改这个常量，不要换回 spring。**
`ui/shell/ShellBars.kt` 的顶栏推入也已从弹簧改成同一个 tween，两处必须同值，否则顶栏和内容两条动作不同步。

### 13.2 返回时的几何：所有东西朝右

`miuixPushTransform(false)` = 子页 `0 → +width`（整幅向右退出）+ 上级页 `-width/4 → 0`（也向右回位），
两页都不透明、同一条曲线；**不要再加 fade**（两页不透明，淡出只会闪底色）。
另外要带 `using SizeTransform(clip = false)`：默认的尺寸动画对全屏页没有意义，只会按容器把滑出去的页面裁掉一截，
看起来像「卡住」。`MineScreen`（我的 / 设置 / 通讯录…）和 `ContactsScreen`（通讯录下钻）都走这一个函数，
改这一处就同时生效，别在页面里另写一套 `transitionSpec`。

### 13.3 从二级页返回一级页：顶栏零动画、零高度伸缩

用户明确要求（通讯录 → 我的主页）：顶栏不要横移、也不要做 56dp ↔ 96dp 的高度过渡（「抽一下」）。
实现：`ShellState.slideInstant`（新字段）+ `showToolbarMain/showToolbarSub(..., instant = ...)`；
`instant = true` 时 `LanhangTopBar` 用 `EnterTransition.None togetherWith ExitTransition.None`，
并且**不给外层 Box 挂 `animateContentSize`**（`barModifier` 分支）。`MineFragment` 在
`toolbarDir < 0`（goBack / goRoot）时传 `instant = true`、`slideDir = 0`。
`showToolbarMain` 的 `slideDir = ±1` 同时也是「切 Tab」的方向（`applyTitle(tab, ±1)`），
**所以不能靠 slideDir 判 pop，必须走 instant 参数**。

### 13.4 成绩页统计卡必须常驻

用户反馈「成绩页要展示加权平均分 / 总学分 / 课程数，Miuix 化前的版本有」。
原来 `GradeStatsCard` 写在 `LazyColumn` 的「列表非空」分支里，当前学期（2026-2027-1）还没出成绩 →
整块消失。现在改成「统计卡永远在第一项」，没成绩时加权平均显示 `--`、学分/课程数 0
（口径和改版前 `GradesFragment.renderStats()` 一致），空态卡（`GradeEmptyCard`）排在它下面，
多学期时再补一行提示「当前学期还没有出成绩，可用上方的「学期」查看其他学期」（`grades_empty_hint`）。

### 13.5 本轮验证方法（可复用）

- 录屏看转场：`adb shell screenrecord --time-limit 16 --bit-rate 8000000 --size 720x1280 /sdcard/x.mp4`
  用 `Start-Job` 跑（pwsh 里 `... | Out-Null &` 会报 `AmpersandNotAllowed`），
  再用 ffmpeg `fps=10,crop=720:520:0:0,tile=5x6` 抽成联系表，数「中段帧」有几张：
  280ms 在 10fps 下只该有 2~3 张，700ms+ 会有 7 张以上（就是用户说的「叠加」）。
- 通讯录下钻坐标（1220×2656）：`教职工 (297, 627)` → `教学机构 (320, 1306)`；
  返回一路 `KEYCODE_BACK`，注意**在「我的」根页再按一次 BACK 会退出应用**（录屏里会看到回桌面的缩放动画，别误判成 bug）。
- `adb logcat -b crash -d` 必须为空；`Skipped N frames` 允许首屏加载偶尔出现一次（本轮仅启动+首次拉组织架构时 1 次 58 帧，模拟器+录屏叠加所致）。

---

## 0. 关于 `AGENTS1.MD`（2026-10-25）

同目录的 `AGENTS1.MD`（大小写不敏感，`AGENTS1.md` 是同一份）是 **Miuix 视觉改版之前**的开发文档快照，
本次已逐行比对并入本文档：**凡 10 字以上的行，`AGENTS1.MD` 里没有一条是本文档缺失的**，即内容已全部吸收。

- 处理原则：`AGENTS1.MD` **只作历史留档，不再维护**；与本文档冲突时**一律以本文档为准**
  （典型冲突：`AGENTS1.MD` 里 `ui/navigation/LiquidGlassBottomBar.kt`、`moments/MomentAdapter.kt`、
  `io.github.kyant0:backdrop` 依赖、「禁止 Compose」的红线都已随 Miuix 改版作废）。
- 它比本文档多出来的只有时效性内容（早期 `E:\dev\...` 本机路径、`compileSdk 36`、旧文件树），已被下面第 3/4 节替换。

---

## 1. 主要数据来源（API 端点）

### 1.1 主 API：`https://nginx-1.app.nchu.edu.cn/api`
官方 App 自身的 REST 接口，常量取自反编译 `cn.edu.nchu.nahang.ui.CustomHttp:13` 与 `ui.utils.HttpUtils:35`。

| 端点 | 用途 | 说明 |
|---|---|---|
| `POST /user/security_login` | 登录 | body：`username`、`password`(RSA 加密)、`agent{platform,device_id}`、`status`、`device_info{...}`；返回 LoginInfo（token、cookie 随 OkHttp 会话保存） |
| `GET /configuration/all` | 全局配置 | 下发 `password_public_key_rsa`（登录密码公钥）等，见 `net/Security.kt` |
| `GET /apps/getLdapToken` | LDAP 令牌 | **必须带登录 Cookie**；返回 `{"code":10000,"result":"字符串"}`（不是嵌套对象！）→ 用作课表握手的 `Token` 头 |
| `GET /apps/getAppToken/{appId}` | OA 应用令牌 | 同样返回 `{"code":10000,"result":"字符串"}` |
| `POST /apps/all` | OA 应用列表 | body `{"state":[0,1]}`；本项目仅保留接口，UI 已删除应用列表 |
| `GET /user/refreshUserByDel/{userId}` | 登录态探测 | 登录成功后调用 |

成功码：一般 `code=10000` 且外层 `{"code":..,"msg":..,"result":..}`；token 类接口用 `RawTokenResponse` 解析（见 `net/Models.kt`）。

### 1.2 课表 miniapp：`http://miniapp.app.nchu.edu.cn`
课表不是主 API，是独立「小程序」站点，须先经网关握手换会话：

1. **握手**：`GET http://go.nchu.edu.cn/?id=78&state=/schedule`，带请求头：
   - `Token` = ldapToken（步骤 1.1）
   - `Version` = `1.0.14`
   - `DeviceID` = ANDROID_ID
   - `User-Agent` = 完整 UA 指纹（见下）
   服务端 302 → `miniapp.app.nchu.edu.cn/api/auth?uid=...&signtext=...&redir=/schedule`
   （**signtext 由服务端生成，客户端不做签名**）→ 跟随后获得 miniapp 会话 Cookie（存入 `ApiClient` CookieJar）。
2. **取数**：`GET /api/common?method=GetSchedule&week=N`，带同一 UA。
   成功 `{"code":0,"msg":"ok","data":{...}}`（**成功码是 0，不是 10000**）。
   **关键坑（2026-10-22 实测）**：`GetSchedule` 返回的是**该周展开的课程集合**，不是全量课表——不带 `week` 参数=只返回当前周该上的课（当周不开的课根本不在里面，本地 `isWeekValid` 过滤变不出来）。官方 chunk_698.js 每次切周都重新请求 `GetSchedule&week=N`。必须按周请求、按周缓存。
   **会话坑**：miniapp 会话失效时服务端仍返 `200` 但 **body 为空**；ldapToken（JWT）约 2 小时过期，握手会返 `Error.aspx?code=4000 客户端身份鉴权失败`。`MiniappApi.fetchCommon` 已实现：空 body → `clearMiniappCookies()`（**只清 miniapp 域，勿用 clearCookies() 全清会删掉 RCESESSIONID**）→ `handshake`（内部先 `isJwtFresh` 判断并用 RCE 会话 `getLdapToken` 刷新）→ 重试一次。

`GetSchedule` 数据结构（models 见 `schedule/ScheduleModels.kt`）：

```json
{"code":0,"data":{
  "xnxqId":"2026-2027-1",
  "week":{"当前周次":"8","起始周次":"1","结束周次":"20"},
  "schedule":[{"星期":"星期一","节次":[{"节次名":"05","课程":[
    {"课程名":"...","教师":"...","教室":"...","周次":"6-15","单双周":"0"}]}]}]
}}
```
- `周次`：如 `"6-15"`、`"1-16,18"`；`单双周`：`0`=每周、`1`=单周、`2`=双周
- 一天最多 11 节；`节次名` 是两位字符串（"01"~"11"）

#### 成绩（同一 miniapp，state=/score）
- 入口：OA 应用列表（`POST /api/apps/all`）下发 `http://go.nchu.edu.cn/?id=78&state=/score`（与课表同 id=78）
- **会话复用**：与课表同一 miniapp 会话（同一 `_uuid` Cookie），无需二次握手（已实测）；页面 JS chunk 见 miniapp `/public/js/727.*.js`
- `GET /api/common?method=GetXnxqList` → `{"code":0,"data":[{"学期ID","学期名称","当前学期":"1"}...]}`
- `GET /api/common?method=GetStudentScore&xnxqId=2025-2026-1` → `{"code":0,"data":[{"课程名称","学分","总成绩","课程性质","课程属性","考试方式","总学时","课程号","学年学期ID"}...]}`
- models 见 `grades/GradeModels.kt`；当前学期可能为空数组（期中无成绩属正常）

**UA 指纹**（`MiniappApi.UA`，抄自官方 `RceWebViewActivity.java:229` 的拼接规则）：
```
Mozilla/5.0 (Linux; Android 16; 23116PN5BC Build/BP2A.250605.031.A3) AppleWebKit/537.36
(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36
NchuApp/1.0.14 NetType/WIFI MicroMessenger/8.0.47.2560
```

### 1.3 其他站点（WebView，仅 OaWebViewActivity）
- `https://form-design-m.paas.nchu.edu.cn/`（表单）
- `go.nchu.edu.cn/?id=60/63/79/83` 等（官方各功能入口）
- `?id=78&state=/schedule`（课表入口）——该地址**未在官方代码硬编码**，由 OA 应用数据动态下发 + 抓包探测确认。

### 1.4 会话与 Cookie
- 主 API 会话：登录下发 `RCESESSIONID`（**path=/api**），持久化在 SP `lanhang_cookies`（重启不丢）；miniapp `_uuid` 仅内存。
- 融云圈鉴权取 cookie 值须用 **`https://nginx-1.app.nchu.edu.cn/api/` 探测 URL 匹配**（用根路径会漏掉 path=/api 的 RCESESSIONID），取 `=` 后的值。
- 登录态持久化在 `SharedPreferences lanhang_session`（`session/Session.kt`），**仅存本机，不上传**。

### 1.5 工作圈（融云圈，`https://nginx-1.app.nchu.edu.cn/moments/api`）
- `POST /user/auth` body **`{"cookie":"<RCESESSIONID值>","user_id":"<staff.id>"}`**（键名是 `user_id`！）→ `{"code":20000,"result":{"authorization":...}}`；成功码 **20000**，`20003`=auth过期自动重登重试
- `GET /timeline?from_feed_id=&size=10` → `{feed_ids:[...]}`；`POST /feed/batch?with_comments=true&comment_size=50&with_likes=true&like_size=50` body **`{"ids":[...]}`** 拿动态正文
- `POST /like {"feed_id"}`、`DELETE /like/{id}`、`POST /comment {"feed_id","type":0,"content","reply_to":""}`；请求带 `authorization` 头
- 用户姓名头像：主 API `POST /staffs/batch` body `{"ids":[...]}` → `code:10000`，`result.data[]{id,name,portrait_url,depart_name,duty_name}`（`getUserId()` 返回的是 `id`）
- 登录模型坑：LoginInfo JSON 字段是 **`staff`**（`@SerializedName`），staff 里是 **`id`** = 融云 userId
- 请求/响应调试 dump：`filesDir/moments_last.json`（含 req/resp）

---

## 2. 反编译获取的内容

### 2.1 位置与工具
- 反编译产物：`decompiled/sources/`（jadx 导出的官方 APK Java 源码；`decompiled/resources/` 未保留资源导出）。
- 工具：`tools/jadx/`（jadx **1.5.3**，`bin/jadx.bat`，另有 `tools/jadx.zip`）。
- 重新反编译示例：`tools\jadx\bin\jadx.bat -d decompiled 官方APK.apk`（官方 APK 不入库）。

### 2.2 关键逆向结论（反编译依据 → 本项目实现）

| 反编译证据（file:line） | 结论 | 本项目实现 |
|---|---|---|
| `cn/rongcloud/rce/lib/AuthTask.java:55` `USER_SECURITY_LOGIN = "/user/security_login"` | 登录走融云 RCE SDK 的 security_login | `RceApi.login()` |
| `AuthTask.java:301,320` `RSAUtils.encrypt(password)`；有公钥时走 security_login 否则 `/user/login` | 密码 RSA 加密 | `Security.encryptPassword()`（`RSA/None/PKCS1Padding`，公钥来自 `/configuration/all`） |
| `cn/rongcloud/rce/lib/config/FeatureConfigManager.java:23` `CONFIG_SERVER_URL = "/configuration/all"` | 配置下发端点 | `RceApi.configurationAll()` |
| `cn/edu/nchu/nahang/ui/CustomHttp.java:13`、`ui/utils/HttpUtils.java:35` `baseUrl = "https://nginx-1.app.nchu.edu.cn"` | 主 API 基址 | `ApiClient.BASE_URL` |
| `cn/edu/nchu/nahang/ui/oa/OAFragment.java:246` `get("/api/apps/getLdapToken", ...TokenBean)` | ldapToken 取法（需 Cookie） | `RceApi.getLdapToken()` + `executeTokenString()` |
| `OAFragment.java:192-201,250` header 放 `Token`/`Version`/`DeviceID`/`ssid`，结果存 SP | 网关请求头格式 | `MiniappApi.handshake()`（未发 `ssid`，实测非必需） |
| `OAFragment.java:269+` 定时刷新 Token；`:301` OkHttp GET 带 UA 头，`:314` `handleRedirect(Location)` | 网关 302 手工跟随模式 | OkHttp 默认跟随 302，Cookie 进 CookieJar |
| `cn/edu/nchu/nahang/ui/oa/RceWebViewActivity.java:215-218` Token/Version/DeviceID/ssid；`:229` UA 拼接 `NchuApp/{ver} NetType/WIFI MicroMessenger/8.0.47.2560` | WebView 注入头与 UA 指纹 | `MiniappApi.UA`、握手请求头 |
| `OAFragment.java:418`、`ui/oa/OASearchAppsAdapter.java:87` `get("/api/apps/getAppToken/{id}", ...)` | 应用令牌 | `RceApi.getAppToken()` |
| `ui/MainActivity.java:841` `go.nchu.edu.cn/?id=63...`；`SettingActivity:132,138` id=79/83；`LoginActivity:181,642` id=60 | go 网关入口集合（**不含 id=78**） | 课表 id=78 为探测所得 |
| `AuthTask.java:46` `/user/login`、`:47 /user/login_refresh`、`:53 /user/reset_password` 等 | 其余账号端点备用 | 暂未使用 |
| IM/推送相关：`io.rong.imkit`、`io.rong.imlib`（BuildVar.SDK_VERSION=2.9.19）、`cn.rongcloud.rce` | 官方 App 是融云 IM 壳，IMLib 是 2.9 代 | 只接 IMLib 2.9.24（补丁 AAR），IMKit/推送不接，界面自绘 |

反编译代码**仅供参考**，官方 App 代码受其许可约束，本项目只重写独立的 Kotlin 实现（接口协议属事实性信息）。

---

## 3. 代码结构（`app/src/main/java/com/lanhang/miuix/`）

> 2026-10-25 校准：本树按**当前源码**重列（旧版还留着已删除的 `moments/MomentAdapter.kt`、
> `ui/navigation/LiquidGlassBottomBar.kt`、`LiquidGlassFab.kt`）。增删文件时请同步这张表。

```
App.kt                     启动 init 顺序：ApiClient → MiniappApi → MomentsApi → Session → applyTheme → AppContext → ScheduleRepository → GradesRepository；另装 installCrashLogger
net/
  ApiClient.kt             OkHttp 单例、BASE_URL/API_PREFIX、跨子域 CookieJar（SP lanhang_cookies）、firstSessionCookieValue(/api/ 探测+RCESESSIONID 优先）
  RceApi.kt                主 API（login/configurationAll/getLdapToken/getAppToken/allApps/staffsBatch）
  MiniappApi.kt            握手（isJwtFresh 自动刷 ldapToken）+ GetScheduleRaw/GetXnxqListRaw/GetStudentScoreRaw（dump 到 filesDir，空 body 自动重握手重试）+ probe()
  MomentsApi.kt            融云圈鉴权 + 时间线/点赞/评论 + 发动态（dump moments_last.json）
  Models.kt                ApiResponse/RawTokenResponse/LoginInfo(staff)/StaffInfo(id)/StaffBrief/ConfigInfo
  Security.kt              RSA 公钥加密密码
session/
  Session.kt               SharedPreferences 持久化（username/name/token/ldapToken/gradesTerm/themeMode/language/...）
  AppLanguage.kt           语言模式（跟随系统/中文/英文），配合 util/LocaleHelper
schedule/                  ★ 只有这一块还是 View 自绘，未 Compose 化
  ScheduleModels.kt        GetSchedule 数据模型 + isWeekValid/isOddEvenValid
  ScheduleRepository.kt    loadCached()/loadCachedWeek(n)/refresh(week)；主缓存 schedule_cache.json + 按周缓存 schedule_w<N>.json
  ScheduleFragment.kt      课表页全部逻辑：周视图 renderWeekView / 网格 renderGrid（自绘）+ 翻周手势 + animateSwap 过渡
  ScheduleHeader.kt        页头（Compose+Miuix）：回到本周 / ‹ 第 N 周 › / 网格·周视图 TabRow
  ScheduleStyle.kt         配色令牌（courseColor 黄金角 / courseFill / onCourseTint / chipFill / bandFill / divider …）
  ScheduleBackdrop.kt      自定义课表背景（纯色/渐变/图片 + 取色分析 + 遮罩）
  ScheduleBackgroundActivity.kt  背景选择/裁剪页
  ScheduleExporter.kt      .ics/.csv/纯文本导出（collectSlots 保留周次给 CSV，collectOccurrences 按周展开给 ICS/文本）
  SchedulePreview.kt       导出/分享预览数据
  CourseItem.kt            课程块数据类（含合并后的起止节次）
  PeriodClock.kt           节次时间唯一来源（08:00-08:45 … 20:50-21:35）
work/
  WorkPage.kt              办事大厅目录 + 「常用功能」卡（数据来自 /apps/all，按本科生/研究生/校领导筛选）
  WorkFragment.kt          「工作」一级 Tab 的宿主（ComposeView + LanhangMiuixTheme）
grades/
  GradeModels.kt           XnxqListResponse/GradeSemester(id/name/current)/ScoreResponse/GradeItem
  GradesRepository.kt      学期列表 + 各学期成绩缓存（grades_semesters.json / grades_<term>.json）
  （GradesFragment.kt 已于 2026-10-26 第六轮删除：成绩改为「我的」里的二级页，直接宿主 GradesScreen）
  GradesScreen.kt          Compose+Miuix 成绩页：学期 TabRow（**标签用 xnxqId**，如 2026-2027-1）+ 统计卡 + 列表
im/
  ImRepository.kt          融云 IMLib 2.9.24（补丁 AAR）：配置→token→connect、会话列表、拉历史、收发文本/图片/视频/文件/语音/位置、收消息监听（Compose 状态）
  ImScreens.kt             私信列表 / 私信会话页（Miuix 气泡按类型渲染、输入条 + 表情面板 + 「+」附件面板 + 按住说话、连接状态栏）
  ImMedia.kt               富消息的本地支撑：选图落缓存、视频抽帧与时长、录音（MediaRecorder）、语音播放、远端文件下载后 FileProvider 打开、地图跳转、定位反查

moments/
  MomentModels.kt          MomentFeed/FeedContent/LikeEntry/CommentEntry 等
  MomentItem.kt            动态数据类（likes/comments 是 CopyOnWriteArrayList，**不是** Compose 状态）
  MomentsFragment.kt       时间线加载/翻页/点赞/评论 + 发表页切换（publishPage）
  MomentsScreen.kt         Compose+Miuix 动态流/九宫格/操作胶囊/FAB
  PublishMomentScreen.kt   Compose+Miuix 发表页（九图九宫格，Photo Picker 选图）
  MomentImageUploader.kt   发图上传：本地压缩（质量 88→40 + 0.82 倍缩尺寸，目标 ≤ maxBytes，长边 1600）→ multipart
  UserInfoCache.kt         userId→姓名头像缓存（批量 staffsBatch）
  ZoomImageView.kt         自写可缩放图（捏合/双击 1x↔2.5x/放大后拖动）
  DragDismissLayout.kt     图片预览的上滑关闭手势容器
  ImagePreviewActivity.kt  应用内图片预览（ViewPager2 + 计数 + 单击/点图片外/上滑/返回键关闭）
settings/
  SettingsFragment.kt      数据/动作宿主：主题、语言、课表导出（SAF）、诊断、退出登录、调试课表
  SettingsScreen.kt        Compose+Miuix 设置页（page=NONE/APPEARANCE/TOOLS/ABOUT 内部切页）
  Diagnostics.kt           诊断报告（会附上最新一份崩溃日志）
ui/
  LoginActivity.kt         登录 → 自动 handshake + refresh 导入课表
  MainActivity.kt          外壳：ComposeView 顶栏/底栏 + Fragment 容器（课表/成绩/工作圈/设置）+ startPagerSwap 横移 + 看门狗
  OaWebViewActivity.kt     通用带 header 的 WebView（备用）
  shell/ShellState.kt      外壳状态（tab/title/subtitle/backAction/actionText/slideDir/slideFast）
  shell/ShellBars.kt       LanhangTopBar（TopAppBar/SmallTopAppBar + AnimatedContent 横移）/ LanhangBottomBar（NavigationBar）
  miuix/LanhangMiuix.kt    LanhangMiuixTheme（MiuixTheme + navigation-event owner）
  miuix/MiuixDialog.kt     系统 Dialog 里塞 Miuix 对话框（装 view-tree owner）
  miuix/RefreshTexts.kt    PullToRefresh 的中文文案
util/LocaleHelper.kt       语言包装（createConfigurationContext + recreate）
widget/
  ScheduleWidgetProvider.kt / ScheduleWidgetSmallProvider.kt    2×2 与 4×4 小部件
  ScheduleWidgetRenderer.kt / ScheduleWidgetData.kt             绘制与数据装配
  ScheduleWidgetUpdater.kt                                      闹钟/广播/预览推送
  TodayNavCore.kt / TodayNavStore.kt                            2×2 翻天导航（selectedEpochDay/anchorEpochDay）
```

### 课表 UI 关键参数（`ScheduleFragment.renderGrid`，勿随意改）
- 网格几何（**一屏 11 节**）：`gapH=3dp, gapW=4dp, timeW=44dp, bandH=18dp, headerRowH=46dp`；
  `rowH = (availH - panelPad - headerRowH - 2*bandH - 10*gapH)/11`，夹在 32~56dp（`panelPad = dp(22)`）
- **一律画满 11 节**（`drawPeriods = 11`）：以前只画到「最后一门课所在节次」，空出来的下半屏是一片白
- 网格**不画纵向线**、横向线 `alpha = 0.5f`；`contentGrid` 面板默认**透明**（只有设了自定义背景才铺 `panelFill`）
- 休息带：`breaksAfter = mapOf(4 to "午休", 8 to "晚休")`，胶囊 `rounded(50f, divider)`；
  文案只在块区胶囊里显示，时间列只留同高占位（否则同一个词出现两次）
- 课程块：`courseFill(color)` 浅色胶囊（10dp 圆角）+ `onCourseTint(color)` 深字 + 右下 6dp 实心色点（仅 span≥2 的块）
- `mergeCourses` **不得**跨节次 4→5、8→9 合并；行定位 `yTopOfRow(p) = (p-1)*(rowH+gapH) + breaksBefore(p)*bandH`
- 节次时间只在 `PeriodClock.kt` 一处；网格里那份 `periodTimes` 仅供绘制，改时间请两边一致（最终以 PeriodClock 为准）
- 课程配色：黄金角 HSL（`hue = name.hashCode()*137.508f % 360`，同课同色）

---

## 4. 构建与调试

### 环境（本机固定路径，2026-10-25 校准）
- Gradle：`D:\数据\Deepseek Harness工作区\lanhang-miuix\toolchain\gradle-8.14.3\bin\gradle.bat`
  （**项目内 gradlew.bat 已丢失，必须绝对路径调用**；AGP 8.13 要求 Gradle ≥8.13）
- JDK17：`D:\Dev\JDK17`（跑 Gradle 用这个）；预览工程的 `run` 任务另固定 `D:\Dev\JDK27\bin\java.exe`
- Android SDK：`D:\Dev\AndroidSdk`；adb：`D:\Dev\AndroidSdk\platform-tools\adb.exe`
- `GRADLE_USER_HOME`：`lanhang-miuix\cache\gradle-home`（缓存/产物都留在项目目录内，不污染用户目录）
- AGP 8.13.2 / Kotlin 2.3.21 / **compileSdk 37**（Miuix 的 AAR 要求，配 `android.suppressUnsupportedCompileSdk=37`）/ minSdk 26 / targetSdk 35

### 常用命令
```powershell
# 构建（workdir = lanhang-miuix\src\lanhang-lite-main）
$env:GRADLE_USER_HOME="D:\数据\Deepseek Harness工作区\lanhang-miuix\cache\gradle-home"
$env:JAVA_HOME="D:\Dev\JDK17"
& "D:\数据\Deepseek Harness工作区\lanhang-miuix\toolchain\gradle-8.14.3\bin\gradle.bat" assembleDebug --console=plain --no-daemon
# 输出：app\build\outputs\apk\debug\app-debug.apk

# 安装（HyperOS 会拦直接 install，必须走 pm install -r）
& "D:\Dev\AndroidSdk\platform-tools\adb.exe" push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/lanhang.apk
& "D:\Dev\AndroidSdk\platform-tools\adb.exe" shell pm install -r /data/local/tmp/lanhang.apk

# 读取课表原始响应 / 缓存 / 会话（logcat 中文乱码，勿用 logcat 看内容）
adb shell run-as com.lanhang.miuix cat files/schedule_wcur.json
adb shell run-as com.lanhang.miuix cat files/schedule_cache.json
adb shell run-as com.lanhang.miuix cat shared_prefs/lanhang_session.xml
adb shell run-as com.lanhang.miuix cat shared_prefs/lanhang_cookies.xml   # RCESESSIONID 等
adb shell run-as com.lanhang.miuix cat files/xnxq_list.json               # 学期列表
adb shell run-as com.lanhang.miuix cat files/score_2025-2026-1.json       # 成绩
adb shell run-as com.lanhang.miuix cat files/moments_last.json            # 工作圈最近 req/resp
```

### 调试设施
- `MiniappApi.getScheduleRaw` 每次把返回体写 `filesDir/schedule_wcur.json`
- 菜单 `action_debug_schedule` 手动触发握手+拉课表并保存

---

## 5. 约定与红线

- **禁止新增第三方库，优先原生实现（2026-10-23）**：
  - **适用范围：全局**。约束对象是 `app/build.gradle.kts` 的 `dependencies` 全集，覆盖所有代码路径（课表/成绩/工作圈/设置/小部件等全部页面与模块），也覆盖今后新增的 module、source set、productFlavor 与 Kotlin/Java 源码——**不是只限某个特定模块或某个页面**。新增文件里 `import` 未在依赖中声明的外部库同样视为违规。
  - **默认做法**：能用 Android Framework 原生能力（View 体系、Canvas、RemoteViews、Handler/AlarmManager、Animation/Animator、SharedPreferences、SAF、MediaStore 等）或 AndroidX 官方 Jetpack 实现的，**一律用原生方案**，不得为省事引入第三方库。AndroidX 官方件（core-ktx、appcompat、material、recyclerview、swiperefreshlayout、constraintlayout、viewpager2、activity-ktx）、Kotlin 官方件与 kotlinx-coroutines 属平台官方组件，**不受本条限制**。
  - **唯一例外：底部导航栏已使用的库**——即 `androidx.compose.*`（compose-bom、activity-compose、ui、material3）与 `io.github.kyant0:backdrop`，用于 `ui/navigation/LiquidGlassBottomBar.kt` 的液态玻璃底栏，以及与之同款的现有组件 `moments/LiquidGlassFab.kt`。该例外**仅限这两处玻璃效果**，不得把 Compose 或 backdrop 扩散到其他页面/新功能，也不得在这两个库之上再叠加新的第三方 Compose 组件。
  - **存量依赖**：`okhttp`、`gson`、`coil` 已被现有代码使用，允许继续在**既有用途**中使用；但不得为新增需求引入同类替代库（已有 coil 就用 coil，不得再引图片库；已有 okhttp 不得再引 retrofit 等网络库），也不得借「已声明」之名扩大用途范围。
  - **需要新库时**：必须先向用户说明「原生方案为什么做不到、成本多大」，取得明确同意后才能修改 `dependencies`；**未经同意不得新增、替换或升级任何依赖**。
- **安装与测试（2026-10-23）**：构建通过后可以用 adb 安装 APK（HyperOS 按上文 push + `pm install -r`）。**agent 也要做测试**：可做构建/lint/单元测试、adb 启动检查崩溃、logcat 排错、缓存与导出数据校验等**非界面测试**；但**滑动屏幕、点击操作、截图/录屏等表面 UI 测试一律交由用户执行**，结果由用户反馈。
- **课表导出保存路径（2026-10-23）**：导出 `.ics`/`.csv` 必须走系统文件选择器（SAF `ActivityResultContracts.CreateDocument`），保存路径由用户自己选择；禁止写死固定目录、禁止静默保存。导出范围为**整学期**（`ScheduleExporter.plan` 的 `startWeek..endWeek`），CSV 带 UTF-8 BOM（Excel 中文）；实现见 `schedule/ScheduleExporter.kt`（plan/buildIcs/buildCsv/buildText）。
- **小部件预览（2026-10-23）**：选择器里的预览必须同时给 `previewImage`（位图，API ≤30 与兜底）和 `previewLayout`（可缩放布局，API 31+ 优先），见官方 `develop/ui/views/appwidgets/previews`。**预览资源里禁止用固定 dp 偏移画内容**：2×2「今日课程」原用 layer-list（top=34dp/bottom=118dp 等 inset）画色条，2×2 高度 <152dp 时 inset 溢出、色条被裁成 0 高 → picker 只剩一张纯白卡片（实测 237px 高的预览里色条完全没出现）；现改为 `drawable-nodpi/widget_schedule_today_preview.png`（1000×1000 位图，按 `ScheduleWidgetRenderer.renderToday` 的几何/配色绘制）+ weight 分配的 `layout/widget_schedule_today_preview.xml`。另外 API 35+ 在 `ScheduleWidgetUpdater.pushGeneratedPreview()` 里用 `AppWidgetManager.setWidgetPreview` 推送真实渲染结果（系统限频 ~2 次/小时，本地再限 12h，时间戳存 `Session.widgetPreviewPushedAt`）。
- **CSV 必须能被 Sleepy 导入（2026-10-23）**：Sleepy（github.com/lingion/sleepy）的 `ScheduleParser.parseCsv` 规则：列名按**包含匹配**（课程/星期/开始节数/结束节数/周次/类型/教师/教室/开始时间/结束时间）；**星期只认「周一…周日」或 1-7，写「星期一」会解析失败→跳过整行→「未能解析任何课程」**；节次必须是「开始节数+结束节数」两列（单列会被当成“节次”把多节连上压成 1 节）；一行 = 一个「星期+起止节+周次区间」，**不能按周展开**（否则导入成几十条重复课程）。故 `buildCsv` 走 `collectSlots`（保留原始 `周次`/`单双周`）而不是 `collectOccurrences`（按周展开，仅供 ICS/文本）。Sleepy 的 CSV 导入不读日期，`startDate` 默认取导入当天，需用户在确认框把「开学日期」改成第 1 周周一（导出对话框里已加提示）。
- **sleepy**（github.com/lingion/sleepy）是 GPL-3.0：只参考其设计风格，不抄代码。
- 成绩 2026-10-21 应用户要求加回（勿再以旧「不做成绩」为由删除）；OA 应用列表仍不做（工作页只读用 `/apps/all` 目录）；IM 已于 2026-10-26 接入（见下方私信条目）。
- **2×2「今日课程」翻天导航（2026-10-23 加，2026-10-24 改版 + 修 vivo 错位）**：底部左右两个按钮 **◀ ▶** 切换前后一天，**翻到别的日期后中间才多出 ⟳（回到今天）**——当天本来就在今天，不显示它（隐藏后按钮组从 80dp 收到 52dp，左侧胶囊因此能用完整文案）。语义对齐 Sleepy（github.com/lingion/sleepy 的 `TodayDateNavCore`，独立实现，未抄代码）：每个实例在 `widget_today_nav` SP 里存 `(selectedEpochDay, anchorEpochDay)`，**anchor ≠ 今天就回到今天**（隔天自动复位，靠午夜闹钟触发的 updateAll 生效）；偏移 `coerceIn(-92, +92)` 天，目标日期再钳到学期首周周一~末周周日；跨周没缓存时 `ScheduleRepository.refresh(week)` 补拉（6s 超时，失败保持当前渲染）。实例删除时 `onDeleted` 清状态。
  - **底栏（胶囊 + ◀ ⟳ ▶）必须 100% 是布局里的真实 View，一个像素都不许画进位图（2026-10-24，vivo 实测 bug）**。根因：位图是**应用进程**按自己的 `displayMetrics.density` 画的，而 `widget_schedule_week.xml` 是**桌面进程** inflate 的、dp 走桌面的 Configuration；vivo/OriginOS 会给桌面小部件套独立缩放，两边 density 一不相等，画在位图里的底板就和布局里的图标错开，**越靠左偏得越多**（真机截图：最左的 ◀ 底板上是空的、⟳ 骑在两个底板中间、▶ 才勉强对齐）。现在底板/图标/点击热区是同一个 View，缩放到哪都不会错位；位图只画标题 / 课程行 / 空态文案（这些不需要和任何 View 对齐）。
  - 几何：按钮 **24×18dp**、间距 4dp、组右边距 9dp、底栏高 22dp（按钮在其中垂直居中 → 距底 11dp）；三个 ImageView 装在 `wrap_content` 的横向 LinearLayout 里靠 `bottom|end`，**不要再用固定 `marginEnd` 定位**（中间 ⟳ 一 GONE 就错位）。整组仅 2×2 且高度 ≥ `FOOTER_MIN_HEIGHT_DP`(130dp) 时 VISIBLE；高度不够时胶囊一起隐藏（位图同样按 `FOOTER_HEIGHT_DP`(22dp) 给底栏留空，两边必须一致）。
  - **浅/深两套各占一组 View**（`widget_nav_group` / `widget_nav_group_dark`、`widget_week_pill` / `widget_week_pill_dark`），绑定时只切 `setViewVisibility` + `setTextViewText` + `setOnClickPendingIntent`。**禁止 `views.setInt(id, "setBackgroundResource"/"setTextColor", …)` 这类反射调 View 方法**（launcher 端可能静默拒绝，且插件化桌面行为不一致）。改几何时两组必须一起改。
  - 胶囊文案由 `ScheduleWidgetRenderer.footerLabel(context, week, target, compact)` 生成；宽度由外层 `layout_weight="1"` 的容器约束，放不下交给 TextView `ellipsize`。**2×2 在 vivo 实测只有 ~110~130dp 宽**（不是设计的 154dp），所以 `NARROW_WIDTH_DP=150` 以下改用无空格短文案（`widget_week_compact`）。
  - 点击走 `ACTION_PREV_DAY/NEXT_DAY/RESET_DAY` 广播（显式组件 + `EXTRA_APPWIDGET_ID`）→ `ScheduleWidgetProvider.onReceive` → `goAsync` → `ScheduleWidgetUpdater.handleNavAction`；`handleNavAction` 里对「已经在今天」的 RESET 保留**直接 return** 作兜底。
- **4×4 周视图课程名 = 竖排、最多两列、整组居中（2026-10-24，用户明确要求）**：课程名**必须竖排**（一个字一行），一列排满才折到右边第二列（左列先填，右列承接剩余），**最多 `NAME_MAX_COLS=2` 列**，整组在色块里水平 + 垂直居中。**不要改成横排折行**——用户实测横排（`高等数/学B3` 那种）会把色块左右撑满，看起来被拉宽；竖排两列只占两个字宽，两侧自然留白。
  - 字号按块自适应（`fitNameSize`）：从 `NAME_MAX_SIZE_DP=13dp` 往下找第一个满足「列数≤2 且两列总宽 ≤ 块宽（扣 `NAME_PAD_H_DP` 2.5）且每列行数×`NAME_ROW_SPACING`(1.14) ≤ 可用高」的字号，下限 `NAME_MIN_SIZE_DP=7dp`；**不要退回全局统一字号**——4×4 里并行分栏的窄块只有半列宽，取全网格最小值会把整周的字一起压到下限。
  - 行距 1.14 不是 1.0：汉字字形高约 1.0em，基线以下还有 descent(≈0.22em)，行距太紧最后一行会压到底部的教室行。绘制基线用 `groupTop + row*rowH + size*0.78`。
  - `roomReserve`（底部教室行预留）必须 ≥ `min(12dp, 块高*0.32)`：教室字号约 7dp、基线距底 5.6dp，字形顶部实际到距底 ~12dp，只留 7dp 会让最后一行课程名和教室叠在一起。
  - 排不下的字用「…」占最后一格（不是直接截掉，否则看不出被截断）。改排版后可用 `.workbuddy/tmp/preview_week4x4.py` 离线出图核对几何（该脚本逐行对齐 `CanvasRenderer`）。
- **小部件预览图必须与真实渲染一致（2026-10-23）**：`drawable-nodpi/widget_schedule_today_preview.png`（1000×1000，picker 的 previewImage）由 `.workbuddy/tmp/gen_widget_preview.py` 生成；改了 `TodayRenderer` 的几何、按钮个数、文案后必须重跑该脚本（`python gen_widget_preview.py <输出路径>`，依赖 Pillow），否则选择器里看到的和放置后不一致。149dp 宽度下底部胶囊放不下「第 4 周」，会退成 `widget_week_compact`（「第4周」）。4×4 的 `widget_schedule_preview.png` 同理，仍是旧的单列竖排手绘图。
- **2×2「今日课程」排课规则（2026-10-23，用户明确要求）**：
  - **上完即走**：今天已经下课（`PeriodClock.endMinute(end) <= 现在`）的课**不再出现在小部件里**，后面的课依次顶上来；不是「按当天课时从前到后截断」。翻到昨天/明天时不做时间过滤，只按节次顺序取前几节。
  - **最多 [MAX_TODAY_ROWS]=3 节**（`ScheduleWidgetRenderer.MAX_TODAY_ROWS`，用户说「总共能显示三节」，后面又提到「不多于四节」，按前者实现，改常量即可）。隐藏的课不画「+N」胶囊——2×2 没有多余高度，课时增加靠前面的课下课后自动补位。
  - **倒计时**：正在上的那节（`start <= now < end`）在**行右侧**显示「剩 N 分」（`widget_today_countdown`，向上取整、最少 1 分），其余行右侧显示教室。倒计时只按分钟跳，所以 `ScheduleWidgetUpdater.refreshTick()` 在**上课期间排链式 60s 单次闹钟**（`ACTION_TICK`）重绘；离下课不足 60s 就精确排在下课那一刻（那次重绘负责让这节课消失、下一节顶上来）。**没上课时排到下一节上课时刻，今天全上完/没放实例就取消闹钟**，不空转。
  - **闹钟必须用 `AlarmManager.ELAPSED_REALTIME`（非 WAKEUP）**：熄屏不唤醒设备，亮屏后补一次，因此**不需要 SCHEDULE_EXACT_ALARM 权限**；别改成 `setExact*`（会抛 SecurityException）或 `setRepeating`（会被批量延迟）。
  - **节次时间只有一个来源**：`schedule/PeriodClock.kt`（08:00-08:45 … 20:50-21:35）。小部件判断上课/下课、算倒计时都走它，禁止再硬编码第三份时间表。
  - **空态要区分**：今天本来有课但全上完了显示「今天的课已上完」（`widget_today_done`），不要复用「今天没有课程」。
  - 布局几何随 3 行改为 outerPad 9dp / header 24dp / headerGap 5dp / cardGap 4dp / footer 22dp（2×2 实测 149dp → 每行 24dp，单行排版）；改这些常量时必须同步 `drawNavButtons`、`layout/widget_schedule_week.xml` 与预览图/预览布局。
- **通讯录必须走本地缓存（2026-10-26）**：`net/ContactsRepository.kt` 把 `GET /departments/tree`（一次 300KB+、
  1500+ 节点）缓存 12 小时、`GET /departments/{id}/branches`（按 id+offset+limit）缓存 6 小时，落盘
  `filesDir/contacts/`。**不要再在页面里直接调 `RceApi.departmentTree()/departmentBranches()`**——用户实测
  弱网时一直转圈甚至超时。缓存过期只是「不再直接返回」，请求失败仍保留旧数据展示。
- **通讯录下钻时返回手势归页面自己**：`ContactsScreen` 在 `path.isNotEmpty()` 时注册 `BackHandler` 逐级退回，
  别让返回直接落到「我的」根页面（用户 2026-10-26 反馈）。
- **私信（融云 IM）已接入（2026-10-26，用户同意用融云 SDK）**：只接 **IMLib**，不接 IMKit——聊天界面是
  `im/ImScreens.kt` 里自己用 Miuix 画的。配置来自主 API `GET /configuration/all` 的
  `result.features.im`（`app_key`、`navi_urls`、`media_urls`），token 依次取 `Session.imToken` →
  `Session.token`（登录 token 本身就是融云 token，92 字符 RCE 加密串）→ `POST /user/refresh_token`。
  连接顺序必须是 **`RongIMClient.setServerInfo(navi, media)` → `RongIMClient.init(context, appKey)` →
  `RongIMClient.connect(token, callback)`**（`setServerInfo` 在 init 之前，否则走默认 `nav.cn.ronghub.com`，本机 DNS 不通）。
  - **SDK 版本只能留在 2.9 这一代**：官方客户端（蓝航 1.0.14）内置 IMLib **2.9.19**；5.21.4 / 4.2.1 连学校的
    navi 服务器一律 `RC_CONN_TOKEN_INCORRECT`（实测），2.9.24 同一份 token 立刻 `connect ok`。
  - **用的是仓库内的补丁 AAR `app/libs/im_lib-2.9.24-patched.aar`**，原因与补丁内容见 `app/libs/README.md`：
    2.9 的 `WakeLockUtils` / `PushConnectivityManager` / `RongNotificationInterface` 三处 `PendingIntent`
    flags 不合法，targetSdk 31+ 会让 `:ipc`、`io.rong.push` 进程一连上就崩。**升级/替换这个 AAR 必须重打补丁**
    （脚本 `scratch/patch-history/tmp_rong_repack2.py` + 源码 `scratch/rong/patchsrc/`）。
  - 已废弃的旧结论（勿再据此判断）：早前写的「`maven.rongcloud.io` 解析不到所以接不了」是探错了主机——
    正确仓库是 **`maven.rongcloud.cn`**（`https://maven.rongcloud.cn/repository/maven-releases/`）。
  - 私信是**真实学校系统**：联调只允许「打开会话页 → 截图 → 返回」，**绝不点「发送」**。
- 工作圈只重写协议不搬融云 SDK 代码（io.rongcloud.moment 为商业 SDK）。
- **工作圈评论整行可点（2026-10-23）**：禁止把所有评论拼进一个 TextView 用 `ClickableSpan` 做回复（旧实现只有人名能点，点正文毫无反应）。现在每条评论独占一行 `layout/item_moment_comment.xml`，动态挂到 `item_moment.xml` 的 `llComments`；整行（含行末空白）点击 = 回复该评论作者（`onReply(item, comment.user_id)`），多行复用子 View 避免重复 inflate。注意：行设了 `clickable` 会吞掉触摸流，**长按删除必须挂在同一行**（`onCommentsLongPress`），否则父容器收不到长按。
- **暗色适配（2026-10-21）**：新写 UI 的文字/卡片底/分隔线一律用 `?attr/color*`（XML）或 `getThemeColor(ctx, MaterialR.attr.color*)`（代码），**禁止硬编码黑字 hex**（如 #1D1B20/#49454F）与固定浅底（如 #FEF7FF）；课表网格与周视图已全部改主题属性；课程块彩色底（HSV/白色文字）是固定设计可保留。
- **语言切换走 LocaleHelper + recreate()，禁止用系统 per-app locale（2026-10-24 重写）**：`util/LocaleHelper.wrap(base)` 用 `createConfigurationContext` 包住 Activity/小部件的 base context，切换时 `AppLanguage.apply(mode)`（存 `Session.language` + `LocaleHelper.prime`）+ `requireActivity().recreate()`。**不要用 `AppCompatDelegate.setApplicationLocales`**——它走系统 relaunch，切换过程有约 0.4s 纯黑帧（实测 Android 15），且 `configChanges="locale|layoutDirection"` 拦截也不行（设置页文字不刷新、切 tab 假死，已回滚）。口径对照 E:\app_start 的 `util/LocaleHelper`。接入点：5 个 Activity 的 `attachBaseContext`（Login/Main/OaWebView/ImagePreview/ScheduleBackground）、`App.onCreate` prime 一次（顺带清掉旧版写进系统的 per-app locale）、小部件文案在 `ScheduleWidgetRenderer.render/renderToday/footerLabel` 与 `ScheduleWidgetUpdater` 的 contentDescription、`ScheduleWidgetData` 的兜底课名处 wrap。**新增 Activity 必须自己加 `attachBaseContext`，新增小部件/通知文案必须用 `LocaleHelper.wrap(context)`**，否则语言不跟随。
- `res/values/colors.xml` **禁止整体覆盖**（曾误删 primary/primary_dark/gray 导致构建失败）。
- 图标用官方 Material Symbols（如 `drawable/ic_calendar.xml` = Material Symbols Rounded `calendar_month` 原版路径 + `translateY=960` 转换），配 `?attr/colorPrimary` 着色。
- 课表数据只存本机（filesDir 缓存 + SP），不外传；密码仅在登录时 RSA 加密上送。
- **每次功能实现完必须安装（2026-10-23）**：任何功能（新特性/修改）实现完成后，流程固定为「构建 → adb 安装到设备（上文 push + `pm install -r`）→ 非界面测试（启动/崩溃/logcat/构建校验）→ 交付」，**不允许只构建不安装就交付**；滑动、点击、截图等表面测试由用户完成，结果由用户反馈。

---

## 6. Miuix 视觉改版（2026-10-24，用户指定）

**落盘位置（2026-10-24）**：本项目的全部文件（源码 / 构建产物 / 截图 / 脚本 / Gradle 缓存）统一放在 `D:\数据\Deepseek Harness工作区\lanhang-miuix\`，对应关系见该目录的 `README.md`；**不要再往工作区根目录或别处放任何文件**。（工作区已再次整理，项目直接位于工作区根下。）

用户要求「不够美观」，指定按 Miuix（github.com/compose-miuix-ui/miuix）重做视觉。范围是**混合方案**：外壳（顶栏/底栏）+ 成绩 + 设置 + 工作圈用 Compose+Miuix 重写，课表保留自绘网格只换皮，**数据层（net/session/schedule 仓库/导出/小部件）零改动**。

- **依赖（唯一新增，已获用户同意）**：`top.yukonga.miuix.kmp:miuix-ui / miuix-preference / miuix-icons : 0.9.1` + `io.coil-kt:coil-compose:2.6.0`（与原 coil 2.6.0 同版本）+ Compose BOM/activity-compose。0.9.1 是与项目 Kotlin 2.3.21 / Compose 1.11 对齐的版本（0.9.4 要 Kotlin 2.4.20，勿升）。**Miuix 的 AAR 要求 compileSdk 37**，故 `compileSdk = 37` + `gradle.properties` 里 `android.suppressUnsupportedCompileSdk=37`。
- **旧依赖已移除**：`io.github.kyant0:backdrop` 与 `ui/navigation/LiquidGlassBottomBar.kt`、`moments/LiquidGlassFab.kt`、`ui/LanhangTheme.kt`、`moments/MomentAdapter.kt` 一并删除（液态玻璃底栏被 Miuix `NavigationBar` 取代，`MomentItem` 已移到 `moments/MomentItem.kt`）。**AGENTS.md 第 5 节里「Compose/backdrop 例外」的旧口径随之失效**：现在允许在 `ui/`、`grades/`、`settings/`、`moments/` 的 Compose 屏幕里用 Miuix 组件。
- **结构与入口**：
  - `activity_main.xml` = `shellTopBar`(ComposeView) + `fragmentContainer`(FrameLayout) + `shellBottomNav`(ComposeView)，无 MaterialToolbar。
  - `ui/shell/ShellState.kt`（tab/title/subtitle/backAction）、`ui/shell/ShellBars.kt`（`LanhangTopBar` = `SmallTopAppBar` + 更多菜单；`LanhangBottomBar` = `NavigationBar` + 4 个 `NavigationBarItem`，图标 `MiuixIcons.Weeks/Notes/Community/Settings`）。
  - `MainActivity` 仍是 Fragment 容器（hide/show 四页），`showToolbarMain(title)` / `showToolbarSub(title,onBack)` 驱动顶栏，`onConfigurationChanged` 更新暗色。
  - 各页宿主 Fragment 只保留数据/动作，UI 在同名 `*Screen.kt`：`grades/GradesScreen.kt`、`settings/SettingsScreen.kt`（page=NONE/APPEARANCE/TOOLS/ABOUT 内部切页，SAF 导出、诊断、语言、主题逻辑仍在 Fragment）、`moments/MomentsScreen.kt`（卡片流/九宫格/发布与评论 `OverlayDialog`/FAB/自动翻页）。
- **主题口径**：`ui/miuix/LanhangMiuix.kt` 的 `LanhangMiuixTheme(darkTheme = isNightMode())` = `MiuixTheme(light/darkColorScheme())`，**保留 Miuix 默认蓝 #3482FF**（不用校徽深蓝）。`res/values/colors.xml` + `values-night/colors.xml` 已按 Miuix 调色板重排（页面底 #F7F7F7/#000、卡片 #FFFFFF/#242424、主色 #3482FF、暗色次级文字 #787E96），**原 color 名字全部保留**（小部件/旧布局还在引用）。`themes.xml` 把 M3 语义色（`colorSurface*`/`colorPrimary*`/`colorOutline*`）映射到同一套颜色，XML 页面（登录、课表、系统弹窗）自动跟随；新增 `values-night/themes.xml` 只管状态栏图标亮暗。
- **课表只换皮**：`fragment_schedule.xml` 底色改 `@color/page_bg`、`bg_grid_panel`/`bg_day_section` 改 `@color/card_bg`（16dp 圆角）、周次胶囊圆角 50dp、`ScheduleStyle.FALLBACK_PRIMARY` 改 `0xFF3482FF`。**网格几何常量（rowH/gapH/gapW/timeW/bandH）、休息带、合并规则一律没动**。
- **工作圈刷新坑**：`MomentItem.likes/comments` 是普通 `CopyOnWriteArrayList`（不是 Compose 状态），卡片里用 `remember(item, revision)` 快照，Fragment 每次点赞/评论后 `revision++`，否则界面不刷新。
- **桌面预览**：`lanhang-miuix\preview\` 是独立的 Compose Desktop（JVM）工程，用同一套 Miuix 0.9.1 桌面产物 + `ImageComposeScene` 离屏渲染成 PNG（输出到 `artifacts\screenshots\*.png`，824×1830 @2x）。跑法：`cd lanhang-miuix\preview; ..\toolchain\gradle-8.14.3\bin\gradle.bat run`（**必须用 `D:\Dev\JDK27`**，Miuix 桌面产物是 Java 21 class file；`build.gradle.kts` 里已给 `run` 任务固定 `executable`）。改视觉后可重跑核对，不必装机。

- **二次改版（2026-10-24，用户逐条反馈后修）**——这次改的都是「感觉」问题，逐条对应如下，改的时候别回退：
  1. **页面切换要有过渡动画**：`MainActivity.switchTab` 从 `hide/show` 改成 **`detach/attach` + `setCustomAnimations(R.anim.tab_enter, R.anim.tab_exit)`**。hide/show 是 Visibility 变化，Fragment 动画根本不生效——这是原来「切页瞬切」的根因。detach 会销毁 View 但 FragmentManager 保存 View 状态，切回来滚动位置还在（`replace` 做不到）。重建（savedInstanceState≠null）时 `restoring=true` 不播动画，Tab 存在 `KEY_TAB` 里恢复。动画资源：`res/anim/tab_enter.xml`（alpha 0→1 + 上浮 1.5%p，220ms 减速）、`tab_exit.xml`（alpha 1→0，180ms）。Fragment 改为**懒创建**：首次进入哪个 Tab 才 `add`，其余 `detach`。
  2. **课表要 Miuix 化**：头部从「四个 Material ImageButton + 圆角 TextView 胶囊」换成 **Compose 的 `schedule/ScheduleHeader.kt`**（`fragment_schedule.xml` 里是 `ComposeView id=scheduleHeader`）：左侧 `‹ 第 N 周 ›` 胶囊（点周次跳周），右侧 **`TabRow` 分段控件**（网格/周视图）。网格本体仍是自绘 View，几何常量一个没动，但配色改成 **染色卡片**：块底 `ScheduleStyle.courseFill(color)`（浅色底 16% alpha / 深色底 32%）、左侧 3dp 色条、文字用 `onCourseTint(color)`（浅底加深同色相 v=0.58，深底提亮 v=0.95）、圆角 10→12dp、面板 16→20dp。周视图课程行同样处理（`item_lesson_row.xml` 新增 `vAccent` 色条，文字色不再写死白色）。**注意 `ScheduleHeaderState` 上的颜色覆盖**：自定义课表背景时 `applyChromeStyle()` 会把 `style.accentFill/chipFill` 写进去，否则头部永远是主题蓝、和背景不搭。
  3. **课表翻周要有过渡动画**：`ScheduleFragment.animateSwap(direction)`——换周/切视图后把当前内容容器从 ±26dp 位移 + alpha 0.35 收到原位（230ms 减速）。`switchWeek` 按新旧周次决定方向，`switchView` 按去网格(+1)/去周视图(-1)。**别用「整块 View 重建」代替动画**，网格重建会闪。
  4. **成绩选中学期字号变小**：原来选中态用 `Button` + `Text(footnote1)`、未选中用 `TextButton`（内部是 17sp button 文本），两套字号不同 → 视觉上「选中的反而更小」。现在统一用 **`TabRow(tabs, selectedTabIndex, onTabSelected)`**，天然同字号 + 指示器滑动动画。**`TabRow` 内部自带 `fillMaxWidth`，只靠 `Modifier.width()` 压不住宽度，必须套一层固定宽的 `Box`**（课表头 176dp；成绩页给 `Modifier.padding(horizontal=12.dp)` 免得贴屏幕边）。
  5. **工作圈赞/评论没图标**：`MomentsScreen.MomentActionChip`（图标 + 数字的 50% 圆角胶囊），图标用项目已有矢量 `ic_favorite` / `ic_favorite_outline` / `ic_mode_comment`（Material Symbols，别为这个引入图片库）。点赞后心形实心 + 暖红 `#E94634`。
  6. **顶栏三个点（More）要删**：那个 `OverlayIconDropdownMenu` 在 `renderInRootScaffold=false` 时点了没反应，用户判定「没有功能」。已从 `ShellBars.LanhangTopBar` 和 `ShellState` 里删掉（`showMenu` 一并删除），**退出登录移到「设置 → 账号」，调试课表移到「设置 → 课表工具」**（`MainActivity.logout()/debugSchedule()` 现在是 public，由 `SettingsFragment` 调用，退出前有确认弹窗 `settings_logout_confirm`）。新增操作入口时优先放进设置页，别再往顶栏塞图标。

- **三次改版（2026-10-24，用户按截图逐条反馈）**——以下都是「别回退」的决定：
  1. **切页动画 = 左右滑动**：`MainActivity.switchTab` 比较新旧 tab 索引选方向，往右切用 `R.anim.slide_in_right` + `slide_out_left`，往左切用 `slide_in_left` + `slide_out_right`（4 个资源在 `res/anim/`，入场 300ms 位移 100%p，退场 240ms 位移 ±30%p + 淡到 0.35）。**不要退回 `tab_enter/tab_exit` 那种纯淡入淡出**（用户明确说「切换的动画不对」）。重建（`restoring=true`）时仍然不播动画。
  2. **课表页头按参考截图重排**：`schedule/ScheduleHeader.kt` 现在是单行 `[回到本周] … ‹ 第 N 周 › … [网格/周视图]`，周次用 `title4` 居中加粗，不再有「左胶囊 + 右 TabRow」；点周次跳周（Miuix 对话框）、左右箭头翻周、左右滑动网格也能翻周。参考截图右上角的 ⋮ 是空菜单，**别再塞回顶栏**。
  3. **课表标题是「课表」**：`MainActivity.applyTitle` 统一走 `titleForTab()`，课表分支用 `R.string.tab_schedule`，**不要再回落到 `R.string.app_name`**。
  4. **翻周/切视图动画要能看出方向**：`animateSwap` 位移 = 容器宽度 × 45%，时长 360ms，`DecelerateInterpolator(1.6f)`，起始 alpha 0.15。位移小/时长抖成 200ms 级就看不出左右滑（用户第一次就是这么反馈的）。
  5. **网格一屏装下 11 节**：`renderGrid` 的行高不再写死，用 `gridScroll.height` 反推 `rowH = (availH - panelPad - headerRowH - 2*bandH - 10*gapH) / 11`，夹在 32~56dp；配套常量 `gapH=3dp, gapW=4dp, timeW=44dp, bandH=18dp, headerRowH=46dp`。`fragment_schedule.xml` 里 `gridScroll` 的 `paddingBottom` 必须是 2dp（原来是 96dp，白吃一屏高度）。**只有 `availH == 0`（首帧未测量）才 `post{}` 重画一次**，否则矮屏会自己触发自己死循环。
  6. **课程块 = 纯浅色胶囊 + 同色系深字**（对齐参考截图）：底色 `ScheduleStyle.courseFill()`（HSL 浅底 s0.62/l0.90，暗色 s0.40/l0.26），文字 `onCourseTint()`（浅底 s0.72/l0.38，暗底 s0.55/l0.80），块内居中、圆角 10dp、**不要左侧色条**。周视图列表行（`item_lesson_row.xml`）保留 `vAccent` 色条。
  7. **所有对话框必须 Miuix**：统一走 `ui/miuix/MiuixDialog.kt` 的 `showMiuixDialog / showMiuixInfo / showMiuixConfirm / showMiuixPicker / showMiuixProgress`（系统 Dialog 窗口 + 内部 Miuix `Card`/`TextButton`，因为 Fragment 宿主拿不到 Miuix `Scaffold`，用不了库自带的 `OverlayDialog`）。已替换：设置页（主题、语言、导出格式、补周确认、退出登录、诊断、导出进度）、课表（跳周、课程详情）、`MainActivity`（调试弹窗）。**禁止再新增 `MaterialAlertDialogBuilder` / `PopupMenu` / 裸 `android.app.Dialog`**。
  8. **学期胶囊不许截断**：`GradesScreen` 的 `TabRow` 要 `minWidth=124.dp, maxWidth=132.dp`（默认 98dp 会把「2026-2027-1」截成「202…」）；`TabRow` 内部是 LazyRow，超宽自动横向滚动并居中选中项，所以放宽宽度不会把别的 Tab 挤没。
  9. **`PullToRefresh` 必须显式传 `refreshTexts`**：库默认文案是写死的英文（"Release to refresh"），中文界面下拉会冒英文（用户反馈的 "release to flash"）。统一用 `ui/miuix/RefreshTexts.kt` 的 `refreshTexts()`（`refresh_pull/release/refreshing/done` 四个字符串资源），新页面用 `PullToRefresh` 时别忘了。
  10. **预览工程跑法**：`$env:JAVA_HOME="D:\Dev\JDK17"` 启动 Gradle（Gradle 8.14 不支持 Java 27，直接用 JDK27 会报 `What went wrong: 27`），`run` 任务自身 `executable` 已在 `build.gradle.kts` 里钉成 `D:\Dev\JDK27\bin\java.exe`。

- **三次改版·补丁（2026-10-24 晚，用户实测崩溃后修）**——三条都是「踩过的坑」，别回退：
  1. **Compose 放进 `Dialog` 必须装 view-tree owner**：自建 `Dialog` + `ComposeView` 时，Dialog 的 DecorView 上没有任何 `ViewTreeLifecycleOwner`，一挂 `ComposeView` 就抛 `IllegalStateException: ViewTreeLifecycleOwner not found from DecorView@...`——**所有「该弹对话框的地方点击后必崩」就是它**。`ui/miuix/MiuixDialog.kt` 已有 `ComposeView.installViewTreeOwners(context)`：从 context 链里找 `ComponentActivity`，把 `setViewTreeLifecycleOwner / setViewTreeViewModelStoreOwner / setViewTreeSavedStateRegistryOwner` 三个都装到 ComposeView 上，配合 `DisposeOnDetachedFromWindow` 不泄漏。**以后在 Dialog/PopupWindow 里塞 ComposeView 也必须走这套**，别只 `setContentView`。
  2. **不要在 Fragment 事务上用 `setCustomAnimations`**：这套「detach/attach + 每页都是 ComposeView」的组合下，事务动画会让切页 100% 崩（用户实测：切到成绩/工作圈必崩）。现在 `MainActivity.switchTab` 用 `tx.commitNow()` 之后再调 `slideIn(tab > from)`：直接给新页根 View 做 `translationX`（±35% 宽 → 0）+ alpha 0→1，300ms 减速，往右切从右滑入、往左切从左滑入。`res/anim/` 下 6 个动画文件已删，别再引回来。
  3. **组合期（`setContent {}` 里）禁止 `requireContext()`**：Fragment detach 之后组合还可能重组，届时 `requireContext()` 直接抛 `not attached to a context`。四个页面的 `LanhangMiuixTheme(darkTheme = …)` 一律用 `LocalContext.current.isNightMode()`。
  4. **崩溃现场必须能自证**：`App.installCrashLogger()` 把未捕获异常写到 `filesDir/crash-<时间>.txt`（留最近 5 个）**并镜像到** `getExternalFilesDir(null)`（`/sdcard/Android/data/com.lanhang.miuix/files/`，文件管理器可直接取）；`Diagnostics.report()` 会把最新一份崩溃内容附在报告里，用户「设置 → 诊断与日志 → 分享」就能把堆栈发出来。**再遇到「100% 崩溃」先要日志，别猜。**
  5. **课表页头图标取色**：`ScheduleHeader` 的图标 tint 用 `(state.onContainer ?: scheme.onSurface).copy(alpha = 0.72f)`，**不能用 `state.container`**——那是 `chipFill`（近白的胶囊底色），画在浅色页面上等于隐形（用户反馈「翻周箭头和视图切换按钮跟白底融为一体」）。
  6. **本机模拟器由用户用 Android Studio 配置（2026-10-24）**：agent 不自己下载系统镜像、不改 AVD 配置（用户明确要求）。模拟器起来后 `adb` 在 `D:\Dev\AndroidSdk\platform-tools\adb.exe`，装包用 `adb push … /data/local/tmp/` + `pm install -r`；崩溃日志 `adb shell run-as com.lanhang.miuix cat files/crash-*.txt` 或 `adb logcat -b crash`。

- **四次改版（2026-10-24 深夜，照 Miuix 官网 demo 重新对齐 + 崩溃真因）**——这几条是硬约束：

  0. **「切 tab 必崩 / 弹对话框必崩」的真因是构建，不是代码**：旧 APK 里 `com.lanhang.miuix.ui.miuix.MiuixDialogSpec`
     只有**字符串引用**、没有任何 dex 有它的 **Class descriptor**（用 `dexdump.exe` 在 build-tools 里逐个 dex 查），
     所以运行时 `NoClassDefFoundError`。是 AGP **增量 dex 合并丢了类定义**，`ui/miuix/*` 的改动没进最终 dex。
     **规矩**：动过 `ui/miuix/`、新增过类、或换过依赖之后，一律 `gradle clean assembleDebug`（或删
     `app/build/intermediates/dex*`），装包前先自检：
     `python scripts/check_dex.py <apk> MiuixDialogSpec`（见 `scratch/dex_check` 思路：解 zip 取 `*.dex`，
     `dexdump … | findstr "Class descriptor"`）。别再拿增量产物去给用户测。

  1. **顶栏分两层（照 demo 的 `AdaptiveTopAppBar`）**：`ShellBars.LanhangTopBar(state, large)`——
     主 Tab（成绩/工作圈/设置）传 `large = shell.tab != TAB_SCHEDULE && backAction == null`，走 Miuix `TopAppBar`
     （大号粗标题 + 副标题 + 上方保留 56dp 紧凑行，HyperOS 展开态就长这样）；**课表页和二级页保持
     `SmallTopAppBar`**——课表必须把高度全留给一屏 11 节。别为了「统一」把课表也换成大标题。

  2. **课表面板贴 Miuix Card**：`bg_grid_panel.xml` 圆角 16dp → **22dp**，`fragment_schedule.xml` 的
     `contentGrid` 外边距 8dp → **水平 12dp / 上 4dp / 下 12dp**，内边距 8dp → **10dp**（对齐 demo `Card` 的
     `padding(horizontal = 12.dp)` + `insideMargin = PaddingValues(16.dp)` 的观感）。改完的行高公式
     `rowH = (availH - panelPad - headerRowH - 2*bandH - 10*gapH)/11` 仍成立（`panelPad` 用实测 padding），
     一屏 11 节不能破。

  3. **空态也要有 Miuix 骨架**：成绩页「本学期暂无成绩」原来是一句裸灰字居中，现在放进
     `Card` + 140dp 高度 + `onSurfaceVariantSummary`，别回退成裸 `Text`。

  4. **模拟器验证清单（真机坐标，1080×2400 @420）**：底栏四个 tab 的**真实** `y≈2250`（不是 1800！之前按
     缩放后的预览图算坐标，点歪了白折腾一轮）；x = 课表 135 / 成绩 405 / 工作圈 675 / 设置 945。
     按文字点击用 `scratch/tap_text.py "外观" "主题模式" "跟随系统"`（`uiautomator dump` + 找 text 节点中心），
     比手算坐标可靠。截图用 `adb shell screencap -p /sdcard/s.png` + `adb pull`（`exec-out > 文件` 在
     pwsh 里会把 PNG 写坏）。

  5. **每轮验收必须走一遍**：启动 → 点课程块（弹 `MiuixDialogSpec` 对话框）→ 切成绩 → 切工作圈 → 设置里
     开主题对话框 → 切语言（会 `recreate()`）→ 四个 tab 各截一张 + `adb logcat -b crash -d` 必须为空。

- **五次改版（2026-10-24 深夜，工作圈「点赞/评论/＋」点了没反应）**——Miuix `OverlayDialog` 的三个坑，缺一不可：

  1. **必须有 `MiuixPopupHost`**：Miuix 的 `OverlayDialog` 不自己开窗口，只把状态写进
     `MiuixPopupUtils` 的 `LocalDialogStates`，由 `MiuixPopupHost()` 负责渲染。**没有任何 host 时，
     点「评论 / ＋」既不弹窗也不报错**（用户反馈「点了没功能」就是这个）。现在 host 挂在
     `activity_main.xml` 的 `shellOverlay`（全屏 `ComposeView`）里，`MainActivity` 给它 `MiuixPopupHost()`。
     **别再在页面内部挂第二个 host**（会渲染两份）。

  2. **`shellOverlay` 的边界 = 顶栏之下、底栏之上**：`layout_constraintBottom_toTopOf="@id/shellBottomNav"`，
     这样遮罩不会盖住底栏（用户要求「发评论时底栏保留」），底栏始终可点；XML 里也要声明在
     `shellBottomNav` 之后（同层级后声明者画在上面），否则底栏会压住对话框。
     另外 **Miuix 的手机端对话框默认贴底**（`DialogContentLayout` 里 `contentAlignment = BottomCenter`），
     想让它上抬就传 `outsideMargin = DpSize(12.dp, 120.dp)`（默认 `DpSize(12.dp, 12.dp)`，竖向值就是底部留白）。：ConstraintLayout 同层级按声明顺序绘制，
     overlay 在前则底栏会盖住对话框（用户实测「底栏挡住对话框」）。overlay 要 `clickable=false` /
     `focusable=false`，这样没弹窗时不拦截底栏点击（已验证 Tab 切换正常）。

  3. **ComposeView 里用 `OverlayDialog` 会崩在 navigation-event**：
     `IllegalStateException: No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner`
     ——`OverlayDialog` → `DialogContentLayout` → `NavigationBackHandler` 需要该 owner，而
     `androidx.activity 1.9.2` 的 `ComponentActivity` **不实现** `NavigationEventDispatcherOwner`。
     解法：`ui/miuix/LanhangMiuix.kt` 里 `CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides …)`
     包住 `MiuixTheme`，owner 用 `NavigationEventDispatcher()` 现造。这些类只在 miuix 的运行时依赖里、
     不在编译 classpath，所以 `app/build.gradle.kts` 加了
     `compileOnly("androidx.navigationevent:navigationevent-compose:1.1.1")`（**不增加 APK 体积**，别改成 implementation）。

  4. **真实平台，禁止在联调时真的发内容**：工作圈连的是学校线上系统，测试对话框只允许「打开 → 截图 → 取消」，
     **绝对不要点「发送 / 发表」**（用户 2026-10-24 明确要求）。

- **六次改版（2026-10-24 深夜，发表动态改成独立页面 + 九图）**——用户要求「发表动态就切换到新页面，可添加九张图片，
  按微信朋友圈的思路做，但外观是 Miuix」：

  1. **发表动态不再是弹窗**：新增 `moments/PublishMomentScreen.kt`，由 `MomentsFragment.publishPage` 切换，
     用 `AnimatedContent`（往左进、往右退的横向滑动）在列表页与发表页之间过渡。
     **别再退回 `OverlayDialog`**：那条路在 Fragment 里要 host + navigation-event owner 才能跑（见五次改版），
     而且弹窗装不下九宫格。
  2. **顶栏用二级页形态 + 右上角动作**：`ShellState` 新增 `actionText / onAction`，`MainActivity.showToolbarSub(title, onBack, actionText, onAction)`，
     `ShellBars.LanhangTopBar` 的 `actions` 槽渲染 Miuix `TextButton`。发表页 = 返回箭头 + 「发表动态」+ 右上「发表」。
     **调用方必须用具名参数 `onBack = { … }`**——尾随 lambda 会落到最后一个函数参数 `onAction` 上（`SettingsFragment` 已照着改）。
  3. **九宫格布局（对齐朋友圈）**：`Card` 里 3 列 `Row` + `Modifier.weight(1f)` + `aspectRatio(1f)`，已选图片
     `ContentScale.Crop` 填满 + 12dp 圆角，右上角 20dp 半透明圆形 ×（`MiuixIcons.Close`）做删除，末尾「＋」格
     （`surfaceContainerHighest` 底 + `MiuixIcons.Add`）在选满 `MAX_PUBLISH_IMAGES = 9` 后自动消失；标题行右侧显示 `3/9`。
     点/长按图片进 `ImagePreviewActivity`（复用工作圈的图片预览）。
  4. **选图仍走系统 Photo Picker**（`PickMultipleVisualMedia(9)`，不需要存储权限），`pickedImages` 是
     `mutableStateListOf<Uri>`，选/删直接驱动重组。发表页里的正文 `publishText` 也提升到 Fragment（`mutableStateOf`）。
  5. **返回的两条路都要通**：`OnBackPressedCallback` 注册到 `viewLifecycleOwner`（发表页启用），
     并且**再点底栏「工作圈」也退回列表**（`onTabSelected` 里先 `closePublish()`）。
  6. **进发表页要清空上次的正文和已选图**（`openPublish()` 里 `publishText = ""` + `pickedImages.clear()`），
     发表成功后 `closePublish()` 回列表并刷新。
  7. **真实平台，联调时绝不点「发表」**（用户 2026-10-24 明确要求）。要验九宫格就往模拟器相册塞图：
     `adb push x.png /sdcard/Pictures/Lanhang/` + `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///…`，
     流程只做「＋ → 选图 → Add → 点 × 删除 → 返回」。已验证：3/9 显示、第二行自动从「＋」开始、删除后变 1/9、预览可开可关。
  8. **点击坐标必须从 `uiautomator dump` 取**：截图在会话里是缩放显示的，按图里像素算 y 会差 100~200px
     （这轮就点空了两次，`content-desc` 搜「移除图片 / 添加图片」最稳）。

### 七次改版（2026-10-24，动效对齐 Miuix demo + 课表周次对比度）

用户四条意见：① Tab 切换动效不自然；② 再点同一个 Tab 不该有动效；③ 非本周（如第 9 周）文字和背景融为一体；
④ 进二级页（设置 → 课表工具）没有动效。

1. **Tab 之间 = 整页横移（对齐 demo 的 `HorizontalPager` 手感）**。`MainActivity.switchTab()` 不再用
   「只给新页 `translationX` 滑入」那种写法（旧页当场 detach，视觉上「新页飞进来、底下空一块」）。
   现在新建 `startPagerSwap()`：**动画期间先不 detach 旧页**，新旧两页一起 `animate().translationX(...)`
   整页反向横移（`TAB_SWAP_MS = 320L` + `PathInterpolator(0.2,0,0,1)`），动画结束的 `withEndAction`
   才 `finishSlidingOut()` 把旧页 detach；中途再切页先 `settleSlidingOut()` 收尾，避免容器里挂三个页面。
   **仍然不能用 `FragmentTransaction.setCustomAnimations`**（detach/attach + ComposeView 必崩，见三次改版）。
2. **同一 Tab 再点 = 静止**。`switchTab()` 开头 `if (from == tab && currentAttachedFragment(fm0) != null) { … return }`，
   只同步顶栏 + 让该页退回主层级（工作圈退出发表页、设置页对齐二级页顶栏）。
   ⚠️ 那个 `currentAttachedFragment(fm0) != null` 判断**不能省**：冷启动时 `shell.tab` 的默认值就是
   `TAB_SCHEDULE`，直接 return 会让首屏空白（本轮真踩过）。
3. **两页必须不透明**。Compose 页面根没有底色，横移到一半两页重叠时会互相「透出重影」。
   两处一起修：`startPagerSwap()` 里对两个根 View `setBackgroundColor(R.color.page_bg)`（跟亮/暗主题走）；
   `GradesScreen` / `MomentsScreen` / `PublishMomentScreen` / `SettingsScreen` 的根 Composable 一律
   `.background(MiuixTheme.colorScheme.background)`。
4. **二级页推入/退出**：`SettingsScreen` 的 `when (state.page)` 包进 `AnimatedContent`——
   进二级页 `slideInHorizontally(tween(300)) { it }` + 旧页 `slideOutHorizontally { -it / 4 }` 视差让位，
   返回时反向。**不要再用 fade+crossfade**：两页都不透明，淡入淡出只会得到一团重影
   （本轮先写成 `it / 5 + fadeIn` 就是这样翻车的）。
5. **课表周次：强调方向别搞反**。`第 N 周` 只有**本周**用强调色（`scheme.primary` 文字 + `primary.copy(alpha=0.12f)`
   圆角底），其他周退回 `onContainer`（黑/白正文色）且不加底；「回到本周」图标只在离开本周后才点亮强调色。
   同时 `ScheduleFragment.applyChromeStyle()` 里 `headerState.accent` 必须给 `style.accent`（主色），
   **不能给 `style.accentFill`**——那是 `colorSecondaryContainer`（近白），用周次文字上等于隐形（原第 9 周 bug）。
6. **验证动画靠录屏 + 抽帧，别靠截图**：`adb shell screenrecord --time-limit 12 /sdcard/x.mp4` →
   `adb pull`（中文路径 adb 偶尔拒绝写，先 pull 到 `$env:TEMP` 再 `Copy-Item`）→
   `ffmpeg -i x.mp4 -vf "select='gt(scene,0.02)',showinfo" -f null -` 拿变化时间点，
   再用 `fps=20` 抽帧 + `tile=5x2` 拼图看过程；要看「半路」还可以
   `adb shell settings put global animator_duration_scale 10` 把动画放慢 10 倍，截完记得改回 1。
   证据图：`artifacts/screenshots/anim-01..03-*.png`，录屏 `artifacts/videos/anim-tabs-and-subpage.mp4`。
   「点同一个 Tab 无动效」就是靠上面那次场景变化检测证明的：那段区间一处 `pts_time` 都没有。
7. **装完包先等 15~20 秒再截图**：`pm install -r` 后启动会先过 `LoginActivity`，太早截到的是
   `page_bg` 色的 starting window（本轮误判成「冷启动白屏」，查 `uiautomator dump` 才发现页面早就渲染好了）。

### 八次改版（2026-10-24 深夜，动效收尾 + 图片关闭手势 + release 包）

用户这轮的要求：① Tab 切换还在「快速闪动」；② 二级页动效照 demo（settings→about）补齐；③ 图片预览上滑关闭、
④ 点图片显示区域之外也关闭，都要「衔接流畅」；⑤ Tab 切换动画里出现圆角要修掉；⑥ 最后出一个 release 版放项目根目录。

1. **Tab 切换去掉圆角裁剪**：`MainActivity.startPagerSwap()` 里原来给两个页面设了 `outlineProvider + clipToOutline`
   做「穿透圆角」，用户看到 Tab 切换时页面被裁圆角（只有二级页推入才该有圆角）。现在 **Tab 横移不裁剪**，
   圆角只保留在 `SettingsScreen` 的二级页推入（Miuix `NavDisplayEffects.cornerClip`）。
2. **顶栏标题必须跟着页面同方向滑入**：原来 `switchTab()` 第一件事就是 `applyTitle(tab)`，标题瞬间变、页面还在滑 560ms，
   观感就是「闪一下」。现在 `showToolbarMain(title, slideDir)` / `showToolbarSub(...)` 末尾调 `animateTopBar(slideDir)`：
   先把 `shellTopBar` 摆到 `slideDir * 22dp` 且 `alpha = 0`，`post{}` 一帧后（等新标题组合出来）再用同一条临界阻尼弹簧
   （`SpringEase`，`TOP_BAR_MS = 380`）滑回原位。切 Tab 时 `slideDir = tab > from ? +1 : -1`；二级页推入 +1、返回 -1。
   **不要在 `switchTab` 里把 `applyTitle` 放在 `commitNow` 之后或延迟调用**——那会让标题和页面不同步。
3. **切页必须有兜底看门狗**（本轮真踩到）：设备一卡（模拟器 ANR、组合迟迟不来），`startPagerSwap` 已经把新页
   `translationX` 摆到屏幕外，动画却没跑完 → 旧页一直留在屏幕上，用户看到的是「点了 Tab 没反应」。
   现在 `switchTab()` 开头调 `resetAllPageViews()`（清位移/透明度/残留压暗层/顶栏 alpha），
   `startPagerSwap()` 末尾再挂 `swapWatchdog`（`SWAP_MS + 1.6s` 超时强制收尾）。
   **这两处都不能删**：它们是「动画卡住 → 永久半切」的唯一保险。
4. **图片预览的三种关闭方式，共用一条 `closeSmoothly()`**：
   - **上滑关闭**：新增 `moments/DragDismissLayout.kt`（`FrameLayout` + `Listener` 回调），
     `ImagePreviewActivity` 里按 `dy` 驱动 `translationY` + 缩放（1 → 0.82）+ 背景 alpha（1 → 0.15）；
     松手时 `|dy| > 屏高*0.16` 或 `|velocityY| > 700` 就飞走，否则 `springBack` 弹回（320ms）。
     `canDrag()` 里判断 `ZoomImageView.isZoomed`——放大状态让位给平移手势，不许拖关。
   - **点图片 / 点图片外**：`ZoomImageView.onTap`（`onSingleTapConfirmed`）→ `closeSmoothly()`。
     **`ZoomImageView.onTouchEvent` 必须无条件 `return true`**：未置 `clickable` 时
     `super.onTouchEvent()` 返回 false，手势会被 `ViewPager2` 的 RecyclerView 抢走，`onTap` 永远不触发
     （本轮实测：上滑能关、点哪儿都不关，就是这个原因）。
   - **返回键**：`onBackPressedDispatcher` → 同一函数；`finish()` 后 `overridePendingTransition(0, 0)`，避免系统退场动画和自定义动画打架。
5. **release 包**：`app/build.gradle.kts` 的 `release` 签名在没有 `keystore.properties` 时回落
   `signingConfig = signingConfigs.getByName("debug")`——**同一签名，所以 `pm install -r` 能直接覆盖 debug 包、登录态不丢**。
   产物 `lanhang-lite-release.apk` 放在 **`lanhang-miuix\` 根目录**（用户要求「方便找」），
   同时存一份到 `artifacts\apk\`。`proguard-rules.pro` 保留 `net/schedule/grades/moments` 整包 + Gson `TypeToken`
   + OkHttp/Coil 的 `-dontwarn`；改动这些包后必须重出 release 并装机验启动（R8 会丢反射类）。
6. **模拟器验证的坑（这轮踩了一整圈，别再重复）**：
   - `adb shell screencap` 在这台模拟器上会给出**过期帧**（页面早已切完，截到的还是上一屏甚至别的 App），
     判断状态一律用 `dumpsys window windows | grep <Activity>` / `dumpsys activity activities | grep topResumedActivity`，
     截图只当辅助。
   - `adb pull <设备路径> <中文路径>` 会报 `cannot create file/directory`：**先 pull 到 `$env:TEMP` 再 `Copy-Item`**。
     `ffmpeg -i <中文路径>\d%02d.png` 同样打不开，先把帧复制到 `$env:TEMP` 的纯 ASCII 目录再拼图。
   - `uiautomator dump` 在设备卡的时候会 `Timeout while connecting UiAutomation`（那是 uiautomator 进程自己崩，
     不是 App 崩），并可能返回**上一次的旧层级**（文件大小一模一样就是旧的）；先 `rm -f` 设备上的 xml 再看大小。
   - `adb shell "for i in ...; do screencap ...; done"` 在 pwsh 里必须用**单引号**传（双引号里 `\$i` 不会被展开）。
   - 连点十几张 screencap + 长动画会把模拟器拖到 ANR（`am_anr ... failed to complete startup`）；
     要抓中间帧就 `settings put global animator_duration_scale 10` + **force-stop 重启进程**（进程只在启动时读这个值），
     抓完记得 `put global animator_duration_scale 1`。
7. **工作圈还是那条红线**：测试只许「点开 → 取消」，本轮误触到评论行弹出「回复」对话框，立刻点了「取消」，
   **一次都没有点「发送 / 发表 / 点赞」**。

### 十三次改版（2026-10-25 上午，用户三轮反馈 4 条 + 3 段参考视频）

1. **大标题初始位置贴顶栏下部**：`CollapsingTitleBar` 里大标题的 `translationY` 从
   `lerp(0f, …)` 改成 `lerp(yLarge, ySmall, progress)`，其中
   `yLarge = BAR_LARGE_HEIGHT - blockSize.height - 12dp`（Miuix 大标题就坐在顶栏下部）。
2. **弹窗更贴 Miuix 规范**：卡片顶部加拖拽横条、整行铺满的列表（行间 0.5dp 左边留白分割线）、
   选中行主色 + ✓、底部「Cancel/确认」分割线按钮行；窗口宽度改成「屏宽 - 24dp」。
   学期切换、删除工作圈、周次选择、课程详情等全部共用这套 `DialogCardBody`。
3. **图片打开动画与关闭对称**：`ImagePreviewActivity.playOpenAnimation` 现在把图片先缩到
   缩略图原位（`scaleX/Y = sourceRect.width / fitted.width`，平移到缩略图中心），
   再动画到全屏；黑幕同步 0→1。要点：**必须先在首帧摆好位置，再用 `binding.root.post{}`
   启动动画**（首帧前 start() 会停在半缩放），并加了 `OPEN_MS + 260ms` 的兜底，
   万一被打断也强制落回「全屏 + 纯黑」。
4. **顶栏换标题不再整幅横移**：改成 1/4 幅随动 + 淡入淡出（`slideInHorizontally{it/4*dir} + fadeIn`
   对 `-it/4*dir + fadeOut`）。整幅横移会让新旧两条顶栏在半路重叠，出现「两个标题打架」，
   也就是用户视频里那段看起来很割裂的返回动画。

## 十二次改版（2026-10-25 上午，用户二轮反馈 4 条）

1. **顶栏收缩与文字真正同步**：`ShellScroll` 从布尔 `collapsed` 改成 0..1 的 `progress`
   （`ReportListScroll` 用 `firstVisibleItemScrollOffset / 320` 逐帧上报）。
   `ShellBars.kt` 新增 `CollapsingTitleBar`：**一个** `Text` 的字号（32sp→18sp）、
   横向位置（左对齐→正中）、副标题高度（18dp→0）全部按 progress 插值，
   不再用「两套顶栏 + 交叉淡入」（那版切换是离散的，看起来像闪）。
   自绘顶栏必须自己加 `statusBarsPadding()`，否则大标题会压到状态栏时钟上。
2. **所有对话框贴底栏**：`ui/miuix/MiuixDialog.kt` 的两个 dialog 窗口
   `Gravity.CENTER → Gravity.BOTTOM or CENTER_HORIZONTAL`，并 `attributes.y = 96dp`
   （底栏 64dp + 手势条 + 间距），周次选择/课程详情/确认/提示/进度全部跟着变。
3. **评论输入框 Miuix 化**：改成 Miuix `Card`（26dp 圆角）+ `textStyles.title4` 标题
   + Miuix `TextField` + `TextButton`，底边仍按窗口坐标算 `lift`，输入法弹出时贴键盘上沿。
4. **周视图课名自动换行**：摘要卡课名 `maxLines = 2`、字号 8.5sp，卡片高度 132dp → 158dp，
   常见课名（高等数学B1 / 大学计算机基础训练）不再全被省略号吃掉。

## 十一次改版（2026-10-25 上午，用户实机反馈 8 条）

1. **顶栏收缩速度**：`TopBarContent` 里 `animateContentSize` 200ms → 110ms，交叉淡入 180/120 → 120/70。
   之前高度变化明显落后于手指/列表滚动，观感是「文字先上移、顶栏慢慢跟」。
2. **自己动态的删除入口**：`MomentCard` 底部动作行末尾新增纯蓝色「删除」文字按钮
   （无底色、无阴影），点击弹 `OverlayDialog` 二次确认；长按卡片仍可触发同一确认框。
   回调从 `onLongPress` 更名为 `onDeletePost`，仅 `item.feed.user_id == Session.userId` 时渲染。
3. **切回课表空白**：`MainActivity.switchTab` 对 `TAB_SCHEDULE` 调 `ScheduleFragment.onTabSelected()`，
   它先 `clearTransitionLeftovers()`（取消位移动画、`translationX/Y = 0`、清除 `GHOST_TAG` 快照），
   再 `renderAll()`。根因是 detach/attach 复用视图时换周动画的 `translationX = ±整屏宽` 残留没人复位。
4. **评论/回复输入条**：弃用 `OverlayDialog`（居中、离 Tab 栏远），改为页面根 `Box` 内的贴底输入条。
   位置按**窗口坐标**自己算：`lift = 容器底边(窗口) - 输入法上沿(窗口)`，`bottom = lift + 8dp`。
   注意：本页 ComposeView 位于顶栏与 Tab 栏之间，直接 `imePadding()` 会减两次（输入条被顶到屏幕中部），
   完全不处理则被输入法挡住（edge-to-edge 下窗口不 resize）。
5. **午休/晚休**：去掉灰色胶囊底，只保留 70% 透明度的文字。
6. **周视图门数**：「0」而非「0 门」是 plurals 在窄卡里被裁。改成 `schedule_day_count_format`
   （`%1$d 门` / en `%1$d`）+ `setSingleLine` + 卡片内满宽居中，量词不再被裁掉。
7. **周视图课名省略**：摘要卡里的课名 TextView 显式给 `MATCH_PARENT` 的 LayoutParams，
   省略号才会落在卡片右边缘（之前 `addView` 默认 wrap_content 会提前 break）。
8. **点赞名单图标**：名单前加红色 `ic_favorite`（与点赞按钮同一图标），让人一眼看出这行是「谁点了赞」。

## 十次改版（2026-10-25）

用户九条反馈的落点：

1. **删除自己发布的动态**：`MomentsApi.deleteFeed(feedId)` → `DELETE /feed/{id}`；`MomentCard` 用
   `combinedClickable(onClick = {}, onLongClick = …)` 包整张 Card，长按弹 `OverlayDialog`：
   自己的动态给「删除这条动态」，别人的只提示「只能删除自己发布的动态」；成功才从列表摘掉并 `revision++`。
   **红线：此 API 只允许用户自己主动操作时触发，测试绝不调用。**
2. **课程色板 + 重合圆点**：`COURSE_PALETTE` 换成 Nexio 的 11 色 Material-500，`courseColor()` 选
   「与已用色相距离最远」的一支，同屏不撞色；卡片=低透明度同色 + 同色系深字。右下角圆点原来按
   `span >= 2`（连堂课）显示，改为 `LaneInfo.overlap`：**只有真冲突才显示**，冲突时并排分道（`laneLayout()` 贪心分道）。
3. **图片预览异常阴影**：`ImagePreviewActivity` 改半透明窗口（`Theme.LanhangLite.ImagePreview`：
   `windowIsTranslucent` + 透明 windowBackground + `windowAnimationStyle=@null`），黑幕由根布局背景 alpha 控制
   （0→1），不再是整窗 alpha 淡入。
4. **关闭动画**：`closeSmoothly()` 单击分支把整张图 scale/translate 缩回「点开时那张缩略图的窗口矩形」，
   黑幕同步 alpha→0；矩形由 `MomentsScreen` 的 `onGloballyPositioned { boundsInWindow() }` 经
   `ImagePreviewActivity.start(ctx, urls, index, rect)` 传入，`ZoomImageView.fittedRect()` 算起止比例；
   换了图片（`pager.currentItem != originIndex`）或无矩形时退回缩小淡出。打开动画只做黑幕渐显
   （真机上共享元素放大与系统 OPEN 过渡叠加会把图片卡在半缩放）。
5. **课表左侧裁切**：`colW` 不再用 `screenW = widthPixels - dp(32)` 估算，改为量 `contentGrid` 内宽；
   `contentGrid` 边距 12dp→2dp、内边距 10dp→4dp，节次列贴左缘，周日列不再被裁。
6. **刷新不重播 / 周切换动画**：`switchWeek` 里 `week == currentWeek` 直接 return；周/视图切换改为
   `captureSnapshot()` + `slideSwap()`（旧图 ghost 平移出场、新内容进场），时长曲线复用
   `MainActivity.TAB_SWAP_MS(220ms)` / `TAB_INTERPOLATOR(0.42,0,0.58,1)`，与底部 tab 切换一致。
7/8. **顶栏大标题与下滑收起**：`MainActivity` 里 `large = shell.backAction == null`（课表也用大标题）；
   新增 `ShellScroll.collapsed` + `ReportListScroll(listState)`：列表滑过 24px 上报收起，`LanhangTopBar`
   用 `animateContentSize` + `AnimatedContent(fadeIn/fadeOut)` 在大标题与 `SmallTopAppBar`（居中小标题）间切换；
   `switchTab()` 里 `ShellScroll.reset()`。
   *不用 Miuix `ExitUntilCollapsedScrollBehavior` 的原因*：它靠 `Modifier.nestedScroll` 从内容侧收滚动，
   顶栏在 Activity 的 ComposeView、列表在 Fragment 内，跨不过去，只能页面主动上报。
9. **成绩学期选择**：等宽 `TabRow` 分段控件 → 行式交互 `Card { ArrowPreference(title = 学期, summary = id, …) }`
   + `showMiuixPicker` 选择框，和课表「跳转周次」同一套。

## 九次改版（2026-10-25，Tab 动效对齐 KernelSU kit + 课表照 NexioSchedule 重排 + 三处实测 bug）

用户这轮的要求：① 动画切换还是有问题，参照 `github.com/chenaizhang/KernelSU-Style-UI-Kit`；
② 成绩页选中的学期仍被截成 `2024-202...`；③ 工作圈发图提示「图片大小超过服务器限制」；
④ 周视图第 8 周周一显示「6 门」（一二节都是高数，应算一节）；⑤ 周视图 0 门的天文字异常换行；
⑥ 课表 UI 可参照 `github.com/HaoZai000/NexioSchedule`。

1. **Tab 切换 = 纯位移（删掉压暗/视差/淡出）**。参考项目的 Tab 是**真的 `HorizontalPager`**：
   `animateScrollBy(一屏宽, tween(100 × 距离 + 100ms, EaseInOut))`，相邻 Tab 只有 200ms；
   Pager 语义就是「新旧两页各平移一屏、首尾相接」，没有 dim、没有 alpha、没有 parallax、没有圆角。
   我们前几版把 Miuix `NavDisplay`（那是**二级页推入**的规范）搬到 Tab 上，dim 0.5 + 视差 0.25 + 淡到 0.9 + 560ms 弹簧，
   观感就是用户连续两轮说的「快速闪动」。现在 `MainActivity`：`TAB_SWAP_MS = 220L` +
   `PathInterpolator(0.42f, 0f, 0.58f, 1f)`，`runSwap()` 只改 `translationX`，`swapScrim` / `MIUIX_*` 常量全部删除。
2. **顶栏标题跟页面同方向滑入（不再是「淡出→淡入」）**。Miuix 的 `TopAppBar(title: String)` / `SmallTopAppBar`
   **只吃字符串标题**、没有 composable 标题槽，所以把**整条顶栏**塞进 `AnimatedContent`
   （`ShellBars.LanhangTopBar`，targetState = `TopBarSpec(title, subtitle, large, hasBack, actionText)`）：
   入场 `slideInHorizontally { width * dir }`、退场 `slideOutHorizontally { -width * dir }`，
   两条顶栏都不透明、首尾相接，等价于参考项目「每页自带 TopAppBar 跟着 Pager 滑」。
   方向由 `ShellState.slideDir` 决定，档位 `ShellState.slideFast`：Tab 级 `tween(220)`；
   二级页 `spring<IntOffset>(1f, 146f)`（与 `SettingsScreen` 的推入同一条临界阻尼弹簧）。
   **旧的 `animateTopBar`（alpha 0 → 1 + 22dp 位移）已删，别再引回来**——那就是「闪一下」的来源。
3. **成绩学期标签用 `xnxqId`**：截断的真因不是宽度而是文案——原来 tabs 取 `GradeSemester.name`
   （「2024-2025学年第1学期」这种长串），再宽的胶囊也放不下。现在取 `id`（`2024-2025-1`），
   `TabRow(minWidth = 132.dp, maxWidth = 168.dp)`。**改回 name 会立刻复发**。
4. **周视图门数按「合并后的课程块」计**：`renderWeekView()` 先 `mergeCourses(...)` 再统计，
   `tvCount` 用 `merged.size`、预览也只列 `merged` 且 `maxLines = 1` + `ellipsize = END`。
   之前按「有课的节次」计数，高数连上 1-2 节就被算成 2 门（周一 6 门）。正确值：3/3/4/0/4/0/0。
   `item_day_summary.xml` 的 `tvCount` 也加了 `maxLines="1"`（0 门时文案被挤换行就是这个）。
5. **发图「超过服务器限制」的真因是单位**：`MomentImageUploader` 把服务器下发的 `max_file_size`
   一律当**字节**用，但融云媒体配置里给的是 KB（如 `2048`）→ 上限变成 2KB，压到 320px 也进不去。
   现在 `resolveMaxBytes()`：`<=0` 用兜底 2MB、`< 100_000` 视为 KB、否则字节，再夹到 `[64KB, 10MB]`；
   压缩阶梯放宽到质量 88→40、缩放 0.82 倍，长边从 2048 降到 **1600**（手机上看不出差别，体积小一半）。
   **别再假设单位是字节**。
6. **课表照 NexioSchedule 重排**（`ScheduleFragment.renderGrid` + `fragment_schedule.xml`）：
   - **去掉大白色面板**：`contentGrid` 背景默认透明（只有设了自定义背景才铺 `panelFill`），网格直接铺在 `page_bg` 上；
   - **不画纵向网格线**（原来每列一条 1px 竖线，整块像 Excel），横向线 `alpha = 0.5f`；
   - **课程块右下角加 6dp 实心色点**（`courseFill` 胶囊 + 课程本色圆点，仅 span≥2 的块），内容改挂 `blockInner`；
   - **休息带改全宽胶囊** `rounded(50f, divider)`（`bandFill` 在浅色下≈纯白，压在 #F7F7F7 上太抢眼），
     时间列只留同高占位（否则「午休」出现两次）；
   - **一律画满 11 节**（`drawPeriods = 11`）：以前只画到最后一门课的节次，下半屏是一片空白；
   - 几何公式、`mergeCourses` 规则、`PeriodClock` 时间表一个没动。
7. **顺手清掉的死代码**：`ui/navigation/liquid/*`（4 个文件，随液态玻璃底栏一起作废，全项目已无引用）。
8. **本轮踩坑**：用 PowerShell `Set-Content` 改 XML 会**带 UTF-8 BOM**，AAPT2 直接报
   `Cannot read field "elmName" because "root" is null`。改资源文件请用 Python 或 `write` 工具写无 BOM 的 UTF-8。

## 十四次改版（2026-10-25，全局规则：用户明确要求「以后所有类似场景都按这个方法」）

1. **对话框唯一形态**：全应用任何对话框/选择器/确认框，一律走 `ui/miuix/MiuixDialog.kt`
   的 `showMiuixDialog / showMiuixPicker / showMiuixConfirm / showMiuixInfo / showMiuixProgress`。
   形态固定：贴屏幕真底（可盖住底部 Tab）、26dp 圆角、固定 0.42 遮罩、标题居中加粗、
   副标题居中灰字、列表无分割线且选中项整行淡蓝底 + 主色 ✓、底部整宽圆角按钮
   （单个=浅灰取消，两个=左灰右主色）。**打开时自动滚到当前选中项**。
   进场：整幅上滑 + 渐显（260ms）；退场：进场的严格反向（整幅下滑 + 渐隐 150ms，不缩放）；
   点外部 / 返回键 / 取消按钮都走同一个 `dismissAnimated()`；窗口级动画必须保持关闭。
   禁止再出现 `AlertDialog`、`PopupWindow`、自绘底部面板等第二种弹窗实现。

2. **滚动 → 顶栏联动**：任何可纵向滚动的页面，都要把滚动位置上报给
   `ui/shell/ShellState.kt` 的 `ShellScroll.report(0..1)`，顶栏据此逐帧收起/展开
   （大标题 → 居中小标题），离开页面调 `ShellScroll.reset()`。
   列表页用 `ReportListScroll(listState)`（Compose `LazyListState`）；
   自绘/View 滚动容器（如课表 `NestedScrollView`）用 `setOnScrollChangeListener` 上报
   `scrollY / 320f`。禁止出现「内容滚了顶栏不动」或「离散切换」的第二种写法。

---

## 7. 2026-10-26 第二轮反馈修复（0.13.0 / versionCode 33）

用户这一轮五条意见，逐条落点如下，改的时候别回退。

### 7.1 通讯录左上角「回我的主页」键

- `ShellState` 新增 `homeAction`（与 `backAction` 并列），`ShellBars.TopBarSpec.hasHome` 为真时
  顶栏左侧先画一个手绘房子图标（`LanhangIcons.Home`，24 栅格 / 1.9 线宽，和别的图标同一套口径），
  再画返回箭头。**顺序永远是「首页 | 返回 | 标题」**，返回箭头在最左是旧版行为，别改回去。
- `MainActivity.showToolbarSub(title, onBack, …, onHome)` 传 `onHome`；`showToolbarMain` 会把它清空，
  所以主 Tab 上不会多出这个键。
- `MineFragment.goRoot()`：一路弹到 `MinePage.ROOT`。**首页键 = goRoot（回「我的」根页）**，
  返回箭头 = `goBack()`（只退一级目录），两者语义不要混。
- 目前只有通讯录这一层挂首页键（用户点名的地方）；其它二级页仍是纯返回箭头。

### 7.2 工作页宫格间距再收窄

`work/WorkPage.kt`：`AppGlyph` 默认尺寸 **48dp → 66dp**，`AppTile` 横向 padding 归零、纵向 3→2dp，
图标与名称间距 5→3dp，名称定高 38→36dp，卡片内边距 10→6dp（外距 12→10dp）。**仍然是一行 4 个**
（用户明确要过 4 列，别为了「看起来更密」加成 5 列）。判断密度的口径是「图标本体之间的空白」：
现在是 ~28dp，改小图标或加大列数都会破坏用户要的观感。

### 7.3 私信群聊

- `ImConversation` 变成 `(peerId, title, isGroup, preview, time, unread, previewSenderId)`：
  **单聊 peerId = 对方 userId，群聊 = 群 id**，会话列表把 PRIVATE 和 GROUP 一起取（`getConversationList` 传两个类型）。
- `ImMessage` 带 `senderId`；群聊里别人的气泡上方显示「谁说的」——名字走 `UserInfoCache.resolve()`，
  查不到时显示「成员 · 后四位」，**绝对不能拿群名当人名**（第一版就是这么错的）。
- 列表预览「谁：正文」在 `ImScreens.previewText()` 里拼，仓库只存正文，免得缓存没热时把一串 id 写进字符串。
- 群头像是圆角方块 + 通讯录图标（和单聊的圆形人像区分），后面跟一个「群聊」小标签。
- **加入群聊是内联表单，不是对话框**（Fragment 宿主里弹 Miuix 对话框要 host + navigation-event owner，
  能不用就不用）：`ImStatusBar` 右侧「加入群聊」→ Card 里两个 TextField（群 ID / 群名可留空）→ 取消/加入。
- ⚠️ **IMLib 2.9 客户端没有 `createGroup` / `getGroups`**（建群只能走融云服务端 API，要学校的 app secret）。
  所以只做「加入已有群」：`joinGroup(id, name)` → `syncGroup(listOf(Group(id, name, null)))` → 刷会话列表。
  **不要再加「新建群聊」按钮**，那是假功能。

### 7.4 小米推送（MiPush）——骨架，未通电（⚠️ 已在 0.14.0 放弃，见 §8.2）

- 依赖：`com.xiaomi.mipush.sdk:mipush:4.9.1`（阿里云 `repository/public` 才有；纯 jar、无清单、无传递依赖）。
  清单里的 `com.xiaomi.push.service.*` / `com.xiaomi.mipush.sdk.*` / `io.rong.push.platform.mi.MiMessageReceiver`
  是我们手写的（SDK 是 jar，不自带清单）；`<permission>` 必须放 `<manifest>` 下、**不能放 `<application>` 里**（AAPT 会直接报错）。
- 运行时：`im/ImPush.kt` 读清单里的 `lanhang.mipush.appId / appKey`（值来自 `gradle.properties` 的
  `lanhangMiPushAppId / lanhangMiPushAppKey` → manifestPlaceholders）。**留空就不注册**，
  `ImRepository.connectWith().onSuccess` 里调 `ImPush.registerIfConfigured()`，失败只打日志不影响聊天。
- 要真通电还差两步（都不是代码）：dev.mi.com 用本包名+本签名建应用拿 APPID/APPKEY；学校融云控制台勾「小米」通道。
  **在拿到这两样之前，任何「推送已开启」的文案都是假的，别加。**
- **`proguard-rules.pro` 里已经 `-keep class com.xiaomi.push.** / com.xiaomi.mipush.**`**：这些类只被清单和反射按类名引用，R8 改名/裁剪后表现为「注册成功但收不到推送」。改推送相关代码别删这几行。
- R8 对 mipush 4.9.1 会报 `Expected stack map table` / `does not type check` 警告，是它自带的旧字节码，
  构建能过；真接推送时若某个方法被 R8 判成不可达，再加 `-keep class com.xiaomi.** { *; }`。

### 7.5 「通讯录 → 某人主页 → 返回」必须回上一级目录

- 他人个人主页不再是「切到工作圈 Tab」，而是**「我的」这栈里的第三层**：
  `MinePage.PROFILE_OTHER = 13`（depth=2），压栈顺序 `ROOT → CONTACTS → PROFILE_OTHER`。
  `MineFragment.openOtherProfile(userId)` 负责压栈 + `loadOther(reset)`；
  数据字段是 `MineUiState.other*`（staff/items/loading/refreshing/ended/revision），
  互动回调 `onLikeOther / onPostCommentOther / onDeleteCommentOther / onDeletePostOther`。
- 通讯录的下钻栈与已加载的树提升成 `ContactsUiState`（`@Stable` class，`tree/error/query/reload/path`），
  由 `MineScreen` 在 `AnimatedContent` **之外** `remember` 后传给 `ContactsScreen(state = …)`。
  **别放回 ContactsScreen 内部**：离开页面（进个人主页）时组合会销毁，回来就退回根层了。
- 顶栏标题：`PROFILE_OTHER` 显示那个人的名字（`otherStaff.name` → `UserInfoCache` → 「个人主页」）。
- `onPageOpened(PROFILE_OTHER)` 里按需拉数据（空列表且没在加载才拉）。

---

## 8. 2026-10-26 第三轮反馈（0.14.0 / versionCode 34）

用户两条要求（原文）：①「通讯录中返回上一级去掉，返回按钮用来返回上一级」②「放弃接入 mipush，改用普通通知」。

### 8.1 通讯录只留顶栏返回箭头，不再有页面内「返回上一级」

- `ContactsScreen.DeptContent` 里那颗「返回上一级」胶囊（`R.string.contacts_back_level`，随它一起从
  `values/` 和 `values-en/` 删掉）**已删除**。部门页顶部那行现在只剩当前部门全名，当「你在哪」的小标题用。
- **顶栏返回箭头现在负责逐级退回**：`ContactsUiState` 从 `MineScreen` 的 `remember` 提到 `MineFragment`
  的字段（`private val contactsUi`），通过 `MineScreen(state, contactsState = contactsUi, …)` 传进去，
  `ContactsScreen(state = …)` 再消费。`MinePage.CONTACTS` 的 `onBack` 是
  `{ if (!contactsUi.popOneLevel()) goBack() }` —— 先退目录，退到通讯录根层才退「我的」页栈。
- **状态必须在 Fragment 这一层**：`ContactsScreen` 只在 `path.isNotEmpty()` 时注册 `BackHandler`，
  顶栏箭头走的是 `MainActivity.showToolbarSub(onBack = …)`，两条路都要能退目录，所以状态不能放 Compose 里。
  已实测：校领导（2 层）→ ← → 教职工（1 层）→ ← → 通讯录根（组织架构/教职工/学生）→ ← → 「我的」根页。

### 8.2 放弃 MiPush，改本地通知（用户要求）

**MiPush 已彻底移除**，别再加回来（用户明确要求放弃）：

- 依赖 `com.xiaomi.mipush.sdk:mipush` 删除；`settings.gradle.kts` 里只为它加的
  `maven.aliyun.com/repository/public` 删除；`gradle.properties` 的 `lanhangMiPushAppId/AppKey` 删除；
  `im/ImPush.kt` 整文件删除；`ImRepository.connectWith().onSuccess` 里的 `ImPush.registerIfConfigured()`
  换成 `ImNotify.ensureChannel(AppContext.instance)`。
- 清单：`com.lanhang.miuix.permission.MIPUSH_RECEIVER` 的自定义 `<permission>`、`lanhang.mipush.*` meta-data、
  小米那串 service/receiver、`io.rong.push.platform.mi.MiMessageReceiver` 全部删掉；
  新增 `POST_NOTIFICATIONS` 权限。`proguard-rules.pro` 里小米那批 `-keep` 也删了（保留 `-dontwarn com.xiaomi.mipush.**`，
  因为融云 AAR 的 push 模块还引用它）。
- ⚠️ 融云 AAR 里 `io.rong.push.platform.mi.*` 的字节码仍在（`-keep class io.rong.**` 保着），
  **但那只是死代码**：没有清单组件、没有 SDK 依赖、运行时不会注册。别据此以为还能收离线推送。

**现在的通知实现（`im/ImNotify.kt`）**：

- 渠道 `im_message`（「私信消息」，`IMPORTANCE_HIGH`），文案 `im_notify_channel / _desc`；
  小图标 `drawable/ic_im_notify.xml`（纯白剪影，系统按状态栏配色着色）。
- 收消息在 `ImRepository.installListeners()` 的 `setOnReceiveMessageListener` 里，映射成 `ImMessage` 后
  调 `notifyIncoming(peer, mapped)`：单聊标题 = 对方姓名（`UserInfoCache`，查不到用「新私信」），
  正文 = 消息文本；群聊标题 = 群名（`groupTitleOf`），正文 = 「成员名：文本」（`im_group_sender`）。
- **不弹的三种情况**：自己发的（`mapped.mine`）、App 在前台且用户正停在这个会话
  （`ImNotify.appForeground && activePeerId == peerId`）、没有通知权限。
  `MainActivity.onResume/onPause` 维护 `appForeground`；`ImChatScreen` 用 `DisposableEffect(peerId)`
  设 `activePeerId` 并在进入时 `ImNotify.cancel(peerId)`（看过就把这个会话的通知清掉）。
- **权限**：Android 13+ 第一次进「私信」列表（`ImConversationsScreen`）或从别人主页「发私信」
  （`MineFragment.openImChat`）时申请；被拒绝只是没通知，聊天照常。`MainActivity.ensureNotifyPermission()` 是入口。
- **点通知进会话**：`PendingIntent` 带 `EXTRA_IM_PEER/EXTRA_IM_TITLE` → `MainActivity.handleWidgetIntent`
  → `openDirectChat(peerId, title)` → 切「我的」Tab + `MineFragment.openImChat`。通知 id 用 `peerId.hashCode()`，
  同一会话只留一个通知位。
- **口径（别写错）**：这是**本地通知**，进程活着（前台或后台）才收得到；进程被系统杀掉/被清理后收不到，
  那要厂商推送通道。任何「离线推送已开启」的说法都是假的。

### 8.3 版本

`versionCode 33 → 34`、`versionName 0.13.0 → 0.14.0`（去掉 mipush jar 后 APK 8.76MB → 8.33MB）。

---

## 9. 2026-10-26 第四轮反馈（0.15.0 / versionCode 35）

用户五条要求（原文）：

1. 「所有涉及到头像与姓名的场景，点击后都应该能切换到个人主页。如在群聊中点击头像/姓名直接进入」
2. 「默认返回操作是返回上一级而非直接回到主页，直接返回主页我会让你设计 home 键」
3. 「工作界面中图标太大，改小一点，一行 6 个」
4. 「群聊与私信都可以发表情，参考原蓝航 app」
5. 「群聊名称不对，原来是 2601063 变成了 mlia。修复这些问题」

### 9.1 头像 / 姓名 → 个人主页（补齐私信里的两处缺口）

- `ImChatScreen` 新增两个回调：`onOpenProfile: (String) -> Unit`（别人）与 `onOpenSelf: () -> Unit`（自己）。
  `MessageBubble` 里 **别人的头像 + 群聊里的发送者姓名** 都可点（`senderId` 传给 `openOtherProfile`），
  **自己的头像** 回「我的主页」；`ImConversationsScreen` 新增 `onOpenProfile`，单聊会话行的**头像与姓名**也能点
  （群聊行保持原样——本项目没有群资料页，不假装有）。
- `MineScreen` 把 `onOpenOtherProfile` / `onOpenProfile`（我的主页）接进 `IM`、`IM_CHAT` 两页；
  `MinePage.ALBUM` 原来传的是空 lambda（相册里点头像没反应），现在点自己的头像/姓名回我的主页。
- 已有的「工作圈 / 消息 / 通讯录 / 我的首页」头像入口本来就通，本轮只补私信这两处，别回退成不可点。

### 9.2 返回只退一级（并且过渡方向跟页面栈走）

- 私信聊天页的「上一级」= 私信列表：`MineFragment.openImChat` 里 **`stack.size <= 1` 时先垫一层 `MinePage.IM`**，
  这样从通知 / 外部入口（`MainActivity.openDirectChat`）直接进聊天，返回也是先回列表、再回「我的」根页，
  不会一步落回主页。实测：他人主页 → 群聊 → 私信列表 → 我的根页，逐级退。
- `MineUiState` 新增 `forward: Boolean`，`minePushTransform` 的推入/返回方向改由 **Fragment 的页面栈**决定
  （`open()` 置 true，`goBack()/goRoot()` 置 false），不再用 `MinePage.depth()` 猜——两者 depth 相同时
  （聊天页 → 他人主页）以前会把「返回」演成「推入」。
- 「直回主页」仍然是独立入口：通讯录左上角那颗 ⌂（`onHome = { goRoot() }`）与「再点一次底栏『我的』」
  （`MineFragment.onTabSelected` → `goRoot()`）。**返回箭头/返回键一律只退一级。**

### 9.3 工作页宫格：一行 6 个、图标 66dp → 44dp

- `work/WorkPage.kt`：`GRID_COLUMNS 4 → 6`，`AppGlyph` 默认边长 66dp → **`GLYPH_SIZE = 44dp`**，
  名称行高 36dp → 32dp，卡片外距 10→8dp、内距 6→4dp，圆角 16→14dp。
- 名称仍是**两行定高 + 省略号**：6 列时一格约 68dp，一行 6 个字，12 个字的户名两行刚好放完。
  改列数请连 `GLYPH_SIZE` 一起看，别只改一个。

### 9.4 表情：私信与群聊都能发（`im/ImScreens.kt`）

- 输入条左侧新增**手绘笑脸开关**（`LanhangIcons.Emoji`），点开在输入条**上方**展开表情面板：
  8 列 Unicode 表情 + 右下角退格（`LanhangIcons.Backspace`），点表情只往草稿里插字符，
  发送仍然走原来的 `ImRepository.sendText` → `TextMessage`。
- **为什么不用 IMKit 那套表情**：原版蓝航的表情面板 + `rc_emoji_*` 位图属于融云 IMKit（商业 SDK），
  本项目只接 IMLib、界面全自绘；Unicode 表情落到的还是普通文本消息，对方用官方客户端照样看得见，
  也不需要额外贴图协议。**别为了「像原版」去引 IMKit 或表情图片库。**（`im_emoji` / `im_emoji_backspace`
  两个字符串资源中英各一份。）
- 退格按**码点**删（`String.dropLastEmoji()`，表情是代理对，按 char 删会留半个）。

### 9.5 群名修复：真名只认学校服务端，不再往 SDK 里写占位名

- 真因：`joinGroup(id, name)` 的第二个参数会被 SDK 当成群名**落进本地库**，旧实现在用户没填群名时传的是
  占位「群聊 · 后四位」，于是真名被覆盖，之后任何地方读 `conversationTitle` 都只能拿到这串假名字
  （用户看到的就是「原来是 2601063 变成了 mlia」）。
- 融云 IMLib 2.9 **没有 `getGroupsInfo`**（`RongIMClient` 上只有 `joinGroup/quitGroup/syncGroup`），
  群名的权威值在学校 RCE：`GET /api/groups/{id}` → `result.name`（官方 `GroupTask.GROUP_INFO = "/groups/%s"`，
  成功码同为 10000）。新增 `RceApi.groupInfo()` + `net/Models.kt` 的 `ImGroupInfo`。
- `ImRepository.groupTitleOf()` 现在的优先级：**已拿到的真名 → SDK 的 `conversationTitle`（只在不以「群聊 · 」开头时采信）
  → 异步补拉 `/groups/{id}`，本次先用占位顶着**。拿到真名后写进 `groupTitles` 并 `syncGroup` **把 SDK 库里
  被污染的旧名覆盖回真名**，退出重进也不会再看到假名。
- `joinGroup` 改为先问服务端真名 → 用户填的名字 → 都没有就传空（**绝不写占位符**）；
  另加 `groupIds` 集合记录见过的群 id，`isGroup()` 不再依赖「有没有群名」来判断。
- 实测：会话列表与聊天页标题都显示 `2601063`，强杀重启后仍是 `2601063`，crash 日志为空。

### 9.6 版本与验证

- `versionCode 34 → 35`、`versionName 0.14.0 → 0.15.0`，APK 8 349 636 B。
- 实测（模拟器 emulator-5554）：私信群聊 2601063 → 点发送者姓名进「张敏佳」主页 → ← 回群聊 →
  ← 回私信列表 → ← 回我的根页；表情面板开/插/退格正常；工作页 6 列宫格与 44dp 图标正常；
  `adb logcat -b crash -d` 为空。
- **红线不变**：私信/工作圈都是学校线上系统，联调只做「打开 → 截图 → 取消/返回」，**绝不点「发送」「发表」「点赞」**，
  也**绝不调用 `joinGroup`**。
## 10. 2026-10-26 第五轮反馈（0.16.0 / versionCode 36）

用户八条要求（原文）：

1. 「在私信栏发送消息的时候所有元素都向上抬升到不正常的高度」
2. 「工作中顶部加入一个常用功能，将查用电，缴电费，校园网一键认证，图书馆座位预约，智慧教务这五个功能提到前面，可以一列一个功能」
3. 「工作页面下面的标题与图标不太协调，重新设计一个」
4. （缺号，用户原话直接跳到第六、第七、第八）
5. 「工作页面的图标与图标颜色重复率太高，重新设计一份」
6. 「私信发表情的图标不正常，应该是一个微笑脸，而你设计的图标只有一只眼睛」
7. 「私信有 bug，比如我点入一个群聊，点进群聊中发消息的一个人，点击发私信进入与他的私聊界面，当我返回时第一次进入他的个人主页，第二次返回又进入了和他的私信界面而非群聊界面」
8. 「在私聊与个人主页间切换的动画不连贯有问题」

### 10.1 键盘弹起把整页顶走（`AndroidManifest.xml`）

- 真因：`MainActivity` **从来没有声明 `windowSoftInputMode`**，默认 `adjustUnspecified` 在这个 ROM 上被解析成
  `adjustPan`（`dumpsys window` 里能看到 `sim={adjust=pan forwardNavigation}`）——窗口不 resize，
  系统把整页内容往上推，于是顶栏被推出屏幕、消息列表 bounds 变成 `[0,0][0,0]`、输入条被顶到屏幕中部。
- 修法：`MainActivity` 显式写 `android:windowSoftInputMode="adjustResize"`（`LoginActivity` 本来就有）。
  Compose 侧保持「输入区一层 `imePadding()`」的唯一写法，**不要再给父容器或 Scaffold 叠 insets**，否则会双重抬升。
- 实测（1220×2656）：键盘弹起后顶栏 `2601063` 仍在 y≈143，输入条 y≈1273~1344 紧贴键盘上方，消息列表正常显示。

### 10.2 表情面板与键盘互斥（`im/ImScreens.kt`）

- 面板与键盘**不能同屏**：两个 insets 叠加就是用户说的「抬到不正常的高度」。现在
  - 点表情键：`keyboard?.hide()` → 再展开面板；
  - 点面板里的表情键（已展开）：收起面板 → `keyboard?.show()`（微信那种互相顶替）；
  - **点输入框时面板自动收起**：`WindowInsets.ime.getBottom(LocalDensity.current) > 0` + `LaunchedEffect(imeVisible)`，
    比 `onFocusChanged` 可靠（输入框一直保持焦点时不触发 focus 变化）。注意 `WindowInsets.isImeVisible` 是
    `@ExperimentalLayoutApi`，本轮被编译器拦下，改用 `WindowInsets.ime.getBottom(...)`（需 `import ...layout.ime`）。

### 10.3 工作页顶部「常用功能」（`work/WorkPage.kt`）

- `COMMON_FEATURE_KEYS` 五组名称关键词（`查用电` / `缴电费` / `一键认证`·`校园网` / `座位预约`·`图书馆` / `智慧教务`），
  从**真实目录** `POST /apps/all` 里挑第一个命中的应用 → `WorkCommonCard` 一列一个（图标 36dp + 名称 + ›）。
  挑不到就不显示那一行，**绝不写死假功能**；命中项从下面的分类里摘掉（`sectionsBelow`）避免重复；搜索时整卡让位。
- 实测目录里的真名与用户叫法不完全一致：`座位预约`（图书馆分类内）、`智慧教务（本科生）`——按关键词命中即可。
- **坑**：数据回来时这张卡是插在列表最前面的新 item，LazyColumn 会以「当前首个可见项」为锚，
  新卡片被顶到屏幕外（看起来像没生效）。`LaunchedEffect(loading, common.size)` 里在首次加载完成后
  `scrollToItem(0)` 一次即可（`landed` 标记，只做一次，别和用户滚动打架）。

### 10.4 宫格「标题与图标不协调」

- `GLYPH_SIZE 44dp → 40dp`、名称 `15.6sp/32dp` → **`11sp` / 行高 `14sp` / 两行 `28dp`**（`TILE_LABEL_*` 常量）、
  图与字间距 3→6dp、单元上下内距 5dp、单元圆角 16dp。原来是字比图形抢眼、一列看着头轻脚重。
- 名称仍是两行定高 + 省略号（6 列一格约 62dp，11sp 一行 5 个字），改列数请连这三个常量一起看。

### 10.5 图标颜色重复率太高

- 真因：色相由 `displayName.hashCode() * 137.508f` 决定，hash 一撞就是近似色，一屏里出现好几对同色。
- 改为**按显示顺序**用黄金角铺开：`hueOf() = (18 + index * 137.508) % 360`，同一行 6 个必然两两差 ~137°。
- `hueSlots` 用 `LinkedHashMap` 只记**第一次**出现的位置：同一个应用会在多个分类里重复出现
  （南航要闻 / 掌上学工 / 后勤报修 …），若让后面的位置覆盖，前排两个图标又会拿到相邻色相变回一片同色（本轮踩过）。

### 10.6 表情键的图标只有一只眼（`ui/miuix/LanhangIcons.kt`）

- 真因：旧实现把「两只眼睛 + 弧线嘴」写在**同一条 path** 里，弧线前没有 `moveTo`，Path 会用一条直线把
  上一个点（右眼）连到弧线起点，于是斜线从右眼劈到嘴边——看起来只剩一只眼。
- 现在三条 path：脸 `circle(12,12,8.3)`、双眼各一条 `moveTo → lineTo`（2.1 线宽）、嘴自己起一条
  `moveTo(12+4.4*cos30, 12+4.4*sin30)` 再 `arc(30°→150°)`。**改手绘图标时，弧线/圆`必须是 path 的第一个元素`。**

### 10.7 私信返回栈错乱（`mine/MineFragment.kt`）

- 真因：`stack` 只是 `ArrayList<Int>`（页面枚举），会话页的对端 `imPeerId` 是**全局唯一变量**。
  群聊(A) → 他人主页 → 发私信(B)：`openImChat` 压入第二个 `IM_CHAT` 并把全局 `imPeerId` 改成 B；
  底层那个「群聊会话页」再渲染时读到的是 B，于是返回落到与 B 的私聊。
- 修法：`stack` 升级为 `ArrayList<MineEntry>`，`MineEntry(page, peerId, title, otherId)` 让**每个会话页自带对端**；
  `applyEntry()` 在 `render()`/`goBack()`/`goRoot()` 前把该层的数据装回 `imPeerId` / `otherUserId`；
  `openImChat` / `openOtherProfile` 在「已经在目标页」时替换栈顶而不是再压一层。
  同时 `onSaveInstanceState` 保存整条栈（`KEY_STACK` + 三个 StringArray），进程被杀回来仍能逐级退。
- 实测：群聊 2601063 → 点发送者姓名 → 张敏佳主页 → 发私信 → 返回（主页）→ 返回（**群聊**，不是私聊）。

### 10.8 私聊 ↔ 个人主页过渡不连贯（`ui/miuix/NavPush.kt`）

- 真因：旧写法是「父页面整体淡出 + 子页平移」，父页淡到 0 的过程中露出的是**白底**，看着像白闪；
  进会话页时又是 `animateScrollToItem`，列表从顶部滑下来。
- 现在 `miuixPushTransform(forward)`：新页 `slideInHorizontally { it }`，父页 `slideOutHorizontally { -it/4 }`
  （`NAV_PARENT_PARALLAX = 4`），**两边都不透明、都不淡出**，`DampingRatioNoBouncy / stiffness 146`。
  `NAV_PARENT_FADE_MS` 已删除（改前先 grep 确认没有其他文件引用）。
- 会话页首次进列表改 `scrollToItem`（瞬移），之后的新消息才 `animateScrollToItem`。

### 10.9 版本与验证

- `versionCode 35 → 36`、`versionName 0.15.0 → 0.16.0`，APK 8 350 220 B。
- 实测（emulator-5554）：①点输入框后顶栏/列表/输入条位置正常，表情面板与键盘互斥 ②工作页首屏就是「常用功能」五列
  ③宫格 40dp 图 + 11sp 名 ④相邻图标颜色不再重复 ⑤微笑脸两只眼 ⑥群聊→主页→发私信→返回两级各回正确页面
  ⑦过渡逐帧录制（`artifacts/anim-mine-nav.mp4`）确认无白闪、父子页都参与位移；`adb logcat -b crash -d` 为空。
- **红线不变**：私信/工作圈都是学校线上系统，联调只做「打开 → 截图 → 取消/返回」，**绝不点「发送」「发表」「点赞」**，
  也**绝不调用 `joinGroup`**。

---

## 11. 2026-10-26 第六轮反馈（0.17.0 / versionCode 37）

用户四条 + 一个崩溃：①页面过渡动画「所有元素从右向左滑进来、从左向右退出」，且「顶栏和底部各走各的」；
②私信输入框应紧贴输入法；③移植原蓝航的全部私信功能；④成绩与工作互换位置；⑤修 `MomentsFragment` 的 detach 崩溃。

### 11.1 二级页顶栏与内容同向滑（别退回纯淡切）

- 病根在 `ui/shell/ShellBars.kt` 的 `AnimatedContent.transitionSpec`：**二级页分支原来只有 `fadeIn/fadeOut`**，
  页面（`ui/miuix/NavPush.kt` 的 `miuixPushTransform`）在整幅横移、顶栏原地淡，看起来就是两套动作。
- 现在二级页分支与 `miuixPushTransform` 用同一套参数：`spring(DampingRatioNoBouncy, stiffness = 146f)`，
  方向取 `state.slideDir`（+1 推入 / -1 返回，`showToolbarSub` 按 `pushing` 判定）；
  新顶栏 `slideInHorizontally { it * d }`，旧顶栏 `slideOutHorizontally { -it / 4 * d }` + `fadeOut(110)`。
  **淡出必须留着**：两条顶栏都不透明，旧的不快淡掉就会看到两个标题叠在一起。
- `AnimatedContent` 外面套了 `Box(Modifier.fillMaxWidth().animateContentSize())`：一级大标题（≈96dp）↔
  二级紧凑条（56dp）差 40dp，不补这一层下面的容器会瞬跳。
- 底栏（`LanhangBottomBar`）按用户本轮明确要求**保持静止**，不参与切页位移。

### 11.2 键盘弹起时收起底栏（私信输入框贴住输入法）

- `MainActivity.setupShell()` 给 `binding.root` 装了 `ViewCompat.setOnApplyWindowInsetsListener`：
  `ime.bottom > navigationBars.bottom` 时把 `binding.shellBottomNav` 设为 `GONE`，收起时恢复 `VISIBLE`，
  最后仍要 `ViewCompat.onApplyWindowInsets(view, insets)` 把 insets 继续往下发。
- 底栏原本压在屏幕底部（56dp + 导航条），键盘弹起后它卡在键盘上方，而输入条又被 `imePadding()` 顶一次，
  中间就空 56dp —— 这就是用户看到的「空隙」。底栏 GONE 后 `fragmentContainer` 底边直接落到屏幕底。
- 表情面板 / 附件面板 / 键盘三者互斥：`LaunchedEffect(imeVisible)` 里同时收掉 `emojiOpen` 与 `attachOpen`。

### 11.3 私信五种富消息（图片 / 视频 / 文件 / 语音 / 位置）

- **只用 IMLib 自带的消息类型，没有自造协议**：`ImageMessage`、`SightMessage`（小视频）、`FileMessage`
  （`MediaMessageContent` 的 `setName/setSize` 带文件名与大小）、`VoiceMessage`、`LocationMessage`；
  发送走 `RongIMClient.sendMediaMessage(msg, push, null, ISendMediaMessageCallback)`，位置走
  `sendLocationMessage(...)`，上传由 SDK 用 `features.im.media_urls` 完成。
- 本地支撑全在 `im/ImMedia.kt`（**没有新增任何第三方依赖**）：`copyToCache`（Photo Picker / SAF 的临时授权
  进程一被杀就失效，必须先落地）、`videoInfo`（`MediaMetadataRetriever` 抽第一帧当 `SightMessage` 封面 + 时长）、
  `VoiceRecorder`（`MediaRecorder` AAC/m4a 16k 单声道，**不足 1 秒视为取消**）、`Player`（同一时间只播一条，
  `playingKey` 是 Compose 状态）、`openFile`（远端先下载到 `cache/im`，再经 `FileProvider` 出 `content://`，
  Android 7+ 直接 `file://` 会 `FileUriExposedException`）、`openLocation`（高德/百度/geo 依次尝试）。
- 清单新增 `RECORD_AUDIO`、`ACCESS_COARSE/FINE_LOCATION` 与 `androidx.core.content.FileProvider`
  （`${applicationId}.fileprovider` + `res/xml/file_paths.xml`）。定位只用 `getLastKnownLocation`，
  取不到就提示「暂时取不到当前位置」，**不编造坐标**。
- UI：输入条左侧手绘加号（`LanhangIcons.Plus`）开「+」面板（图片/视频/文件/位置四格），右侧按钮在草稿为空时
  显示「语音」、语音模式下变「键盘」；按住说话用 `detectTapGestures(onPress = { tryAwaitRelease() })`。
  气泡按 `ImMessage.kind` 分支（图片自带圆角不套内边距），会话列表 `previewOf` 也认得这五种。
- **通话仍然没做**（需要 CallLib + RTCLib，用户同意下一轮），官方客户端发来的通话记录在 IMLib 里解成
  `io.rong.imlib.model.UnknownMessage`，气泡如实显示「[暂不支持的消息]」（类名打在 logcat `LanhangIM`）。

### 11.4 成绩 ↔ 工作互换

- `ShellState.TAB_GRADES` → **`TAB_WORK`（仍是 1）**；`work/WorkFragment.kt` 作为一级 Tab 宿主，
  `MainActivity` 的 `newFragment/tagFor/cache/titleForTab` 全套跟着改名（`TAG_WORK` / `workFragment`）。
- 成绩进「我的」：`MinePage.WORK` → **`MinePage.GRADES`（仍是 1）**，`MineScreen` 里渲染 `GradesScreen()`，
  根页入口标题用 `R.string.tab_grades` + `mine_grades_summary`；`grades/GradesFragment.kt` 已删除。
- `GradesScreen` 的滚动记账从 `TAB_GRADES` 改指 `TAB_MINE`（它现在就是「我的」这个 Tab 里的内容），
  双击底栏「我的」也会把成绩列表滚回顶部。
- 底栏第二项图标 `MiuixIcons.Notes` → `MiuixIcons.Tasks`，文案 `R.string.tab_work`（工作 / Work）。

### 11.5 崩溃：协程里禁止 `requireContext()`

- 用户报的 `java.lang.IllegalStateException: Fragment MomentsFragment{…} not attached to a context`
  落在 `MomentsFragment$load$1.invokeSuspend`：`viewLifecycleOwner.lifecycleScope` 的协程在 Fragment detach
  之后（切 tab / 销毁视图）继续跑完 `withContext(Dispatchers.IO)`，`catch` 里再调 `requireContext()` 就炸；
  被当成 `CancellationException` 之外的普通异常抛出去，直接杀进程。
- **规矩**：`net/session` 之外只要在协程里，一律用 `com.lanhang.miuix.schedule.AppContext.instance`
  （Application context，永远有效），`getString` 也改成 `AppContext.instance.getString(...)`；
  `toast()` 在 `context == null` 时退到 `AppContext.instance`。本轮改了工作圈 6 处 + 「我的」14 处。
- 组合期（`setContent {}` 里）仍然禁止 `requireContext()`（三次改版第 3 条），两件事别混。

### 11.6 版本与验证

- `versionCode 36 → 37`、`versionName 0.16.0 → 0.17.0`，APK 8 375 356 B；`clean assembleRelease` 后安装。
- 实测（emulator-5554）：①底栏四项 = 课表/工作/工作圈/我的，工作 Tab 首屏「常用功能 + 办事大厅」，
  我的里第一项是「成绩」（点进去返回箭头 + 成绩学期卡）②私信会话页输入条左侧出现「+」，面板四格齐全，
  图片/视频打开系统 Photo Picker、文件打开 SAF，点输入框后键盘弹起 → 底栏消失、输入条紧贴键盘、面板自动收起
  ③语音首次点击弹录音权限；切成「按住 说话」后轻点不会发出消息（<1 秒按取消处理）④顶栏动效逐帧录制
  （`artifacts/screenshots/v37-09/10`）确认顶栏与内容同向参与位移 ⑤切 tab / 进出二级页 / 群聊往返均无崩溃，
  `adb logcat -b crash -d` 为空。
- **红线不变**：联调只做「打开 → 截图 → 返回」，不点「发送/发表/点赞」，不调 `joinGroup`，不删评论。

## 12. 2026-10-26 第七轮反馈（0.18.0 / versionCode 38）

用户四条：①「动画依旧是顶栏向右、底部向左，还会卡顿」②「第一次点进私信严重卡顿，姓名与群聊名也没法正常显示，
如果刷新速度跟不上触发速度可以先加载缓存，连上再刷新」③「工作页『最近使用』『教学办公』等标题与页面不太协调」
④「不需要 push 到 github，以后不再向原项目提交 PR，原项目与该项目已彻底分离」。

### 12.1 顶栏方向为什么还是错（本轮真正的病根）

第六轮把二级页的顶栏换成了和内容同一套弹簧，方向本身是对的。错的是**另一次没人注意的过渡**：

`MainActivity.showToolbarSub()` 在调用方没传 `slideDir` 时会自己猜方向（`if (pushing) 1 else -1`），
其中 `pushing` 的判据是「当前顶栏有没有返回箭头」。而**同一页里只换标题**时（私信打开后对方姓名从
`POST /staffs/batch` 回来、群名从 `GET /groups/{id}` 回来，`MineFragment.syncToolbar()` 会再调一次），
返回箭头早就在了 → `pushing = false` → 猜成 **-1（从左侧推入 = 往右滑）**；但页面栈一动不动，
内容侧没有任何位移。这就是「顶栏向右、底部不动/向左」的观感，也是那次「卡顿」（同一条顶栏连着跑两次转场）。

修法两条：

- `showToolbarSub()`：没给方向且仍在二级页 → `slideDir = 0`（不再猜 -1）；
- `ShellBars`：`dir == 0` 且非 Tab 切换 → 只做 150ms 交叉淡入，**不许横移**。

判据汇总：`+1` 推入、`-1` 返回、`0` 同页换标题（淡入淡出）。新增方向时别再把「没传」当成「返回」。

### 12.2 卡顿的第二来源：高度过渡用弹簧

`animateContentSize()` 默认是弹簧，大标题（≈96dp）↔ 紧凑条（56dp）来回振荡几百毫秒，
每帧都要重新测量整条 `shellTopBar` 的 ComposeView，下面的 Fragment 容器跟着重排。
改成 `animateContentSize(animationSpec = tween(BAR_HEIGHT_MS=180, FastOutSlowInEasing))`，一次到底。
**这条也是「所有页面切换都卡」的主要来源，不要改回默认弹簧。**

### 12.3 私信首屏：先铺缓存，连上再刷新

进私信时原本什么都是空的，要等 connect → 会话列表 → `POST /staffs/batch` → `GET /groups/{id}`
这条链走完才有内容。新增 `im/ImCache.kt`：

| 文件（`filesDir/im_cache/`） | 内容 |
|---|---|
| `conversations.json` | 会话列表（对端/群 id、预览文案、时间、未读） |
| `msg_<peer>.json` | 每个会话最近 60 条（信封里带 peerId，启动时能反查） |
| `group_titles.json` | 群 id → 真群名 |

- `ImRepository.ensureStarted()` 第一步就是 `primeCache()`：后台读三个文件 → `withContext(Dispatchers.Main)`
  填空（已有数据不覆盖）→ 再走原来的连接流程；连接成功后 `refreshConversations()` / `loadMessages()`
  把最新结果覆盖回来并落盘。
- 落盘时机：拉历史、发消息（文本 + 富消息回显）、发送状态变化（`mark`）、会话列表刷新、群名拿到。
- `release()`（退出登录）会 `deleteRecursively` 整个 `im_cache`，换账号看不到上一个人的聊天。

### 12.4 姓名一直是「匿名」的真因

`UserInfoCache` 原来是纯内存 `ConcurrentHashMap`，改完不触发重组：名字回来了，
列表却要等 20 秒轮询才重绘。现在：

- 加 `revision: Int`（Compose 状态，`merge()` 里 +1），`ConversationRow` / 气泡发送者各读一次
  → 批量接口一回来立刻重绘；
- 资料写盘到 `filesDir/staff_cache.json`（600ms 去抖，避免批量解析写几百次），`preload()` 在后台线程读回；
- `preload()` 读回来的人**不写进 `fetched`**，这样连上以后仍会去批量接口拿最新资料。

### 12.5 工作页分组标题

`title4`（20sp 加粗）+ 卡片内 6dp 左内距 + 卡片外只有 8dp —— 又大又贴边，和 40dp 图标 / 11sp 名称不成层级。
现在抽成 `WorkSectionLabel`：**放在卡片外面**、`body2`、`onSurfaceVariantSummary`、
左内距 28dp（= 卡片 12dp 外边距 + 16dp 内容内距），上下 12/6dp；卡片外边距统一 12dp。
「常用功能」用同一个标签，两处不再各写一套字号。

### 12.6 与上游彻底分离

用户明确「以后不再向原项目提交 PR」。本轮起：

- 不再生成 / 更新 `lanhang-miuix/pr/` 里的 PR 包（`repo/`、`0001-*.patch`、`miuix-restyle.diff`、`src.zip`、PR.md），
  里面已有内容是 0.17.0 那版的历史归档，保留但不再维护；
- fork 仓库不再往 `upstream` 推；本地 `pr/lanhang-lite-miuix` 已删掉 `upstream` 远端；
- 版本号、`VERSION.md`、本文件继续照常维护（给自己看）。

### 12.7 新硬规则：落盘数据一律手写 JSON，不许 Gson 反射

本轮上线后连续踩了两次崩溃，都是**混淆 + Gson 反射**：

1. `ClassCastException: LinkedTreeMap cannot be cast to ImMessage`（release 里是 `q7.n → f8.l`）。
   根因：给 `gson.fromJson(...)` 传 `Class` / 被 R8 删掉泛型签名的 `TypeToken` 时，`List<ImMessage>`
   的类型参数丢了，每条消息反成 `LinkedTreeMap`，LazyColumn 一读字段就炸。
2. 改成 `TypeToken` 之后又崩：`NullPointerException: Parameter specified as non-null is null:
   method kotlin.text.StringsKt.…`。根因更阴——**Gson 是按字段名写盘的，R8 每次构建都会重命名字段**，
   上一次构建留下的 `staff_cache.json` / `conversations.json` 到新包就认不出键名，`String` 字段反成 null。

结论（**硬规则**）：凡是要写到 `filesDir` 再读回来的结构，一律用 `JsonObject` / `JsonArray` **逐字段手写映射**
（`ImCache.encodeMessages/decodeMessages`、`saveConversations/loadConversations`、`UserInfoCache.encode/decode`），
键名是固定的字面量。Gson 只用于「网络响应 → 模型」这种一次性、同进程的解析。已有旧缓存读不出来就当作空，
不要为了兼容旧文件再引回反射。

其他本轮踩到的坑：
- 混淆包的堆栈先用 `app/build/outputs/mapping/release/mapping.txt` 反查类名（`mapping.txt` 每次构建都重生成，
  **必须用崩的那次构建的 mapping**，用旧的会指向完全不相干的类）；实在看不懂就临时把 release 的
  `isMinifyEnabled/isShrinkResources` 关掉重编一版拿真堆栈——签名配置在没给正式 keystore 时回落到 debug，
  所以 `pm install -r` 能覆盖安装、**登录态不丢**，验完记得改回来。
- Compose 的 snapshot 状态（`UserInfoCache.revision`）只能主线程写，后台回调里改要用 `Handler(mainLooper).post`。

### 12.8 版本与验证

- `versionCode 37 → 38`、`versionName 0.17.0 → 0.18.0`；`clean assembleRelease` 后 `pm install -r` 安装
  （**装包前千万别 `pm uninstall`**：本轮就这么把用户登录态清了，只能让用户重新登一次）。
- 实测（emulator-5554）：①我的 → 私信 → 会话页，顶栏与内容同向、只跑一次过渡，`logcat` 无
  `Skipped N frames` ②强杀重进 → 点私信：`cache primed: conv=2 threads=1`，1.2s 截图里会话列表与
  聊天记录都已铺满，姓名/群名直接是「张敏佳」「2601063」（不再是「匿名」）③连开三轮会话页 + 群聊往返，
  `logcat -b crash` 为空 ④工作页「常用功能/最近使用/教学办公」标签改为卡片外 `body2` 次要色。
- **红线不变**：只做「打开 → 截图 → 返回」，不点发送/发表/点赞，不 `joinGroup`。

## 13. 2026-10-27 第九轮之后合并反馈（0.22.4 → 0.22.5 / versionCode 49 → 50）

### 13.1 通讯录：搜索三通道 + 返回只退一级

- **接口**：`RceApi.searchContacts(keyword)` 统一入口——`POST /staffs/search`
  （`{"keywords":[...],"with_orgs":"0","type":1}`）同时命中**姓名**和**学号/工号**；
  关键词是 11 位纯数字时再补 `POST /user/search/mobile`（只认手机号），两批按 `id` 去重合并。
- **学号补齐**：`/user/search/mobile` 只回 `id/name/mobile`，没有 `staff_no`。
  合并后把「缺学号」的 id 收成一批，一次 `POST /staffs/batch` 补回来（别一个人一个请求）。
- **返回**：搜索框有内容时 `BackHandler` 先走 `ContactsUiState.backOnce()`——清关键词、留在通讯录原页；
  `MineFragment` 的顶栏返回箭头同样先问 `backOnce()`，它是 false 才真的退出这一页。
- **成员行/搜索命中行**：统一 `学号 · 身份 · 手机号`（`StaffSearchHit` 新增 `mobile` 字段），
  取不到的项自动省掉；成员卡里也是这三行。

### 13.2 课表自适应（短屏机器最左侧时间被切）

- `ScheduleFragment` 全部改成按实测文字宽度排：`textPx("08:45", 8f)` 量出时间列，
  `timeW` 夹在 26–52dp；周次列 `colW = (可用宽度 − timeW − 7×gapW − 1dp) / 7`；间隙 4/3/2dp 三档。
- 时间格字号 13/12/11sp、行距 8/7.5/7sp、内边距 2/1/0dp 三级降档，`includeFontPadding = false`。
- 实测 1080×1920@480（360dp）与 960×1600@480（320dp）下 `08:00/08:45 … 20:50/21:35` 全部完整可见，
  节次列与周次列都不再互相挤。

### 13.3 深色强调色（Miuix 深色那套发闷）

- `ui/miuix/LanhangMiuix.kt` 里 `darkColorScheme(...)` 用**具名参数**覆盖（Miuix 的 `Colors` 没有 `copy()`，
  49 个 `long` 位置参数走 `$default` 桥接）：`primary #4C8DFF`、`primaryVariant #5C9BFF`、
  `primaryContainer #2A4E8F`、`onPrimaryContainer #D8E6FF`，`onPrimary` 仍保持白色（白字压在亮蓝底上 3.3:1，
  15sp semibold 够看，比 MD3 那套「亮底 + 深字」在 Miuix 组件里更稳）。
- 新增三个小工具：`Color.liftForDark(amount)`（朝白插值）、`isDarkTheme()`（按背景亮度判断，比
  `isSystemInDarkTheme` 靠得住）、`accentColor(base)`（深色自动提亮）。
- 落实处：我的页 8 个图标色（`MineScreen` 的 `MineRow`）、成绩分数色（`scoreColor(score, night)`）、
  课表「今天」强调色（`ScheduleStyle.accentOf(context, night)`）；座位图的选中色本来就吃 `colorScheme.primary`，跟着变。

### 13.4 工作页图标按学校原图标重绘

- `ui/miuix/SchoolIcons.kt` 全部是 24×24 viewport、`strokeLineWidth 1.9f`、圆角端点的描边图标。
  本轮重画：`Teach`（教案本 + 三条表单线，去掉竖分隔）、`BoltYuan`（放大闪电 + ¥）、`Wrench`（开口 C 形头 + 手柄）、
  `Mailbox`（信封 + 立柱 + 底座）、`HandYuan`（¥ 硬币）、`News`（正文三行 + 右侧配图）、`Sliders`（机箱 + 滑杆）、
  `Seat`（靠背 + 坐板 + 两条腿），新增 `Flask`（实验室安全，原图是显微镜，20dp 下烧瓶更清楚）与
  `FaceCamera`（门禁照片更新 = 相机 + 镜头）。
- `WorkPage.glyphId()` 规则微调：`门禁照片/照片更新/人脸采集 → faceCamera`、`宿舍门禁/人脸门禁 → faceGate`、
  `实验 → flask`、`仪器/机房/设备 → sliders`、`摊点/活动/布置/宣传品 → flag`（这条挪到新闻前面，
  免得「宣传品布置申请」被 `宣传` 抢走）；`出差/证书/申请/审批` 统一 `clipboard`（原来落到 `wallet`，和银行卡撞脸）。
- **去重策略改了**：`SCHOOL_GLYPH_IDS` 里的学校图标**允许同义复用**（新闻稿件报审 vs 南航要闻、两个门禁申请），
  色相按顺序错开；Miuix 备用图标仍然保持全页不重复——这样既不会出现「一堆随机图标」，也不会把同义应用硬拆成两个图标。

### 13.5 验证与红线

- `assembleRelease`（R8 开启）→ `pm install -r` → 逐项截图：通讯录按学号搜（`260106336` → 焦普田）、
  按手机号搜（`17507095317` → 同一人且学号已补上）、搜索态按两次返回落回通讯录原页；
  课表 360dp / 320dp 时间列完整；深色下我的页图标、课表「今天」、工作圈 FAB 都是提亮后的蓝；
  工作页 50 个应用逐个看过图标辨识度。
- 临时探针（`RceApi.probeRaw/probePost/probePostLocal`、`ContactsScreen` 里那段 `RceProbe` `LaunchedEffect`）
  **本轮已全部删除**——发版前别留 dump 代码。
- 红线不变：只做「打开 → 截图 → 返回」，不点发送/发表/点赞，不 `joinGroup`，不 `pm uninstall`。
