## 74. 第七十四轮（2026-11-14）：私信时间/视频预览/语音/返回键/支付拉起

### 74.1 用户原话（八条）

> 第一，修改密码、修改手机号两个功能全部失效，一直提示身份鉴权失败。第二，校园网登录功能异常，一直提示无法连接认证服务器。第三，私信发送的消息需要显示日期-时间，而非只显示发送时间。第四，私信中发送视频或图片时，应该添加一个预览功能直接预览发送的图片与视频，具体动效参考工作圈中打开图片的效果。第五，语音无法正常发送，一直显示发送失败，另外，播放语音时进度条没有随播放进度而变化。第六，赣青二课页面点击我的活动后无法正常加载内容。第七，在 web 页面内，触发返回手势就是返回页面中的上一级，点击屏幕右上方的返回键则直接退出整个 web 页面。第八，缴电费界面充值无法拉起微信/支付宝，修复这些问题。

随后补充：「现在的网络环境是校园网，理论上所有功能都能正常使用，做好之后 push 到 github」。

### 74.2 第三条：消息时间要带日期

`im/ImScreens.kt` 新增 `messageTime()`，`BubbleBody` 下方那行从 `clockTime()`（只有 `HH:mm`）换成它：

- 当天 → `HH:mm`（不啰嗦）
- 昨天 → `昨天 HH:mm`
- 今年 → `MM-dd HH:mm`
- 跨年 → `yyyy-MM-dd HH:mm`

`clockTime()` 保留不动，会话列表的 `shortTime()` 还在用。

### 74.3 第四条：视频预览（对齐工作圈图片那套动效）

新建 `moments/VideoPreviewActivity.kt`（约 300 行，纯代码搭 UI，不写 XML）：

- 根布局复用现成的 `DragDismissLayout`（工作圈图片预览的下拉关闭容器），黑底全屏；
- 进入：`stage` 0.92→1.0 缩放 + 根布局 alpha 0→1，路径 `PathInterpolator(0.4f, 0f, 0.2f, 1f)`；
- 下拉：画面跟手缩小到 0.72、下移、背景变透明；松手超过 110dp 或速度 >1200 关闭，否则回弹；
- 关闭：顺着当前拖动量再缩到 0.86 + 淡出后 `finish()`（不会「先弹回原样再消失」）；
- 视频用 `VideoView`，`setOnPreparedListener` 里按 `videoWidth/videoHeight` 算比例铺满，避免竖屏视频被拉扁；
- 右上 ✕ 关闭、底部「用其他应用打开」兜底，`setOnErrorListener` 播不了时自动转系统播放器；
- `AndroidManifest.xml` 注册，主题沿用 `Theme.LanhangLite.ImagePreview`。

`ImScreens.kt` 视频气泡的点击从 `ImMedia.openFile(...)`（`ACTION_VIEW` 丢给系统）改成
`VideoPreviewActivity.start(context, message.uri)`。图片那条本来就走 `ImagePreviewActivity`，未动。

### 74.4 第五条：语音发不出去 + 进度条不动

**发不出去**：`im/ImMedia.kt` 的 `VoiceRecorder` 原来固定录 `MPEG_4/AAC`（`.m4a`）。
融云的语音消息默认按 AMR 走，m4a 在部分链路会被上传端直接拒收 —— 症状就是「语音必失败，
而文字/图片正常」。改成**优先 AMR_NB**（8kHz、单声道，即融云推荐那套），失败再回退 AAC/m4a；
抽成 `startWith(fmt, enc, ext, sampleRate, bitRate)`，`bitRate = 0` 表示不显式设置
（AMR-NB 设了反而可能抛异常）。

**进度条不动**：`Player` 原来只有 `playingKey`，没有任何位置信息。新增
`var progress by mutableStateOf(0f)` + 主线程 `Handler` 每 80ms 采样
`currentPosition / duration`，`toggle()` 起播时 `handler.post(tick)`、`stop()` 里移除。
气泡那条原来是静态灰条，现在叠一层按 `progress` 增长的白色前景（`fillMaxWidth(frac)`）。

### 74.5 第七条：返回手势 / 右上返回键语义分离

`ui/OaWebViewActivity.kt` 顶栏的 `IconButton` 从
`onBackPressedDispatcher.onBackPressed()`（= 退一级网页）改成 `finishNow()`（= 直接退出整页）。
系统返回手势那条 `OnBackPressedCallback → goBackOrFinish()` 保持不变。
（第 72 轮用户要求「两个都退一级」，本轮改成「手势退一级、按钮退出」，二者不再共用一条路径。）

### 74.6 第八条：微信/支付宝拉不起来 —— Android 11+ 包可见性

`AndroidManifest.xml` 原来**没有 `<queries>`**。Android 11（API 30）起有包可见性限制：
不声明的话系统根本不告诉我们设备上装了微信/支付宝，`startActivity(ACTION_VIEW, weixin://…)`
必然抛 `ActivityNotFoundException`，被 `runCatching` 吞掉之后用户看到的就是「点了没反应」。

两处修：

1. 清单里补 `<queries>`：`com.tencent.mm`、`com.eg.android.AlipayGphone`、`com.unionpay.uppay`
   三个包，外加 `weixin` / `weixinpay` / `alipays` / `alipay` / `tencent` / `mqq` / `uppay`
   七个 scheme 的 `VIEW` intent，以及 `mailto`（SENDTO）、`tel`（DIAL）。
2. `OaWebViewActivity` 新增 `openExternal(raw, uri)`，替换原来那句静默的 `runCatching`：
   先按 `intent://` 用 `Intent.parseUri(..., URI_INTENT_SCHEME)` 解包（支付跳转常见这种打包形式），
   解出来还带 `browser_fallback_url` 就用它兜底；再退到普通 `ACTION_VIEW`；全都失败才
   Toast「没有找到能打开它的应用」。**绝不再静默失败。**

### 74.7 第一条：校内 cookie 要跨子域（推测性修复，待真机确认）

`OaWebViewActivity` 写 cookie 时，`ApiClient.allCookies()` 收上来的票大多是 **host-only**
（只在 `nginx-1.app.nchu.edu.cn` 下有效）。而学校 SSO 摊在多个子域：`nginx-1.app.*` 发票、
`go.nchu.edu.cn` 才是改密码（`?id=79`）与改手机号（`?id=83`）的页面。跳过去时一个票都带不上，
页面于是报「身份鉴权失败」。

修法：`domain.endsWith("nchu.edu.cn")` 时把这些票再补写一份到 `nchu.edu.cn` 根域与
`go.nchu.edu.cn`。只在校内域生效，不外溢到公网站点。

### 74.8 第二、六条：模拟器验不了，需真机信息

模拟器走 QEMU NAT（`10.0.2.15`，网关 `10.0.2.2`），**不在校园网内网**：

- `ping 10.1.88.4` 100% 丢包（深澜门户不可达）→ 第二条无从复现；
- `ping gqhw.jxmediacenter.com` unknown host（模拟器 DNS 未继承宿主），
  `adb root` 报 `adbd cannot run as root in production builds`，改不了 DNS → 第六条无从复现。

宿主本身是通的（`Test-NetConnection 10.1.88.4:80 = True`、`gqhw` 解析到 `36.133.146.115`），
所以这两条要么在真机上验，要么需要用户提供页面上的具体错误文案/错误码。

### 74.9 产物

- `versionCode 76` / `versionName "1.1.24"`
- APK **8,708,624 字节 / SHA256 `BD4DCE91885521CADF4D0BEC03AF1037DD47C7393ABEF2001D7F875112117B85`**
- `strings.xml` 56,476 → 56,788 字节（新增 `im_video_open_other` / `im_video_play_failed` / `oa_open_app_failed`）
- `AndroidManifest.xml` 5,763 → 8,889 字节（`<queries>` + `VideoPreviewActivity`）

### 74.10 三条教训

1. **备份文件不能放进 `res/`**。给 `res/values/strings.xml` 做 `Copy-Item *.bak74c` 之后，
   `mergeReleaseResources` 报 `The file name must end with .xml` —— 资源合并会把 values 目录下
   所有文件都当资源。备份一律放 `scratch/bak74/`。
2. **「点了没反应」多半是异常被 `runCatching` 吞掉**。第八条表面看是支付 SDK 问题，
   实际是 Android 11+ 包可见性导致 `ActivityNotFoundException` 被静默吞掉。凡是给用户
   触发的跳转包 `runCatching`，都要在 `onFailure` 里留一句可见提示。
3. **模拟器验不了的不要硬撑**。QEMU NAT 出不了校园网内网，DNS 也改不了（无法 root）。
   与其在模拟器上反复试，不如把宿主侧的证据（`Test-NetConnection`、`Resolve-DnsName`）
   写清楚，明确告诉用户「这条需要真机」，并把代码层的确定性缺陷该修的修掉。
## 73. 第七十三轮（2026-11-14）：私信图标、媒体发送修复、账号与安全 + 权限控制

### 73.1 用户原话
「第一，私信无法发送语音、文件、视频，另外，私信+号拓展页面图片、视频、文件、位置与私信主页的语音按钮缺少图标，可以试着链接网络矢量图标库获取图标。获取图标素材后换掉应用内全部能替换的图标，让图标更美观。第二，在设置-账号功能下加入修改密码、修改手机号、权限控制的功能，具体细节可以参考原蓝航app，权限控制比如谁能看到手机号，谁能看到邮箱等等功能，将原蓝航的功能搬过来。做完后push发版」

### 73.2 图标（`im/ImScreens.kt` 63612 → 66094 字节）
`AttachPanel` 原来把「图 / 视 / 文 / 位」四个汉字用 `Text(label.take(1))` 当图标。改为 `top.yukonga.miuix.kmp.icon.extended` 的真图标：`Image` / `Play` / `File` / `Location`，46dp 圆角底框内 `Icon(24dp, tint = primary)`。
输入条右侧三个纯文字 `TextButton` 换成新增的 `IconPill(text, icon, primary, onClick)`（38dp 高、19dp 圆角）：语音 `Mic`、发送 `Send`（实心主色）、键盘 `LanhangIcons.Backspace`。

**坑 1（务必记住）**：`top.yukonga.miuix.kmp.icon.extended.Xxx` 是**扩展属性** `val MiuixIcons.Xxx: ImageVector`，不能 `import ... as 别名` 当顶层值用，否则报 `receiver type mismatch`。正确写法是 `import top.yukonga.miuix.kmp.icon.MiuixIcons` + `import top.yukonga.miuix.kmp.icon.extended.Mic`，使用处写 `MiuixIcons.Mic`。现成样板见 `work/WorkPage.kt`（已 import 100 余个）。

**坑 2**：`@Composable` 的 `stringResource(...)` 不能出现在普通 lambda（如 `onClick`）里，必须在 composable 作用域先取成局部 `val`。
**坑 3**：批量 `Replace` 时如果替换目标包含定义行本身（`val tHead = stringResource(...)` → `val tHead`），会把自己改成自引用 `val tHead = tHead`，编译报 `Unresolved reference`。替换要挑唯一的调用形态。

### 73.3 媒体消息「发不出去」的真实原因（重要）
用户反馈语音 / 文件 / 视频发不出去。加了临时 `Log.e` 诊断后拿到的链路是**通的**：
`filePicker picked` → `copyToCache 成功 len=N` → `filePicker prepared` → `sendRich status=CONNECTED content=FileMessage` → `onProgress 0..100` → `onSuccess`。
真正的问题是**偶发失败**：同一个文件**发第二次必定失败**。因为 `ImMedia.copyToCache` 直接沿用原文件名写入 `cache/im/<原文件名>`，第二次把第一次的缓存文件覆盖掉，而融云 SDK 可能仍持有旧句柄。

修法：`copyToCache` 一律加时间戳前缀 —— `val raw = fileName ?: queryName(...) ?: "im_"; val name = System.currentTimeMillis().toString() + "_" + raw.replace('/', '_')`。改完实测「同一个 150KB 文件连发两次」两条都成功。

视频另有一处会「点了没反应」：`videoInfo()` 的 `catch` 直接 `return null to 0`，调用处看到 `durationSec <= 0` 就提示失败并 `return`。SightMessage 必须要封面，所以有些视频永远发不出去。修法：抽帧失败时生成一张 320×320 深灰占位 jpg 当封面，时长兜底为 1 秒 —— 宁可时长显示不准，也让视频能发出去。

**教训**：用户说「发不出去」不等于代码没走到。先打点确认走到哪一步，再谈修 —— 这次打点之后才发现代码是通的、失败是「同名缓存覆盖」这种数据层面的偶发问题，光读代码看不出来。

### 73.4 账号与安全 / 权限控制（对照原版蓝航）
原版三个入口（`SettingActivity.java`）：
- `liv_setting_password` → `RceWebViewActivity` + `https://go.nchu.edu.cn/?id=79`
- `liv_setting_mobile` → `https://go.nchu.edu.cn/?id=83`
- `rce_permission_control` → `PermissionControlActivity`（原生页）

原版权限接口（`PermissionControlActivity.java`）：`GET /api/user/getUserStatus` 读、`POST /api/user/setUserStatus` 写，body 九个值**全是字符串数字**：`telFlag` / `mailFlag` / `messageFlag` / `groupFlag` / `pinFlag` / `friendFlag` / `headFlag` / `hideTelFlag` / `hideOfficeFlag`。语义：头像 / 加好友 / 群 / 置顶 / 语音视频是**三态**（0 全部可见、1 仅老师、2 都不可见），手机号 / 邮箱是**两态**（0 全部、1 仅老师）。

新增：
- `engine/net/UserPrivacyModels.kt`：`UserPrivacy` 数据类 + `toJsonBody()` + 三个 `VISIBLE_*` 常量。
- `engine/net/RceApi.kt` 加 `getUserPrivacy()` / `setUserPrivacy()`。**该文件是 CRLF 换行**，锚点不要归一化成 LF。
- `mine/AccountSecurityScreen.kt`：账号（修改密码、修改手机号走 `OaWebViewActivity.open`）+ 隐私（权限控制）。
- `mine/PrivacyControlScreen.kt`：三组卡片 + `showMiuixPicker` 单选 + 底部保存。
- `MineScreen` / `MineFragment` 加 `ACCOUNT_SECURITY = 18` / `PRIVACY_CONTROL = 19` 接线；`strings.xml` 加 33 个 `account_*` / `privacy_*` 键。

改 engine 后必须 `:engine:assembleRelease` 再把 `engine-release.aar` 复制成 `app/libs/engine.aar`，否则 app 用的还是旧 aar。

### 73.5 产物与验证
版本 `versionCode 75` / `versionName "1.1.23"`。
装机实测（模拟器 1220×2656）：工作 tab → 赣青二课正常；我的 → 设置 → 账号与安全三行齐全；权限控制页拉到了真实数据（头像=仅老师可见、加我为好友=所有人可见、我的群/置顶会话/语音和视频=仅老师可见、手机号/邮箱=仅老师可见），点「头像」弹出三选一且当前值正确打勾；私信附件面板四个矢量图标与输入条麦克风胶囊正常；同一个 150KB 文件连发两次均成功。

### 73.6 教训
1. 用户报「功能坏了」先打点定位，别先读代码猜 —— 这次代码是通的，坏的是数据（同名缓存）。
2. 凡是用原文件名落盘的地方都要问一句「同一个文件再来一次会怎样」，共享目录 + 原文件名 = 覆盖 = 偶发失败。
3. 组件图标的 `extended` 包全是扩展属性，只能 `MiuixIcons.Xxx` 访问；批量替换字符串时注意别把定义行本身替换成自引用。
## 72. 第七十二轮（2026-11-14）：二课网页的空白提示、登录态与返回逻辑

### 72.1 用户原话

> 在页面中点击"我的"时，屏幕上方出现"页面可能是空白的"的提示，修复这个问题，另外，需要让内部网页记住赣青二课的登录态，省去反复登录的过程。第三，页面左上角的返回按钮是"返回上一级页面"，如在网页中点击了一个二级页面，点击返回按钮后可以回到上一个界面。而系统中的返回手势触发时则直接退出所有页面。修改一下交互逻辑

### 72.2 三条问题的根因与修法

**① 「页面可能是空白的」误报。** `ui/OaWebViewActivity.kt` 的 `onPageFinished` 里判定白板写的是
`pageTrouble = height <= 2` —— 只看 `document.body.getBoundingClientRect().height`。
uni-app / Vue 那类把内容放进 `fixed` / `absolute` 容器的页面（赣青二课的「我的」正是），
`body` 自身高度恒为 0，于是正文好好画着也被判成白板，顶上就压出一条
「页面可能是空白的 / 重试 / 用浏览器打开」。
修法：改成 `pageTrouble = bits <= 2 && height <= 2` —— **既没有正文、也没有高度**才算白板。
`bits` 本来就是脚本一起量回来的（正文长度 + 图片数 + canvas 数），只是以前没用上。

**② 登录态记不住。** Android 的 `CookieManager` 只把**带 Expires 的持久 Cookie** 写进磁盘，
会话 Cookie 只活在内存里，本页一 `finish()` 或进程被回收一次就没了。赣青二课这类若依 + uni-app
的站点，登录票据恰恰常常是会话 Cookie，表现出来就是「每次进去都要重新登录」。
修法：新增 `ui/WebLoginStore.kt`（2,956 字节，`internal object`）——
`save(context, host)` 把该域名此刻的 `CookieManager.getCookie("https://host")` 整串抄进
SharedPreferences（键 = 域名，附时间戳）；`restore(context, host)` 在下次建 WebView 时逐条
`setCookie` 写回并 `flush()`，超过 30 天的旧快照直接丢弃。
`OaWebViewActivity` 里三处接线：`createWebView` 的 `cm.flush()` 之后对**非校内域名**调 `restore`；
`onPageFinished` 里量完正文后调 `save`；新增 `onPause` 再存一次（有些站点是登录成功后才写 Cookie，
页面并不重载）。**只处理校外域名**：学校域名的 Cookie 由 `ApiClient` 统一同步，不能被旧快照覆盖回去。

**③ 系统返回直接退出整个网页。** 两个原因叠在一起：
- `AndroidManifest.xml` 里没有 `android:enableOnBackInvokedCallback`，targetSdk 35（Android 15）
  默认启用预测性返回，系统手势走的是 `OnBackInvokedDispatcher` 而不是
  `OnBackPressedDispatcher` —— 我们注册的 `OnBackPressedCallback` 收不到，Activity 直接被 finish。
  顶栏返回按钮是显式 `onBackPressedDispatcher.onBackPressed()`，所以它能用。这就是「按钮好使、
  手势直接退光」的由来。修法：`<application>` 上显式写
  `android:enableOnBackInvokedCallback="false"`，回到旧路径，两者统一走 `goBackOrFinish()`。
- 单页应用的二级页根本没进栈。`doUpdateVisitedHistory` 原来对任何新地址都「替换栈顶」
  （那是为了处理 302 重定向回原页的中间页），而 uni-app 的 hash 路由只改 `#` 后面那段，
  于是 `pageStack` 永远只有一条，栈底判定立刻成立 → 直接退出。
  修法：加一段「同主机、同路径、**只有 `#` 片段不同** = SPA 前进」的判断，命中就 `pageStack.add`
  而不是替换；其余情况照旧替换。

### 72.3 验证（模拟器 1220×2656，density 2.925）

- 工作 tab →「赣青二课」→ 首页正常渲染（学生数 1538642 / 活动数 233711）。
- 点底部「我的」→ 进入登录页（HELLO，欢迎登录 / 选择学校 / 账号 / 密码 / 验证码 / 记住密码 / 登录），
  **没有出现「页面可能是空白的」**。截图 `scratch/s72_1.png`、`s72_2.png`、`s72_2.png`。
- 按系统返回（`input keyevent KEYCODE_BACK`）→ **退回二课首页**，不退出网页。截图 `s72_3.png`。
- 再按一次返回 → 退出网页回到工作页（`mCurrentFocus=com.lanhang.miuix/.ui.MainActivity`）。截图 `s72_4.png`。
- 登录态：机制已接通（会话 Cookie 持久化 + 建 WebView 回填），真实「登录一次之后不再要求登录」
  需要用户用自己的学号在真机走一遍——模拟器上没有可用的二课账号。

### 72.4 产物

- APK **8,676,480 字节**，SHA256 `6CE267669CE8A519F6EFD1436F13E63FBF84A801B11B31293EF849E7F28AD2E1`
  （已复制为项目根 `lanhang-miuix-release.apk` 并 `adb install -r` 成功）。
- 改动文件：`ui/OaWebViewActivity.kt` 34,096 → 36,784 字节；新增 `ui/WebLoginStore.kt` 2,956 字节；
  `AndroidManifest.xml` 5,712 → 5,763 字节。
- 版本仍 `versionCode 74` / `versionName "1.1.22"`，用户本轮未要求 push。

### 72.5 三条教训

1. **「页面是不是白的」不能只看高度。** 现代前端框架把内容装进 `fixed/absolute` 容器后，
   `body` 高度可以是 0 而页面上满满都是字。判定白板要同时看**正文内容量**。
2. **同一个「返回」，系统手势与顶栏按钮可能根本不是同一条通路。** targetSdk ≥ 33 之后，
   `enableOnBackInvokedCallback` 决定系统走 `OnBackInvokedDispatcher` 还是 `OnBackPressedDispatcher`。
   只要「按钮好使、手势不行」，先查清单里这个开关，别急着改业务逻辑。
3. **`CookieManager` 不等于「记住登录」。** 它只持久化带 Expires 的 Cookie；会话 Cookie 必须自己
   抄进 SharedPreferences 再写回，否则「关掉再进就要重新登录」会被误判成服务端问题。
### 71. 第七十一轮（2026-11-14）：接入「赣青二课」

### 71.1 用户原话

> 「@lanhang-miuix\青新闻_3.3.3.apk 逆向一下这个软件，分析它的"赣青二课"接口，将"赣青二课"功能接入工作功能中」

追问入口位置时用户答「我把网址发给你」，随后给出：

> 「链接在这里 https://gqhw.jxmediacenter.com/#/ 」

### 71.2 逆向结论：青新闻客户端里根本没有「赣青二课」

APK 116,421,298 字节、7625 条目、**19 个 dex 合 82MB**、包名 `com.jxnews.jxttn`（江西新闻 · 江西团团）、uni-app 打包、assets 339 条目。逐项排查：

| 找法 | 结果 |
|---|---|
| 4419 个文本条目搜「赣青二课 / 赣青 / 二课 / 第二课堂 / 素质拓展 / 第二课堂成绩单」（UTF-8 与 UTF-16LE 双编码） | **全部 0 命中**；对照组「新闻」命中 100+ 处，证明扫描本身没问题 |
| `com/jxnews` 下 3030 个业务类按模块统计 | `djh` 1722、`newsdetail` 725、`attendance` 327（媒体内部考勤打卡）、`view` 225、`chat` 29 —— **没有任何学分/二课模块** |
| 186 个域名 / 461 条 URL | 无二课站点；唯一沾边是 `ad.jxzyz.cn/volunteer/...`（江西志愿服务网） |
| `dekt` / `erke` 关键词 | 全是误报：`CrossfadeKt`、`TrieNodeKt`、`NumberKeyListener`、`PointerKeyboardModifiers` |
| 18 个猜出来的候选域名（`dekt.jxzyz.cn`、`dekt.jxgqt.org.cn`、`erke.jxcn.cn`…） | 全部 NXDOMAIN |

**结论**：「赣青二课」是服务端菜单下发的外部 H5 外链，客户端只负责给个入口，静态分析永远拿不到它的接口 —— 所以「分析它的接口」这条路本身走不通，正确做法是拿到真实地址后作为网页接入。

### 71.3 真实站点（用户提供）

`https://gqhw.jxmediacenter.com/#/` → DNS `36.133.146.115`，`<title>第二课堂</title>`，Vue + uni-app 单页应用，静态资源挂在 `pic.tt.jxnews.com.cn`（与青新闻同源，确认是同一体系的二课系统）。

前端 118 条路由：活动、实践、志愿、成绩单、申报、答题、竞赛、校园组织、校友圈、积分、诚信值、团队、项目孵化、奖项…… 接口是若依（RuoYi）标准那套（`/app/login`、`/getInfo`、`/system/oss/upload`），且真实 API 有独立前缀、不在站点根路径。

**所以不做原生页**：几十个页面 + 全程登录态，重写既不可能也没必要。工作页只给入口，WebView 承载就是完整接入。

### 71.4 代码改动（三处 + 一处上传支持）

1. `work\WorkRoute.kt`（1765 → 3015 字节）：末尾新增
   ```kotlin
   const val GQHW_APP_ID = "native:gqhw"
   ```
2. `work\WorkCatalog.kt`（15046 → 15447 字节 / 346 行）：在「图书馆」分组后新增
   ```kotlin
   "团学活动" to listOf(
       WorkApp(id = GQHW_APP_ID, name = "赣青二课", description = "第二课堂成绩单",
               homePageUrl = "https://gqhw.jxmediacenter.com/#/"),
   )
   ```
3. `work\WorkPage.kt`（52055 → 52367 字节 / 1128 行）：
   - `COMMON_SLOTS` 增加 `CommonSlot.Server(listOf("赣青二课"))` → 进顶部「常用功能」；
   - `openApp` 里 `preflightUrl = if (app.id == GQHW_APP_ID) null else openUrl`。
     **这一行是关键**：`OaWebViewActivity` 只要拿到 `preflightUrl` 就会先跑一遍学校 OA 网关预检（20~30s），还会把 12s「白板看门狗」误触发成「页面打不开」。二课是校外站，必须传 `null` 走「直接加载」分支。
4. `ui\OaWebViewActivity.kt`（25831 → 34096 字节 / 699 行）：补文件上传链路 ——
   `private var fileCallback: ValueCallback<Array<Uri>>?`、`view.webChromeClient = object : WebChromeClient() { override fun onShowFileChooser(...) }`、`override fun onActivityResult(...)` 回填（多选取 `clipData`、单选取 `data`、取消回 `null`）、`REQUEST_FILE_CHOOSER = 4001`。
   原因：WebView 默认不弹系统文件选择器（`onShowFileChooser` 默认返回 false），二课要传头像和实践证明材料，没有这一层用户点「上传」毫无反应。

### 71.5 验证（模拟器 1220×2656）

工作 tab → 「赣青二课」（`[222,1323][1122,1389]`）→ 点击 → 顶栏「← 赣青二课」，约 8 秒后页面完整渲染：

- Banner + 首页数据：学生数 **1538642**、活动数 **233711**、活动人次 **17718748**
- 九宫格：活动 / 奖励申报 / 校园组织 / 问卷调查 / 诚信值 / 二课成绩 / 思政教育 / 创新创业 / 志愿公益 / 更多
- 最新活动列表（真实数据：25农产品一班11月消防演习 2.0 学时、童眼看警院 3.0 学时、演讲与口才协会「即兴推销挑战赛」0.03 学时…）
- 底部「首页 / 我的」

游客态即可浏览，无需登录。截图 `scratch\s71_1.png`（工作页入口）、`s71_3.png`（二课首页）。

### 71.6 产物

APK **8,676,448 字节 / SHA256 `3DD145212FFA561CC84BC1540E4B9B66B07A2CEA16A7135114B4304832A49F05`**，已复制为项目根 `lanhang-miuix-release.apk` 并装机。版本仍 `versionCode 74` / `versionName "1.1.22"`（用户未要求发版）。

### 71.7 三条教训

**① 又一次被 `[IO.File]::WriteAllText` 吃掉了源文件。** 本轮打补丁时 `WorkCatalog.kt`（15046 字节）与 `WorkRoute.kt`（1765 字节）从源码目录**消失**，靠 `push\repo` 的副本才恢复（幸好第七十轮刚 robocopy 同步过）。症状是执行补丁脚本时报「不能对 Null 值表达式调用方法」且**没有任何输出**。已确立新规矩：**任何写盘脚本第一步必须 `Copy-Item` 备份到 `scratch\`**，写盘前校验新内容长度 ≥ 原长度（字符级）、写盘后立刻回读校验锚点与花括号净深度；函数内部一律不用 `Write-Output` 做诊断（会被调用处赋值捕获），诊断只用 `Write-Host`。

**② 「找不到符号」时要先怀疑源码不完整，再去猜架构。** 本轮花了很久在找 `WorkRoute` / `WorkCatalog` 的定义，把 `object WorkRoute` 当唯一形态去搜，实际它是 `enum class WorkRoute(val depth: Int)`；更要紧的是这两个文件当时已经丢了。查证手段：`cmd /c dir /s /b *WorkCatalog*` 一招确认「只在 `build\tmp\kotlin-classes` 里有 .class、源码里没有」。

**③ 校外站点不能走学校 OA 网关的预检链路。** `OaWebViewActivity.open()` 的 `preflightUrl` 不是可选装饰：传了就要先过 `WorkRepository.prepareFor`（网关鉴权 + 换落地地址），校外站过不去，还会触发 12 秒白板看门狗。接任何非 `nchu.edu.cn` 的功能，`preflightUrl` 一律传 `null`。
## 69.11 发版 1.1.22（用户 2026-11-14 指定版本号）

用户原话：「push发版，版本号应为1.1.22」。

- `versionCode 73` → **74**、`versionName "1.1.21"` → **"1.1.22"**（`app/build.gradle.kts` L26-27）。
- `VERSION.md` 37,494 → 38,056 字节，在版本表分隔线后插入 `| 1.1.22 | 74 | …` 一行，覆盖第 65–69 轮全部内容。
- 编译 `BUILD SUCCESSFUL in 1m 48s`、退出码 0、错误 0（日志 `%TEMP%\b70.txt`）。
- APK **8,676,448 字节**，SHA256 `D873D675B291AD3638F97A75970D32F303CD27273095674F15B75E2CEE7C63CF`，已复制为项目根 `lanhang-miuix-release.apk`。
- 本轮顺带把第 65–69 轮（课表抖动架构改造、周视图动画、Nexio 规范重做、全站按钮统一）一并发布，此前一直停留在 `versionCode 72` / `1.1.20`。
### 69.8 第 2 条第 1 步：添加课程页按 Nexio 规范重做

设计口径先落成 `scratch/nexio-design.md`（148 行），再逐条落到代码。`schedule/ManualCourseScreen.kt` 53,056 → 56,979 字节（1,086 → 1,185 行）。

- 行规范：`FormRow` 标签 `body2.copy(fontSize = 17.sp, fontWeight = Medium)` + `weight(1f)`（不再钉死 88dp）；输入 `BasicTextField` 用 `weight(1.86f)`（≈ 35% : 65%，等价 `fillMaxWidth(0.65f)` 但不与 weight 抢测量）；`TextAlign.End`；三行基本信息**去掉两个 `Divider()`**，首行 17dp / 中间 14dp / 末行 17dp。
- 星期按钮：44dp 高胶囊 → `Chip` 内部钉死 `height(32.dp)` + `RoundedCornerShape(10.dp)` + `clickable(interactionSource = null, indication = null)` + 13sp，调用侧只传 `weight(1f)`，间距 8dp → 6dp。
- 周次快捷态：圆形单选 `RadioTag` → **三态方框**（`TriState.OFF/ON/PARTIAL` + `TriBox`，20dp 方块、6dp 圆角、ON 打勾、PARTIAL 横条）。**没有直接用 Miuix `Checkbox`** ——它自带 48dp 行高会把卡片顶爆，所以自己画框、点击交给外层可点 Row。
- 周次与节次网格：`spacedBy(8.dp)` → `6.dp`，6 列 × 32dp × 13sp，末行不满左对齐。

实测（`scratch/a69_1.png`、`a69_3.png`、`a69_4.png`）：三态逻辑正确 —— 点周次 3 后再点「单周」→「单周」打勾、「全部」半选、「双周」空框；再点 3 取消后仍保持半选。

### 69.9 第 2 条第 2 步：上课提醒页按 Nexio 规范重做

`schedule/ClassReminderScreen.kt` 32,571 → 32,725 字节。核心是把「卡片里的独立按钮」换成「卡片里的行」。

- `ValueRow` / `SwitchLine` / `PermRow` 三个组件统一为 17sp Medium 标签 + 14.5sp 右侧值，行内边距 13dp → 14dp，开关说明 footnote1（13sp）→ 14sp。
- 新增 `ActionRow`（后提到 `ui/miuix/MiuixActions.kt` 共享）：整行可点、左侧 17sp Medium、右侧主题色动词 14.5sp + `›`。替换掉全部四处 `TextButton`：
  - 「发一条测试提醒」→ 「立即发送 ›」（用户点名的那颗按钮）
  - 「去授权」（勿扰）→ 「勿扰权限 / 去授权 ›」
  - 通知权限缺失 → 「通知权限缺失 / 去系统设置 ›」
  - 精确闹钟缺失 → 「「闹钟和提醒」权限 / 去系统设置 ›」
- 提示语降为卡片尾注 14sp。新增三个字符串：`class_reminder_test_action`=立即发送、`class_reminder_dnd_perm`=勿扰权限、`class_reminder_exact_perm`=「闹钟和提醒」权限。

实测（`scratch/r69_2.png`、`r69_3.png`）：三张卡片的标签、副标题、值、动作行字号与行高一致，动作行右侧是主题色动词而不是实心按钮。

### 69.10 第 2 条第 3 步：全站卡片动作按钮统一

新建 `ui/miuix/MiuixActions.kt`（4,982 字节），三个共享件：

- `CardActionChip(text, onClick, modifier, primary, enabled)`：**32dp 高 + 10dp 圆角 + 13sp + 无涟漪**。`primary = true` 用主题色文字 + 12% 主题色底（表示「这条记录现在最该点的那个」），其余灰字 + `surfaceContainerHighest` 底。存在的理由是 Miuix 的 `TextButton` 自带 40dp 最小高度，两个并排就顶满一行还把卡片撑高。
- `CardActionBar { }`：右对齐、8dp `spacedBy` 的一排胶囊。
- `ActionRow`：从 `ClassReminderScreen.kt` 的 private 版提上来的公共版。

替换了 7 个文件共 16 处 `TextButton`：

| 文件 | 处数 | 说明 |
|---|---|---|
| `library/SeatBookingsScreen.kt` | 8 | 签到 / 暂离 / 退座 / 返回 / 取消 / 扫码 / 刷新，主操作标 `primary` |
| `campusnet/CampusNetScreen.kt` | 3 | 刷新 / 登录（primary）/ 退出 |
| `library/LibrarySeatScreen.kt` | 2 | 我的预约 / 预约（primary） |
| `im/FriendRequestsScreen.kt` | 1 | 接受（primary） |
| `mine/UserProfilePage.kt` | 1 | 发私信（primary，去掉 `fillMaxWidth()`） |
| `moments/MomentsFilterScreen.kt` | 1 | 添加规则（primary） |
| `schedule/ClassReminderScreen.kt` | 4 | 改用共享 `ActionRow`，删除本地 private 版 39 行 |

**有意保留 `TextButton` 的地方**：`ui/miuix/MiuixDialog.kt`（对话框按钮，Miuix 规范就是文字按钮）、`ui/shell/ShellBars.kt`（工具栏）、`im/ImScreens.kt` 的 L287/L289（对话框「取消 / 加入」）、L859/L867（键盘 / 语音切换）、L879（发送）—— 这些都不是「卡片内的记录动作」，换掉会破坏既有交互位置。

实测（`scratch/r69_4.png`、`r69_5.png`、`r69_6.png`）：图书馆首页右上「我的预约」、底部「预约」（未选座时置灰）、校园网右下「登录」均为 32dp 胶囊，与添加课程页的星期胶囊同一套语法。

产物：`versionCode 72` / `versionName "1.1.20"`，APK 8,676,452 字节 / SHA256 `CFC82E5DC309A4514A9B308026FC47331D0CCDF1655D1E40AF303C514F4E3C4B`。用户本轮未提 push，故不升版本、不推送。

教训：
1. **同一个 UI 概念在不同组件里各写一遍，就一定会分叉。** `ActionRow` 先在 `ClassReminderScreen.kt` 里当 private 函数写，第二步要复用时只能复制，于是提出到 `ui/miuix/` 并删掉本地版 —— 跨页面的视觉统一必须以「共享组件」为前提，不能靠各处照着改。
2. **`Find-Block` + 整函数替换会留下原函数上方的 `@Composable` 和 KDoc。** 第一次编译报三处 `This annotation is not repeatable.`，就是新体里又写了一份注解。修法是行级去重：遇到 `@Composable` 时向上跳过空行与注释行，若已经有一份就丢弃本行；单行 `/** ... */` 与上一非空行重复时也丢弃。之后再 `WriteAllText`，花括号 net/min 双零校验。
3. **路径里 `miuix` 又拼成 `muix` 了。** 新建 `MiuixActions.kt` 时写成了 `com/lanhang/muix/ui/miuix/`，`write` 工具直接建了目录并写成功（父目录不存在会一路创建）。发现后 `Move-Item` 到正确路径、确认误建目录为空再 `Remove-Item`。**新建文件前必须先把目标目录 `Test-Path` 一遍，不能靠写成功后回看。**
## 69. 第六十九轮（2026-11-14）：周视图过渡动画异常

### 69.1 用户原话

> 第一，课表的另一个视图即周视图的过渡动画切换异常。第二，根据nexio的设计重做你的添加课程页面、上课提醒与所有的交互按钮（如上课提醒中的"发一条测试提醒"按钮。

### 69.2 第 1 条：周视图没有过渡动画

**现象**：在列表（周）视图里翻周，没有泳道滑入滑出的动画，只有一张旧画面横着扫过去，看起来像硬切。

**根因**：`slideSwap()` 里那一行

```kotlin
val movers = listOfNotNull(courseHeaderRow, courseBlockArea)
```

这两个字段都属于**网格视图**——它们是 `renderGrid()` 里建的 `headerRow` 与 `blockArea`。
周视图的两个滚动内容容器是 `dayStrip`（顶部日期摘要条）与 `daySections`（每天的日程卡），
**从来没有登记给动画用过**。于是周视图翻周时 `movers` 要么为空、要么指向网格那边已被
`removeAllViews` 摘掉的孤儿 View：

- 「动的那一下」根本没发生在任何可见 View 上；
- 只剩下 ghost 快照横着扫过去。

这就是用户看到的「过渡动画切换异常」。

第六十七轮把动画从「整层位移」收敛成「只位移课程列」时，只处理了网格那一条路径，
周视图这条路从那时起就已经断了——只是网格视图太常用，一直没被发现。

**修法（`schedule/ScheduleFragment.kt`，130,614 → 132,895 字节）**

1. 新增字段，把周视图的两个容器登记进来：

```kotlin
private var weekMovers: List<View> = emptyList()
```

2. `renderWeekView()` 的**两个出口**都要赋值（漏一个就会出现「某周翻过去之后又不动的」）：

```kotlin
weekMovers = listOf(dayStrip, daySections)
```

无数据早退分支（`data == null`）与正常结尾各一处。

3. `slideSwap()` 按当前视图取 movers：

```kotlin
val movers = if (showingGrid) {
    listOfNotNull(courseHeaderRow, courseBlockArea)
} else {
    weekMovers
}
```

4. 轴宽也按视图分：周视图没有左侧时间轴，整宽都是内容，快照不必裁左边：

```kotlin
val axisW = axisOverride ?: if (showingGrid) gridAxisW.coerceIn(0, width / 2) else 0
```

5. 视图互切（网格 ↔ 列表）时传 `axisOverride = 0`：两张快照形状压根不同（一个有左侧
   时间轴、一个没有），绝不能拿当前视图的轴宽去裁另一张快照，否则会把旧画面的左边一截削掉。

```kotlin
slideSwap(activeContentLayer(), snapshot, if (grid) 1 else -1, axisOverride = 0)
```

### 69.3 验证（模拟器 1220×2656）

切到列表视图后连点「下一周」9 次并连拍 6 张：

- `v1.png`（352,224 字节）是**动画中间帧**：第 13 周的内容整体向左位移、右侧露出空白，
  内容只有一份，无重叠——这正是泳道动画在跑的证据（改前这一下是完全不动的）。
- `v2`–`v6`（253,460 / 260,435 / 258,842 / 258,848 / 259,464 字节）为连续翻周过程，
  每张都是单份内容、日期摘要条与日程卡对齐正常，无残影、无重影、无卡死。
- 落点第 14 周，日期摘要条周一~周五与下方日程卡逐项一致。

### 69.4 产物

- `ScheduleFragment.kt` 130,614 → **132,895 字节**（花括号净 0）
- APK **8,675,160 字节**，SHA256 `15CC24D6EDAA3F599613C37A5CC3776BC5A7CDFE1E3A3D78FECD7BA19B5EC59C`
- 已复制为项目根 `lanhang-miuix-release.apk`，`adb install -r` 输出 `Success`
- 版本仍 `versionCode 72` / `versionName "1.1.20"`（本轮用户未要求 push）

### 69.5 第 2 条：尚未开始

用户第 2 条要求「根据 nexio 的设计重做添加课程页面、上课提醒与所有的交互按钮」。
这是一次**结构性重做**，涉及：

- `schedule/ManualCourseScreen.kt`（53 KB，添加/编辑课程整个表单）
- 上课提醒页（`class_reminder_*` 共 80 余个字符串键，含超级岛、勿扰、次日提醒等分组）
- 全站交互按钮的视觉统一

参考对象是 `NexioSchedule` 的 `AddCourseDialog.kt`（66 KB）、`TimeConfigEditScreen.kt`（125 KB）、
`CourseReminderScreen.kt`（100 KB）。按每轮 40 分钟的执行纪律，这一条需要单独安排轮次，
本轮先交付第 1 条。

### 69.6 教训

1. **同一条动画代码被两种视图共用时，两种视图的内容容器都要登记。** 只处理常用的那一条，
   另一条会静默失效——而且失效得很像「没有设计动画」，不容易被当成 bug 报出来。
2. **`renderXxx()` 的所有出口都要维护同一份状态。** 早退分支（无数据、无权限、无网络）
   最容易漏，漏了之后表现为「某些数据下正常、某些数据下失效」。
3. **快照裁剪宽度必须与快照自身来源一致。** 拿 A 视图的轴宽去裁 B 视图的快照，
   会静默丢内容，而且只在互切的那一帧出现。
### 69.7 补正（用户 2026-11-14 二次反馈）：手势拖动切周时「左右分裂」

**用户原话**：「不同周次间滑动切换时，左半边的内容不会移动，只有右半边的内容在移动。导致周次界面直接分裂」。

**这是另一条路径，69.2 改的 `slideSwap()` 管不到它。**

点「‹ / ›」按钮走的才是 `slideSwap()`；**左右滑动切周走的是 `startPager()`**（第三十八轮那套
「手势期间只跟静态位图走」的实现）。两者共用同一个「轴线宽度」概念，但取法不同：

```kotlin
private fun axisWidthPx(): Int {
    val active = runCatching { activeContentLayer() }.getOrNull()
    val gridW = if (gridAxisW > 0) gridAxisW else dp(40)
    val listW = runCatching { daySections.left + daySections.width }.getOrDefault(0)   // ← 病灶
    val w = if (active === contentGrid) gridW else (if (listW > 0) listW else dp(40))
    return w
}
```

周视图分支返回的是 `daySections.left + daySections.width`。而 `daySections` 在
`fragment_schedule.xml` 里是 **`match_parent`** —— 算出来就是**整屏宽 1220**，
紧接着在 `startPager()` 里被夹了一道：

```kotlin
val axisW = (content.left + axisWidthPx()).coerceIn(0, layer.width / 2)   // → 610，半屏
```

后果（`startPager()` 全部按 `axisW` 分栏）：

- L668 `if (axisW > 0)` 成立 → 把旧快照的 `0..610` 裁成一条 `axis`，`addView` 钉在原位；
- L704 `colsW = w - axisW` → 新周位图只截了**右半边**；
- L729/741/779 所有位移都带 `axisW` 偏移。

于是拖动切周时：**左半边被当成「钉住的时间轴」原地不动，只有右半边在滑** —— 正是用户说的
「左半边不动、右半边动、界面裂成两半」。

**修法**：周视图没有左侧时间轴，轴线宽度必须是 **0**，与 69.2 里 `slideSwap()`
的 `else 0` 保持一致。

```kotlin
private fun axisWidthPx(): Int {
    val active = runCatching { activeContentLayer() }.getOrNull()
    val gridW = if (gridAxisW > 0) gridAxisW else dp(40)
    return if (active === contentGrid) gridW else 0
}
```

`axisW = 0` 之后：`if (axisW > 0)` 不成立（不添加钉住的轴条）、`colsW = 全屏宽`、
新周位图整幅截取、位移不带偏移 —— 整幅画面一起平移。

**验证**（模拟器 1220×2656，周视图，用 `input motionevent` 逐段拖动并抓中途帧）：
`m1.png`（240,416 B）与 `m3.png`（240,380 B）都是「整幅画面同步左移」——
旧周的周二/周三/周四/周五依次向左退出、新周的周一/周二从右侧进入，左右两半**位移量一致**，
没有任何一半停在原地。修复前同样操作会看到左 610px 完全静止。

**教训**：同一个概念（「轴线宽度」）在两套动画实现里各算各的，改了一处另一处照旧出错。
周视图的轴宽本来是「整屏宽」，这个值在**钉住时间轴**的语义下毫无意义 ——
它唯一的作用就是把画面切成两半。凡是「取一个宽度当分栏依据」的地方，都要先问：
这个视图**真的有**那一栏吗？没有就必须给 0，不能给「一个看起来合理的数」。
## 68. 第六十八轮（2026-11-13）：切回课表被压缩到很扁

### 68.1 用户原话

「从另一个页面的下方切换到课表页时，课表显示被挤压到很扁又恢复」（附真机录屏 `Screenrecorder-2026-11-13-22-09-27-199.mp4`）。

### 68.2 五轮试错（全部无效，记下来免得再犯）

| 尝试 | 做法 | 结果 |
|---|---|---|
| 第 1 版 | `renderGrid()` 入口拦「过渡窗口内」，条件写成 `&& contentFilled() > 0`（想避免白屏） | 无效。恰好放走唯一会出事的路径：切回来时 `gridContent` 可能还没有子 View，`contentFilled()` 返回 0，守卫放行 |
| 第 2 版 | 去掉放行条件，窗口 400 → 450ms | 无效 |
| 第 3 版 | 高度不可信时 `return` 且不清容器 | **更糟：永久白屏**。视图重建后容器本来就是空的，一 `return` 就再没人画 |
| 第 4 版 | `availH` 退化成 `lastLayoutHeight` 兜底 | 无效 |
| 第 5 版 | 窗口 450 → 900ms | 无效。扁的那一帧不在窗口边界上，是**窗口内某次重排**吃到了 810px |
| 第 6 版 | 入口和取值处各加「高度 < 屏幕 55% 就跳过」 | 无效。入口检查时 `gridScroll.height` 还是 0（放行），等到取值处已经变成 810px — 而 810px 本身「看起来是合法高度」，判断不出对错 |

### 68.3 真正的原因

`renderGrid()` 每次都现读 `gridScroll.height`。切回课表的那一段时间里，这个值会先经过 **约 810px** 的中间态再到正常的 **约 1900px**。810px 不是「0 或异常」，它就是个能算出行高的正常数字 —— 于是排出一张十行挤在半屏的课表。任何「判断这个高度对不对」的写法都拦不住它，因为从数值上看它没毛病。

### 68.4 参考 NexioSchedule 得到的思路

用户提供了 `NexioSchedule-master.zip`。它的课表把几何**预先算好再传进去**：

```kotlin
data class ScheduleGridGeometry(
    val dayBounds: Map<Int, FloatArray>,
    val sectionHeightPx: Float,
    val morningSections: Int,
    ...
    val sectionTopDp: Map<Int, Float>,
)
```

绘制时绝不回头问容器「你现在多高」。而我们是反过来的：每次渲染都现读容器高度 —— 所以过渡中间值必然渗进来。

### 68.5 最终修法（`schedule\ScheduleFragment.kt`，133 KB 上下）

引入 `stableAvailH`，**渲染只认它，绝不现读 `gridScroll.height`**：

```kotlin
// 字段
private var stableAvailH = 0
```

写入点只有一处 —— `gridRelayoutTask`，而它只在「布局静止 320ms 之后」才被允许跑：

```kotlin
val wait = when {
    tabSettleUntil > now -> (tabSettleUntil - now) + 40L
    stableAvailH == 0 -> 80L      // 冷启动首次，别让首屏白等
    else -> 320L                  // 320ms 内没有新的布局变化，才采信这个高度
}
gridScroll.postDelayed(gridRelayoutTask, wait)
```

```kotlin
if (lastLayoutHeight <= 0 || h != lastLayoutHeight || stableAvailH != h) {
    lastLayoutHeight = h
    stableAvailH = h
    renderGrid()
}
```

`renderGrid()` 里：

```kotlin
val availH = stableAvailH
if (availH <= 0) { /* 推迟 80ms 重试 */ return }
```

顶栏收放是 300ms 连续动画，原来 160ms 的防抖太短，采到的正是动画中途值；改 320ms 后中间值根本进不来。

### 68.6 验证

模拟器 1220×2656：工作页下滑三段 → 点回课表 → 连拍 12 张。第 1 张是工作页，**第 2–12 张字节完全相同（364,113）**，没有任何扁的中间帧（修改前第 2、3 张是 163,056 的扁课表）。

极端交替连点 `>`×20 次后：时间轴单份、位置正确，第 18 周网格铺满，无重影。

### 68.7 三条教训

1. **「看起来合法的错误值」比「明显的异常值」难防。** 810px 不是 0、不是负数，任何 `coerceAtLeast`、任何下限判断都放行。真正的解法不是判断它对不对，而是**从架构上不让它进来** —— 只在尺寸静止后采样一次，写进字段，渲染只读字段。
2. **`return` 要慎用。** 第 3 版为了「不排错」而 `return`，结果把「排错」换成了「永远不排」。判断一个提前返回是否安全，必须回答：**谁负责在条件满足后重新调用我？** 答不上来就不该 return。
3. **时序问题的修法不该是「等更久」。** 900ms 仍然无效，因为有别的路径在窗口内触发重排。时间窗口只能挡住「已知路径」，挡不住「还没想到的路径」；状态量（稳定高度）+ 唯一写入点才能挡住全部。
## 67. 第六十七轮（2026-11-13）：泳道动画恢复 + 时间轴彻底静止 + 切回课表不再回弹

### 67.1 用户原话（m02548）
> 第一，泳道动画被改坏了，快速点击会重影。第二，异常回弹问题还是没有解决，参考视频 Screenrecorder-2026-11-13-19-55-16-641.mp4

### 67.2 第 1 条：泳道动画
第六十五轮为了消灭「左侧时间栏重叠」，把 `slideSwap` 改成了「旧快照原地淡出、新内容完全不动」。
方向感没了、观感比原来的泳道差；连点时几份半透明残影同时叠着 —— 这就是用户说的重影。

**真正的病因**（第六十六轮彻查得到）：菜单层级是

```
contentGrid (SwipePagerLayout)
└─ gridScroll (NestedScrollView)
   └─ gridContent (LinearLayout, vertical)
      ├─ headerRow          ← 周一~周五表头
      └─ body (LinearLayout, horizontal)
         ├─ timeCol         ← 左侧时间轴
         └─ blockArea       ← 课程块（FrameLayout 绝对定位）
```

`slideSwap(layer = contentGrid)` 位移的是 `contentGrid` 的子 View —— 也就是整个 `gridScroll`，
时间轴在里面，必然跟着滑。旧快照又是整屏截图，于是「旧轴线 + 新轴线」同时出现。

**修法（三处）**
1. `slideSwap` 只位移两个课程列容器，时间轴不参与：
   ```kotlin
   val movers = listOfNotNull(courseHeaderRow, courseBlockArea)
   val ghostShift = if (direction >= 0) -ghostW.toFloat() else ghostW.toFloat()
   // 旧列整列滑出、新列整列从另一侧滑入；时间轴既位移也不被覆盖
   ```
2. `renderGrid()` 里给课程块区套一个同宽同高的裁剪盒 `blockClip`（`clipChildren = true`），
   块区滑出边界的部分直接被裁掉，不会再压到左边的 `timeCol`。
3. `ghost` 也放进 `ghostBox`（同样 `clipChildren = true`、`leftMargin = gridAxisW`），
   它整列滑出时右边缘会扫过时间轴区域，不裁就会把时间轴盖住。
   `clearTransitionLeftovers()` 的判定从 `c is ImageView && c.tag == GHOST_TAG` 放宽成
   `c.tag == GHOST_TAG`，以便连点后把 ghostBox 一起清掉。

### 67.3 第 2 条：切回课表不再回弹
前几轮（63/64/65/66）分别在改「比较方向」「采样时机」，都绕不开同一件事：
**只要在切换 Tab 后的过渡窗口里排一次版，用到的就一定是错的高度。**
顶栏正在从上一页的形态线性过渡到课表的形态，`gridScroll.height` 在这几百毫秒里持续变化；
先按中间值排一遍（屏幕上表现为被拉长），过渡结束再按最终值排一遍（表现为弹一下）。

**修法**：把守卫从「比较高度」提升为「禁止排布」，并提到 `renderGrid()` 最前面：

```kotlin
if (tabSettleUntil > android.os.SystemClock.uptimeMillis() && contentFilled() > 0) {
    if (::gridScroll.isInitialized) {
        gridScroll.removeCallbacks(gridRelayoutTask)
        gridScroll.postDelayed(gridRelayoutTask, (tabSettleUntil - now) + 40L)
    }
    return
}
```

`contentFilled() > 0` 这个条件是必须的：首次进入课表或切到空周时界面上什么都没有，
白屏 400ms 比轻微跳动更糟，那种情况必须立刻排。

### 67.4 实测（模拟器 1220×2656，density 2.925，第 9→20 周）
- 极端交替连点（`>`×5、`<`×5、`>`×4 共 14 次）+ 连拍 5 张：时间轴**单份、清晰**，
  帧 `scratch\v66d\r1.png`（动画中）与 `r9.png`（稳定）对比，时间轴坐标逐像素不变。
- 稳定态 dump：`第 13 周`、表头周一~周五、时间轴 `1 [18,630]` / `08:00 [18,688]` /
  `08:45 [18,726]` … `14:55 [18,1560]`，全部单份。
- 切回课表：工作页下滑三段（顶栏收起）→ 点课表 tab → 连拍 8 张，
  `t2`–`t8` **字节完全相同**（362,985），即从第一张完整帧起就没有二次重排。

### 67.5 产物
- `ScheduleFragment.kt` 126,049 → **129,418 字节**
- APK **8,675,160 字节**，SHA256 `D0F1EBA245267B2F0754441754955842CC66D296FA57BDF06A83560AA0127F6B`
- 已复制为项目根 `lanhang-miuix-release.apk`，`adb install -r` 输出 `Success`
- 版本仍为 `versionCode 72` / `versionName "1.1.20"`（本轮用户未要求 push）

### 67.6 教训
1. **「位移一个容器」之前先看清容器里有什么。** `contentGrid` 里塞着整棵滚动树，
   位移它等于把时间轴一起拖走；正确的粒度是「只位移课程列」。
2. **`translationX` 不改变布局占位，但会改变绘制位置。** 兄弟 View 位移后会画到别人身上，
   靠 `clipChildren` 的裁剪盒才能真正隔开。
3. **过渡窗口内的重排是无解的，只能禁止。** 改判据只能把「拉长」换成「跳动」，
   换个方向再换回来；把排布整体推到窗口结束后，才是一次到位。
4. **`gridRelayoutTask` 内部 `postDelayed` 它自己会触发 Kotlin 类型推断递归**，
   报 `Type checking has run into a recursive problem`，必须显式标注
   `private val gridRelayoutTask: Runnable = Runnable { … }`。
5. **PowerShell 函数里 `Write-Output` 会被调用处的赋值一起捕获**，
   `$t = Rep …` 会把 "OK xxx" 和文件内容拼进 `$t`，写盘后文件开头多出一串垃圾。
   诊断输出一律用 `Write-Host`。
## 66. 第六十六轮（2026-11-13）：翻页连点时左侧时间栏重叠、切回课表仍回弹

### 66.1 用户原话

> 第一，快速点击翻页键时，左侧时间栏UI依旧会发生重叠。第二，工作/工作圈内下滑一段距离后，返回课表页依旧会异常回弹一下。彻查问题并修复

### 66.2 第 1 条：左侧时间栏重叠

**现象**：快速连点「下一周 / 上一周」时，左侧那条时间轴会出现两条，重影停在中途不消失。

**彻查**：`ScheduleFragment.slideSwap(layer, snapshot, direction)` 是切周动画的唯一实现。它做的事是：

1. `captureSnapshot(layer)` 把 **整个 `contentGrid`**（FrameLayout，里面装着 `gridScroll`，也就是「时间轴 + 课程列」一整条）画成一张位图；
2. 把这张位图作为 ghost 盖在最上层；
3. 把 `layer` 的**所有子 View**（即整个 `gridScroll`）`translationX` 从 ±屏宽 推回 0；
4. 动画结束后 `removeView(ghost)`。

而代码里第 **570-576 行**早就写明设计意图：

> 两种视图各有自己的轴……切周时这一列**不该跟着滑** —— 它每周都一样，滑出去再滑回来看着就像坏了。

也就是说，「时间轴不动」是当初就定下的规矩，但 `slideSwap` 根本没遵守：它把含时间轴在内的整块内容一起推，ghost 也是整屏快照。于是切周的一瞬间，**旧快照里的时间轴**和**正在滑回来的新时间轴**同时出现 → 用户看到两条。连点时前一次动画被 `cancel()`，`withEndAction` 不执行，重影就永久停在中间。

**修法**（`scratch\p65b_1.ps1`，整段重写 `slideSwap`）：

- 开头先 `clearTransitionLeftovers(layer)` 清场，把上一次没走完的动画与残留 ghost 一并收掉；
- ghost **只截轴线右侧的课程列**：`axisW = gridAxisW.coerceIn(0, width / 2)`，`Bitmap.createBitmap(snapshot, axisW, 0, ghostW, snapshot.height)`，再用 `LayoutParams(ghostW, layerH).apply { leftMargin = axisW }` 贴回原位。`gridAxisW` 是 `renderGrid()` 里算好的 `timeW + gapW`，就是时间轴实际宽度；
- ghost 只做 `translationX(-nudge).alpha(0f)` 淡出，`nudge = width * 0.05f * 方向`，即 5% 屏宽的方向性位移，不再是整屏推拉；
- **新内容完全不做位移动画**，只用 `others.forEach { it.animate().cancel(); it.translationX = 0f; it.alpha = 1f }` 归位。

净效果：时间轴从头到尾不位移、也不会被快照盖住，不管连点多快，屏幕上永远只有一份。

### 66.3 第 2 条：切回课表仍回弹

**现象**：在工作 / 工作圈里下滑一段距离（顶栏收起），再点回课表，课表会先被拉长一下再弹回正常行高。

**为什么前三轮都没修掉**：第 63、64、65 轮改的都是「切回这一帧怎么判高度、判到了要不要重画」：

- 第 63 轮：高度变了就重画；
- 第 64 轮：只在高度**变小**时重画（下滚收起顶栏会变大，变大会误伤）；
- 第 65 轮：加 `tabSettleUntil` 过渡窗口，窗口内拒绝采用「更大」的高度。

第 65 轮那一版尤其能说明问题：它只拒「变大」，可**实际高度是单调变小的**（从工作页收起态的中间值，一路收敛到课表的紧凑值），每一步都「比上次小」，守卫一次都没生效，等于白写。

真正的结论是：**在过渡窗口内做任何重排，用的都是错的高度。** 判据怎么调都是在「错的时刻干活的」这条错路上打补丁。

- 先按中间值画一遍 → 用户看到「被拉长」；
- 顶栏动画走完再按最终值画一遍 → 用户看到「弹一下」。

**修法**（`scratch\p65b_2.ps1`）：窗口期内**一次都不排**，只把任务一路推到窗口结束后，让第一次画出来就是对的。

- `gridScroll.addOnLayoutChangeListener`：高度变化时按 `val wait = if (tabSettleUntil > now) (tabSettleUntil - now) + 40L else 160L` 延后；
- `gridRelayoutTask`：进任务先查窗口，`if (tabSettleUntil > now) { removeCallbacks; postDelayed((tabSettleUntil - now) + 40L); return }`；出了窗口判据放宽回 `h != lastLayoutHeight`（此时已稳定，方向无关，只求与最终高度对齐）；
- `onTabSelected()`：删掉原来 `root?.post { … if (h < lastLayoutHeight) { lastLayoutHeight = h; renderGrid() } … }` 整段，改成 `root?.post { ensureContentNotEmpty() }` + 把 `gridRelayoutTask` 推到 `(tabSettleUntil - now) + 40L`；`if (contentFilled() == 0) renderAll()` 保留作白屏兜底；
- 窗口 `500ms` → `400ms`。

### 66.4 一个 Kotlin 编译坑

`gridRelayoutTask` 的任务体里要 `postDelayed` 它自己，`private val gridRelayoutTask = Runnable { … gridRelayoutTask … }` 会让 Kotlin 报：

```
e: ScheduleFragment.kt:308:40 Type checking has run into a recursive problem.
   Easiest workaround: specify the types of your declarations explicitly.
```

修法就是它字面说的方法 —— 显式标注类型：`private val gridRelayoutTask: Runnable = Runnable { … }`。

### 66.5 验证

- 第 1 条：交替连点 `>`×3 → `<`×3 → `>`×3 → `<`×3 → `>`×2 共 14 次，落第 11 周。`uiautomator dump` 里时间轴文字各一份、坐标正确（`08:00 [18,688][115,726]`、`14:00 [18,1396][115,1434]`、`20:40 [18,2306][115,2344]`），截图 `scratch\s66_03.png` 目视单份、无重影。
- 第 2 条：工作页下滑三段（顶栏收起）→ 点课表 tab，同一次操作里连抓两帧。t≈0.3s 那帧正处在 Tab 水平转场中间（工作页右滑出、课表左滑入），课表内容**已经就位、行高正确**；t≈2.3s 那帧与稳定态完全一致。整个过程没有出现垂直方向的拉伸与回弹。截图 `scratch\s66_01.png`（第 19 周连点后）、`s66_02.png`（第 18 周交替连点后）、`s66_04.png`（工作页下滑后切回课表）。

### 66.6 教训

1. **先读设计意图注释，再动手改代码。** 第 1 条的正解就写在 `ScheduleFragment.kt` 第 570 行 ——「时间轴不该跟着滑」。改了三轮都在调动画参数，而真正的错是「根本不该动画的东西被动画了」。
2. **同一个 bug 连修三轮不愈，说明模型错了，不是参数错了。** 第 2 条前三轮都在「判据」上做文章（变大/变小/窗口/阈值），但没变过的那件事才是病灶 —— 在高度还没稳定的时候去排布。换成「窗口内一律不排」之后一次就过。
3. **诊断日志在 release 构建里没用。** 本轮为了看 `gridScroll.height` 加过 4 处 `Log.d`，`logcat` 一条也抓不到（`adb logcat -d` 整机只有 107 行）。调试这类问题只能靠「分时抓帧 + `uiautomator dump` 读坐标」，别指望日志。收尾时那 4 段日志已全部删除（`scratch\p66_clean.ps1`）。
4. **`uiautomator dump` 会等界面 idle 再拍。** 所以它只能证明「稳定后是什么样」，证明不了「过渡期有没有抖」。要覆盖过渡期只能连拍截图，而连拍时 `screencap` 在模拟器上偶尔返回全黑（18,550 字节）—— 遇到就唤醒屏幕、`svc power stayon true` 重来。

## 65. 第六十五轮（2026-11-13）：翻页连点重叠卡死、切回仍拉伸、座位预约刷新不灵敏

### 65.1 用户原话

> 第一，在课表页快速点击翻页键时，课表会发生重叠最后卡死。第二，在工作圈下滑一段距离后点击课表页依然会出现异常拉伸后又回弹的情况。第三，图书馆预约座位功能中，刷新状态不够灵敏，导致有时已经扫码签到但却提示尚未签到，修复这些问题

本轮用户没有提 push，按其一贯规则「说了才 push + release」，**不升版本、不推送**，版本仍是 `versionCode 72` / `versionName "1.1.20"`，只在本地编译、装机验证。

### 65.2 课表连点翻页：重叠 + 卡死

**现象**：在课表页快速连点 `‹` / `›`，几轮之后整张课表变成好几张叠在一起，再点就彻底不动了。

**根因**（`schedule/ScheduleFragment.kt` 的 `slideSwap()`，三处叠加）：

1. `val others = (0 until layer.childCount).map { layer.getChildAt(it) }` 在 `layer.addView(ghost)` **之前**取值。上一段过渡遗留的 ghost 还在层里，于是被一起当成「当前内容」做 `translationX = shift → 0` 的入场动画，位置完全错乱。
2. 新 ghost 只是 `addView`，**没有先清理旧 ghost**。连点时每次切周都往同一层里加一张整屏位图，几张课表就叠在了一起 —— 这就是「重叠」。
3. `ghost.animate()...withEndAction { layer.removeView(ghost) }`。连点时下一段动画会 `cancel()` 这一段，而 **ViewPropertyAnimator 被 cancel 后 `withEndAction` 不会执行**，那张 ghost 就永远留在层里；更糟的是它的 `translationX` 停在半途，正好盖住整屏 —— 这就是「卡死」。

**修法**：

- `slideSwap()` 开头先 `clearTransitionLeftovers(layer)`（这个方法第 58 轮就写好了，本用于 `onTabSelected()`，这里复用：倒序移除所有 `tag == GHOST_TAG` 的 ImageView，并取消其余子 View 的位移动画、复位 `translationX/translationY/alpha`）。
- `others` 再 `.filter { it.tag != GHOST_TAG }` 兜一层，保证动画对象表里不会混进快照。
- `withEndAction` 内容包 `runCatching`，并追加一条 `layer.postDelayed({ runCatching { layer.removeView(ghost) } }, MainActivity.TAB_SWAP_MS + 80L)` 兜底 —— 即使动画被打断，80ms 后这张快照也一定会被摘掉。

**验证**：连点「下一周」8 次 → 落在第 17 周（9+8）；再点「回到本周」后连点 12 次 → 落在第 20 周（上限 clamp）。两次结果都是**完整的一张课表**，无残影、无卡死，第 1–10 节齐全、底部与 tab 对齐。随时抓到的过渡帧只是正常的水平滑入（见 65.3 的对比图）。

### 65.3 切回课表仍「先拉伸再回弹」

**现象**：第 64 轮改过之后，从工作圈（或任何会把顶栏滚成收起态的页面）切回课表，课表仍然会先被拉长再弹回去。

**根因**：第 64 轮只改了**两个触发点**（`gridRelayoutTask` 与 `onTabSelected()` 里的 `root?.post{}`），把判据统一成「只在实际高度变小时才重画」。但 `onTabSelected()` 里还有一条**更靠前**的路径没被覆盖：

```
if (contentFilled() == 0) renderAll()      // ← 这一行在 root.post{} 之前同步执行
```

切回的那一瞬间内容层可能正好是空的（Fragment 是 detach/attach 复用，回来时可能已清空），于是 `renderAll() → renderGrid()` 立刻按此刻 `gridScroll.height` 画了一整张网格 —— 而此刻顶栏还在从「上一页的收起态」往「课表展开态」过渡，这个高度是**偏大的中间值**。用户看到的就是「被拉长」；`renderGrid()` 末尾又把这个偏大值写进了 `lastLayoutHeight`，等 300ms 后顶栏动画走完、`root.post{}` 里发现 `h < lastLayoutHeight` 成立，再画一遍 —— 就是「弹一下」。

**修法**：引入一个**过渡窗口**。

- 新增字段 `private var tabSettleUntil = 0L`。
- `onTabSelected()` 第一行有效语句之后：`tabSettleUntil = SystemClock.uptimeMillis() + 500L`。
- `renderGrid()` 里把 `val rawAvailH = gridScroll.height` 换成：

```kotlin
val measuredH = gridScroll.height
val rawAvailH = if (lastLayoutHeight > 0 &&
    measuredH > lastLayoutHeight &&
    SystemClock.uptimeMillis() < tabSettleUntil
) lastLayoutHeight else measuredH
```

即在窗口内**只认「不变大」**：测量值比上次稳定值大就沿用旧值。窗口一过自然回到实测值，不需要额外定时器。

**验证**：「我的」页（替代工作圈 —— 模拟器无外网连不上工作圈接口，但两者同为 Compose LazyColumn + `ReportListScroll` 上报顶栏收起，机制相同）下滑三段使顶栏收起 → 点课表 tab → 同一条 `adb shell` 内连拍 4 张。过渡帧与稳定帧叠放对比：内容只是整体水平滑入，**行高逐像素一致**（第 1 节、第 10 节 y 坐标相同），没有任何纵向拉伸；稳定后两张截图字节完全相同（363,623 B）。

### 65.4 图书馆座位预约：刷新不灵敏

**现象**：去馆内签到机扫码签到后，回到「我的预约」仍显示「待签到」；有时签到按钮该出现了却一直不出。

**根因两条**：

1. `SeatBookingsScreen` 只在**首次进入**（`LaunchedEffect(reload)`）和**操作成功后** `reload++` 时才拉数据。签到这件事往往不是在本机 App 里点的（馆内机器、别人的手机），本机不主动重拉就永远停在旧状态。
2. `SeatBooking.canCheckIn()` 用的是 `serverNow` —— 那是**拉取那一刻**服务端下发的时间戳，是个定值。页面停留越久，它离「现在」就越远，签到窗口永远不推进，于是按钮该出不出。

**修法**：

- `SeatModels.kt`：`SeatBooking` 增加 `val fetchedAtMs: Long = System.currentTimeMillis()`（data class 最后一个参数、带默认值，`myBookings()` 不用改），并新增 `fun serverNowSec(): Long = serverNow + (System.currentTimeMillis() - fetchedAtMs) / 1000`；`canCheckIn()` 改用它。这样既保留「以服务端时间为准」（不受手机时钟偏差影响），又能在页面停留期间持续推进。
- `LibrarySeatApi.kt`：暂离的 `comeBackTime` 同样改用 `serverNowSec() + limitLeftBack`。
- `SeatBookingsScreen.kt`：存在待签到订单时，**每 15 秒静默重拉一次**（`LaunchedEffect(pendingCount)` 里的 `while + delay(15_000)`，全部使用中/已结束就自然停下）；失败一律静默，不用错误条打断用户；列表顶部加一行小字「每 15 秒自动同步签到状态 / 正在同步签到状态…」。

**验证**：编译通过、页面可正常进入。「我的预约」在模拟器上因无内网 DNS 解析不到 `lib-zw.lib.nchu.edu.cn`，只能看到「加载失败：Unable to resolve host ...」，**真实轮询与签到状态变化待真机确认**。

### 65.5 产物

- `app/build/outputs/apk/release/app-release.apk` = 8,675,160 字节，SHA256 `CCD30166F6B6347A5433E125A4E04550C241345DB1B899A2CC6C17CB49EC78F8`，已复制为项目根 `lanhang-miuix-release.apk` 并 `adb install -r` 装机。
- 改动的文件：`schedule/ScheduleFragment.kt`（122,478 → 125,111）、`library/SeatModels.kt`（7,466 → 8,560）、`library/LibrarySeatApi.kt`（20,043 → 20,190）、`library/SeatBookingsScreen.kt`（16,571 → 18,441）、`res/values/strings.xml`（53,924 → 54,071）。
- 版本未升（用户未要求 push）。

### 65.6 教训

1. **同一个症状可能有多条触发路径，改完一条不代表修好。** 第 64 轮已经把「高度变了就重画」改成「只在变小时重画」，但 `onTabSelected()` 里 `if (contentFilled() == 0) renderAll()` 是**在 `root.post{}` 之前同步执行**的，绕过了新判据。定位这类问题的正确姿势是：把「谁在什么时候读了那个会变的值」全部列出来，而不是只改自己上一轮动过的那两行。
2. **ViewPropertyAnimator 的 `withEndAction` 不保证执行。** 只要有 `cancel()` 参与（连点、快速切换、页面销毁），就必须配一个不依赖动画完成回调的兜底移除，否则就是资源残留 —— 而整屏位图残留的视觉效果恰好是「界面卡死」，很容易被误判成别的问题。
3. **「服务端时间」要区分「拉取时刻」与「现在」。** 服务端下发的时间戳只是快照，页面停留期间必须自己往前推（本次用「抓取时刻 → 现在」的差值补偿，而不是直接读手机时钟，这样既实时又不受时区/时钟偏差影响）。
4. 模拟器无内网时，本项目的三类接口表现各不相同：融云 IM 页面是「加载失败 + 空态」，工作圈直接「加载失败」，图书馆是**先无限 loading 十秒以上**再出失败文案。做 UI 验证时按现象分别判断，别把「还在转圈」当成卡死。
## 64. 第六十四轮（2026-11-12）：切回课表时「先拉伸再弹回」

### 64.1 用户原话

> 现在从工作圈下部直接切换到课表时会从拉伸状态弹一下再恢复正常，修复这个问题

以及同轮追加的交付指令：

> 完成修改后push到github，push完成后休眠电脑

### 64.2 现象与根因

第 63 轮第 1 条为了修「从工作圈返回后课表被截断」，加了一条自检：`gridScroll` 的高度只要和 `lastLayoutHeight` 不一样就重画。这条守则**不区分高度是变大还是变小**，于是引出新问题。

切换回课表的那一帧，顶栏还在从「工作圈的收起态」往课表的展开态过渡，`gridScroll.height` 此刻是**偏大的中间值**：

1. `onTabSelected()` 里的 `root.post { }` 判 `h != lastLayoutHeight` 成立 → 立刻按这个偏大值把网格画一遍，行高被算大，整张课表看起来被**拉长**；
2. 300ms 顶栏动画走完，`gridScroll` 回落到展开态高度，160ms 防抖任务再重排一次 → 用户看到「**弹一下**」才恢复正常。

反向同理：在课表里往下滚使顶栏收起（高度变大），也会触发一次不必要的重排，内容会跳一下。

一句话：**「高度变了就重画」把顶栏动画产生的过渡中间值也当成了真实布局高度。**

### 64.3 修法

核心规则改为**只在「可用高度变小」时重画**，变大一律不动。两处同步改（`schedule/ScheduleFragment.kt`）：

补丁 A，`gridRelayoutTask`：

```kotlin
private val gridRelayoutTask = Runnable {
    if (!isAdded || view == null || !::gridScroll.isInitialized) return@Runnable
    val h = gridScroll.height
    if (h <= 0 || !showingGrid) return@Runnable
    // 只在「可用高度变小」时才重画。
    //
    // 变大意味着顶栏正在收起（用户往下滚课表），或者正带着上一页的收起态切回来 ——
    // 这是个过渡中的中间值，不是最终布局高度。按它重排，整张网格会先被拉长、
    // 等顶栏展开动画走完再弹回原样。
    // 变小则必须重画，否则网格比可用区高，最后一行会被 ScrollView 静默裁掉。
    if (lastLayoutHeight <= 0 || h < lastLayoutHeight) {
        lastLayoutHeight = h
        renderGrid()
    }
}
```

补丁 B，`onTabSelected()` 里的高度自检：

```kotlin
root?.post {
    val h = if (::gridScroll.isInitialized && gridScroll.height > 0) gridScroll.height else root.height
    if (h > 0 && (lastLayoutHeight <= 0 || h < lastLayoutHeight)) {
        lastLayoutHeight = h
        renderGrid()
    }
    ensureContentNotEmpty()
}
```

净效果：切回课表时网格一直保持上次展开态那一份正确布局，全程不重排；高度真的变小（比如顶栏从收起态恢复、或从别的页面带着更大高度回来）时才重排，第 63 轮修好的「末行被裁」不会回退。

### 64.4 验证

模拟器 1220×2656，第 9 周。工作圈在模拟器里打不开（内网 API 不通，显示「加载失败」），改用机制相同的「我的」页代替：下滑三段使顶栏收起（标题从 `[59,181][245,316]` 移到 `[316,153][458,255]`），再点底部课表 tab，用同一条 `adb shell` 内「点一下 + 连拍 12 张 `screencap`」抓切换过程。

- 第 1 张是「我的」页，第 2 张已进入课表且顶栏处于收起态（过渡帧），第 3–12 张**字节完全相同**（每张 363,358 B）。
- 过渡帧与稳定帧的**行距一致**（第 1 节同在 y≈275、第 10 节同在 y≈1590 附近），只有顶栏位置不同 —— 说明过程中没有出现「行高被算大」的拉伸态，修复生效。
- 第 10 节 `20:40` 在 `[18,2306][115,2344]` 完整可见，未被裁。

### 64.5 产物

- `versionCode 73` / `versionName "1.1.21"`
- `lanhang-miuix-release.apk`：8674300 字节，SHA256 `98DE2E5F5FC6546F0206743F36D78ACCE589591970D29C836FF0776C668271AC`
- 本轮一并把第 63 轮（好友申请、通知重做）随版本发布

### 64.6 教训

1. **「尺寸变了就重画」这类守则必须区分方向。** 布局容器的尺寸在动画期间会经过大量中间值，只有「变小导致内容装不下」才是必须立刻响应的信号；「变大」几乎永远是过渡态或留白，按它重排只会制造抖动。
2. **两个触发点必须用同一条判据。** 第 63 轮的 bug 出在两条路径判据不一致（`onTabSelected` 判「不等就重画」、防抖任务判「不等就重画」但时机不同）；这一轮把两处统一成「变小才重画」，才不会互相打架。
3. 模拟器上 `screenrecord` 不可靠（`--time-limit 7` 实际只产出 1.36 秒），验证瞬时过渡应改用「同一条 `adb shell` 内连点 + 连拍 `screencap`」。注意 PowerShell 双引号里的 `\$i` 不会传给远端 shell（会变成空变量名），要用单引号字符串整段传给 `adb shell`。
## 63. 第六十三轮（2026-11-12）：课表截断复现修复、周次拖拽多选、好友申请、通知重做

### 63.1 用户提出的四条

> 第一，从工作圈页下滑一段位置后，切换回课表页，课表页依旧会被异常截断。第二，上课周次页，做一个长按并拖拽时批量选择周次的手势，将1 2 3 4 5 6等复选框与底部背景的边距加大一些。第三，原版蓝航有好友申请功能，加入这个功能并做到私信页面中。第四，参考原版lanhang的通知代码，重做通知功能

### 63.2 第一条：课表被截断（这类问题的第三次修）

第 53 轮修过「切 Tab 后课表拉伸」，第 55 轮修过「二级页返回后课表被拉伸」，第 58 轮修过「工作圈往返后课表伸长」，这次是同一族的第四个入口。

**根因链**：课表顶栏是「大标题 → 小标题」随滚动收起的（`ui/shell/ShellBars.kt` 的 `rememberBarCollapse`，`ShellScroll.report` 上报进度）。顶栏收起时 `fragmentContainer` 变高，展开时变矮。`ScheduleFragment.onTabSelected()` 里用 `root.post { }` 做高度自检重画，但这个 post 经常赶在上一页的旧布局还没结算完时执行：此时读到的 `root.height` 是「顶栏仍收起」时的较大值，恰好与上次记录的 `lastLayoutHeight` 相等 → 判定「高度没变」直接跳过重画；紧接着布局结算完，容器缩回较小值，网格却仍按较大高度算的行高排布 → 底部第 10 节被 `ScrollView` 裁掉。

**修法（`schedule/ScheduleFragment.kt`，四处）**：

1. 监听 `gridScroll` 自身的高度变化，而不是只信 `root.post` 的一次采样：
   ```kotlin
   gridScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
       if (bottom - top != oldBottom - oldTop) {
           gridScroll.removeCallbacks(gridRelayoutTask)
           gridScroll.postDelayed(gridRelayoutTask, 160L)
       }
   }
   ```
   160ms 防抖是必须的：顶栏收起动画是 300ms，逐帧都会触发 layout 变化，不防抖会连续重画十几次。
2. 新增防抖任务 `gridRelayoutTask`，只有在「高度确实变了 + 当前是网格视图 + Fragment 还活着」时才重画：
   ```kotlin
   private val gridRelayoutTask = Runnable {
       if (!isAdded || view == null || !::gridScroll.isInitialized) return@Runnable
       val h = gridScroll.height
       if (h > 0 && h != lastLayoutHeight && showingGrid) renderGrid()
   }
   ```
3. `renderGrid()` 末尾回写 `lastLayoutHeight` 时改记「真正决定 rowH 的那个高度」：`gridScroll.height`，拿不到才退回 `view?.height`。
4. `onTabSelected()` 的高度自检也改成优先读 `gridScroll.height`，读不到才退回 `root.height`。

**教训**：这类「回来看就被拉伸/截断」的问题，症结从来不是算错了高度，而是**采样时机**。任何只在 `post {}` 里采一次高度的自检都会输给「顶栏动画 + 异步布局结算」这两个变量。可靠做法是给容器挂 `addOnLayoutChangeListener`，让布局自己通知你，再配一次防抖。

**实测**（模拟器 1220×2656）：工作圈页因模拟器无外网始终「加载失败」滚不动，改用「我的」页走同一机制（同为 Compose LazyColumn，同样 `ReportListScroll(mineListState, ShellState.TAB_MINE)` 上报顶栏收起）。我的页下滑三段，标题从 `[59,181][245,316]` 缩为 `[317,153][459,255]`（确认顶栏已收起），再切回课表：第 10 节时间 `20:40` 位于 `[18,2306][115,2344]`，高 38px，教室 `张磊` 位于 `[146,2286][318,2341]`，与首次进入课表时的坐标逐像素一致，未被裁。

### 63.3 第二条：上课周次长按拖拽多选 + 加大底边距

**关键发现**：长按拖拽的骨架代码第 60 轮就写好了，变量 `weekGridSize` / `dragAnchor` / `dragBase` / `dragSelect`、函数 `weekAt(x, y)` / `applyWeekRange(...)` 全在文件里，`detectDragGesturesAfterLongPress`、`pointerInput`、`onGloballyPositioned` 三个 import 也都在——**但从来没挂到任何 Composable 上**，所以手势完全无效。属于「写了没接线」的典型。

**修法（`schedule/ManualCourseScreen.kt`）**：

1. `applyWeekRange(from, to, base: Set<Int>, select: Boolean)` 改成显式传参。原来它是读 `dragBase` / `dragSelect` 两个 state 的，而手势协程在 `pointerInput(Unit)` 里创建后不随重组更新，闭包捕获的是首帧的 state 对象，时序不可靠。
2. 周次网格外层 `Column` 加 `.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp)`（用户要求「复选框与底部背景的边距加大一些」），内部再套一层 `Box` 承载手势：
   ```kotlin
   .fillMaxWidth()
   .onGloballyPositioned { weekGridSize = it.size }
   .pointerInput(Unit) {
       detectDragGesturesAfterLongPress(
           onDragStart = { offset ->
               val start = weekAt(offset.x, offset.y)
               if (start == null) dragAnchor = -1
               else {
                   val base = weeks
                   val add = start !in base
                   dragAnchor = start; dragBase = base; dragSelect = add
                   applyWeekRange(start, start, base, add)
               }
           },
           onDrag = { change, _ ->
               if (dragAnchor > 0) {
                   val now = weekAt(change.position.x, change.position.y)
                   if (now != null) applyWeekRange(dragAnchor, now, dragBase, dragSelect)
               }
           },
           onDragEnd = { dragAnchor = -1 },
           onDragCancel = { dragAnchor = -1 },
       )
   }
   ```
   手势必须挂在**整块网格**上而不是每个 Chip 上：挂在 Chip 上时手指一旦划过格子边界就出了该 Chip 的命中区，拖拽立刻中断。

**实测**：`adb shell input motionevent DOWN 150 1659` → 停 900ms → 连续 `MOVE` 到 `886 1659` → `UP`。截图确认第 1–5 周全部变蓝选中，第 6 周及之后保持未选，一次拖拽批量选中成功；19、20 两个 Chip 与卡片灰色底边之间留有约 58px（≈20dp）间距。

### 63.4 第三条：好友申请（私信 → 新的朋友）

接口全部来自原版蓝航反编译的 `cn/rongcloud/rce/lib/FriendTask`（jadx 1.5.1，原版 APK `蓝航_1.0.14.apk`）：

| 用途 | 方法与路径 | 备注 |
| --- | --- | --- |
| 拉申请列表 | `GET /friends/request_list?version=<long>` | 返回 `{data:[...], version}`，`version` 传 0 取全量 |
| 清未读 | `PUT /friends/clear_unread` | **是 PUT 不是 POST**，body `{}` |
| 接受申请 | `POST /friends/accept/<requestId>` | **传 requestId，不是 uid** |
| 删除申请 | `DELETE /friends/request_list/<requestId>` | |
| 发起申请 | `POST /friends/invite` | body `{"id": 目标uid, "content": 验证语}` |

`FriendRequestInfo` 字段（Gson 按字段名匹配，原版无 `@SerializedName`）：`uid`、`name`、`portrait_url`、`requestId`、`state`、`content`、`read_state`、`user_type`、`create_dt`、`update_dt`。`state`：0=INVITE、1=INVITED、2=ACCEPT、3=ACCEPTED、4=TIME_OUT。

**改动**：

- 新建 `engine/.../net/FriendModels.kt`：`FriendRequest`（带 `pending` / `unread` 两个派生属性与五个 `STATE_*` 常量）+ `FriendRequestList`。
- `engine/.../net/RceApi.kt`（11,308 → 15,862 字节）：补 `put()` / `delete()` 两个泛型辅助方法（原来只有 `get` / `post`），新增 `friendRequests(version)`、`acceptFriend(requestId)`、`clearFriendUnread()`、`deleteFriendRequest(requestId)`、`inviteFriend(uid, content)` 五个方法，失败时把原因留在 `lastFriendRequestError`。
- 新建 `im/FriendRequestsScreen.kt`：「新的朋友」页。进页拉列表，拿到非空列表就顺手清未读；接受成功就地摘掉该行并刷新会话列表；点整行弹确认框可删除；空态与失败态都有独立文案。
- `im/ImScreens.kt`（61,433 → 63,612 字节）：`ImConversationsScreen` 加 `onOpenFriendRequests` 参数，并在会话列表最上方插一个 `item("newFriends")` 入口行（「新的朋友」+「查看好友申请」+「›」）。
- `mine/MineScreen.kt`：`MinePage` 加 `FRIEND_REQUESTS = 16`，`depth()` 归到 2，`titleRes()` 补标题；私信页把回调透传下去，并新增整页分支。
- `mine/MineFragment.kt`：`onOpenFriendRequests = { open(MinePage.FRIEND_REQUESTS) }`，走 `open(entry)` 的压栈逻辑，返回自动回私信。
- `res/values/strings.xml`：补 12 个键。

**坑**：`FriendRequestsScreen.kt` 首次编译报 `Smart cast to 'String' is impossible, because 'content' is a public API property declared in different module`。`content` 是 engine 模块里的 public 属性，跨模块时 Kotlin 不敢做智能转换。改用局部变量 `val contentText = request.content.orEmpty()` 即可。

**实测**：私信页出现「新的朋友 / 查看好友申请 ›」入口，点进去标题正确。模拟器无网（DNS 解析不到 `nginx-1.app.nchu.edu.cn`），页面按设计显示失败条 + 空态「暂时没有新的好友申请 / 别人加你之后会出现在这里」。**真实好友申请数据需要校内网络环境验证**。

### 63.5 第四条：通知功能重做

原版 `cn/edu/nchu/nahang/ui/me/SetNotificationActivity` 有三项：接收新消息通知开关、通知显示消息详情开关、免打扰时段。原版是调融云 `RongIM.setNotificationQuietHours("00:00:00", 1439)` 这类接口，**但本项目没有融云推送通道（`app_key` 为空），那些调用在这里不生效**——我们的通知是本机 `NotificationManager` 自己弹的（`im/ImNotify.kt`）。所以三项设置照做，判定逻辑改在本机实现。

**新增文件**：

- `im/NotifySettingsStore.kt`：SharedPreferences（`notify_settings`）存 `enabled` / `detail` / `quiet` / `quiet_start` / `quiet_end`。核心是 `inQuietHours(nowMinute)`，按跨零点处理：`span = ((end-start)+1440)%1440`、`offset = ((now-start)+1440)%1440`、`offset < span` 即在区间内；`start == end` 视为未设置。默认时段 23:00–07:00。
- `im/NotifySettingsScreen.kt`：「新消息通知」（总开关 + 详情开关，总开关关掉时详情行变半透明不可点）、「免打扰」（开关 + 开始/结束时间，点开就地展开无限滚轮）、页脚说明本机通知的口径。

**`im/ImNotify.kt` 四处补丁**（4,705 → 7,876 字节）：`showMessage` 开头的权限检查之后加「总开关关闭则直接 return」「处于免打扰时段则直接 return」两个早退；通知标题与正文按 `showDetail` 决定是否替换为「新消息 / 你收到一条新消息」；每次 `notify(...)` 与 `cancel()` 之后调 `applyBadge()`，反射 `io.rong.notification.shortcutbadger.ShortcutBadger`（备选 `me.leolin.shortcutbadger.ShortcutBadger`）把当前通知条数写到桌面图标角标，两个类都没有就静默跳过。

**接线**：`MinePage.NOTIFY_SETTINGS = 17`（`depth()` 归 2）；设置页「上课提醒」行下面加分隔线与「通知」行，副标题动态显示 `已开启` / `已关闭` / `免打扰 23:00–07:00` / `缺少通知权限`；`App.kt` 在 `ClassReminderStore.init(this)` 之后加 `NotifySettingsStore.init(this)`；`strings.xml` 补 18 个键。

**坑**：`MineScreen.kt` 里有两个 `when`——L300 起那个是**页面渲染**（每个分支返回 Composable），L487 起那个在 `LazyColumn { }` 内部（每个分支是 `LazyListScope` 扩展，如 `settingsDirectoryItems(...)`）。我第一次把 `MinePage.NOTIFY_SETTINGS -> Box(...)` 插到了后者，编译报 `@Composable invocations can only happen from the context of a @Composable function`。改插到前者（锚点用 `MinePage.PROFILE -> Box(pageModifier) {`，先校验唯一性）后通过。**改这两个 when 之前一定先分清哪个是哪个。**

**实测**：设置页出现「通知 / 已开启」行；点进去三项齐全，两个开关默认开、免打扰默认关；打开免打扰后出现「开始时间 23:00」「结束时间 07:00」；点开后面板内的无限滚轮显示 `22 / 23 / 00` 与 `55 / 00 / 05`，滑动小时轮后值行同步变为 `00:00`，循环与联动都正常。验证完已把免打扰关回关闭状态。

### 63.6 产物与教训

- APK `app-release.apk` = 8,674,300 字节，SHA256 `954679A27EFE893B689466042AFE232275C65E6858E930F4FEEA5FEA82673A10`，已复制为项目根 `lanhang-miuix-release.apk` 并装机验证。
- 改 engine 必须先在 `src/lanhang-lite-main` 跑 `:engine:assembleRelease`，再把 `engine/build/outputs/aar/engine-release.aar` 复制成 `app/libs/engine.aar`，然后才能构建 app——app 依赖的是这个本地 aar 文件，不是 project 依赖。
- 模拟器 `adb shell input motionevent <DOWN|MOVE|UP> <x> <y>` 是验证长按拖拽手势的正确手段：`input swipe` 的轨迹是从起点线性插值到终点，中间没有静止段，触发不了长按。
- 本轮四条全部落地，其中第一条与第三条的「真机/校内网验证」仍待补。
## 62. 第六十二轮（2026-11-04）：第 10 节课被底部裁掉

### 62.1 现象

用户截图（模拟器 1220×2656）显示第 10 节的 `19:55` 正常，但 `20:40`、教室名、教师名被屏幕底部 tab 栏截断或完全看不见。

### 62.2 实测（`uiautomator dump`）

- `fragmentContainer` `[0,351][1220,2397]`、`gridScroll` `[18,483][1202,2382]`、`shellBottomNav` 顶边 2397
  —— 网格底边 2382 **并没有**被 tab 覆盖，问题在内容自身高度超出可用区域
- 第 9 节 `19:00` y1=2163、第 10 节 `19:55` y1=2334，行距 171px
- 第 10 节内部：`20:40` 只占 2372–2382（10px，正常应 38px）；`张磊` 2356–2382（26px，被裁）
- `wm density` = 468 → density 2.925（不是 2.75），`dp(x) = x * 2.925`

### 62.3 根因：多算了一个 `gapH`

- `usable = availH - panelPad - headerRowH - bandH * visibleBreaks.size - gapH * (rowCount - 1)`
  —— 按「行数 − 1 个间隙」扣
- 但时间列每格 `LinearLayout.LayoutParams(timeW, rowH).apply { bottomMargin = gapH }`
  **最后一行也带了 bottomMargin**，于是 timeCol 实际高 = `rowCount * (rowH + gapH)`
- 块区 `val gridH = yOfRow(rowCount - 1) + rowH + gapH` 同样多算一个 `gapH`
- 两者都多出一个 `gapH`（density 2.925 下约 14.6px），内容总高比可视区高出这一格，
  `ScrollView` 把最后一行裁掉

### 62.4 修法（`schedule/ScheduleFragment.kt`）

1. 时间列最后一格不再带尾部间隙：

```kotlin
layoutParams = LinearLayout.LayoutParams(timeW, rowH).apply {
    // 第六十二轮：最后一行不再带尾部间隙 —— 块区高度按「行数 × 行高」
    // 收尾，这多出来的一个 gapH 会把第 10 节顶出可视区被 ScrollView 裁掉。
    if (p != rowOrder.last()) bottomMargin = gapH
}
```

2. 块区高度去掉尾部 `gapH`：

```kotlin
// 第六十二轮：原来这里多加了一个 gapH（最后一行下方的空隙），
// 导致整块内容比可用高度高出一个间隙，最后一行被裁。
val gridH = yOfRow(rowCount - 1) + rowH
```

两处必须**同时**改：只改一处会让时间列与块区的行基线错开 `gapH`，课程块整体上移或下移。

### 62.5 实测复核（改后）

- `20:40` `[18,2306][115,2344]` —— 高 38px，完整
- `张磊` `[146,2286][318,2341]`、`张中华` `[363,2286][535,2341]`、`马明辉` `[797,2286][969,2341]`
  —— 三行全部落在 `gridScroll` 底边 2382 之内，底部还余 38–56px
- 行距由 171px 收为 164px（每行让出 7px）
- 第 11 周（课程更密）复核同样完整；列表视图不受影响

### 62.6 教训

- 「可用高度扣了几个间隙」必须和「内容实际排布了几个间隙」逐一对齐。
  这里 `usable` 扣 `rowCount - 1` 是对的，错的是内容多排了第 `rowCount` 个间隙。
- 容器本身没被 tab 覆盖时不要急着改 `fragmentContainer` 的约束或加 padding
  （第 58 轮就曾误判为「底栏是浮层」而多扣 66dp，第 59 轮已推翻）；
  先用 `uiautomator dump` 对比容器底边与内容实际底边，能立刻分清是「被挡」还是「溢出」。
- `ScrollView` 会静默裁掉溢出内容，界面上没有滚动条提示，
  所以最后一行被裁时看起来像「布局少了一行」，实际是高度算多了一格。
## 60. 第六十轮（2026-11-03）：周次默认全不选 / 编辑课程改二级页 + 圆形选择框 / 检查更新

### 60.1 上课周次默认全不选
`schedule/ManualCourseScreen.kt`：`weeks` 初始值从「1..MAX_WEEK 全选」改为
`(initial?.weeks ?: emptyList()).toSet()`（新增时为空集，编辑时回填原周次）；
`weekMode` 初始值 `if (initial == null) "" else …`，避免新增时「全部」呈现为选中态。
- 「全部/单周/双周」三档的 `RadioTag` 现在可以**再点一次取消**：`weekMode = ""`、
  `weeks = emptySet()`、`oddEven = ""` —— 三档一个都不选是合法状态，之后由用户在
  1–20 周网格里自己挑特殊周次。
- 手动点任意周次按钮会清掉 `oddEven` / `weekMode`，因为已经不是「全部/单双周」的快捷态。

### 60.2 编辑课程 = 添加课程的二级页
原先「编辑」是手写的双层 `animateFloatAsState` + `translationX` 覆盖动画，返回时直接
连根覆盖层一起关掉，与其它页面的返回语义不一致。本轮整段换成既有的导航栈组件：

```kotlin
MiuiNavStack(
    key = editing?.let { EditPageKey(it.id) } ?: AddPageKey,
    depthOf = { if (it is EditPageKey) 1 else 0 },
) { … }
```
- 新增 `private object AddPageKey`、`private data class EditPageKey(val id: String)` 作为栈 key。
- 编辑页 ✕ 的 `onClose` 改为 `{ editing = null }` —— **只退一层**，回到「添加课程」页
  （表单已清空，符合「新增」的语义），而不是关掉整个覆盖层。
- 过渡动画自动与设置/关于等页面一致（`NavMotion.DURATION_MS = 300`、
  `FastOutSlowInEasing`、父层 `PARENT_TRAVEL = 0.24f` / `PARENT_DIM = 0.18f`）。

### 60.3 选项改成圆形选择框（RadioTag）
新增 `@Composable private fun RadioTag(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`：
20dp `CircleShape`，选中 = `scheme.primary` 实心 + 8dp 白色圆点，未选 = `scheme.surfaceContainerHighest` 空心。
替换了两处：
- 「上课星期」行右侧的「自定义时间」：原来是 `MiniSwitch`（胶囊开关），换成 `RadioTag`，
  `onClick` 里 `customTime = !customTime` —— **语义仍是开关**，只是外观对齐用户给的参考图。
- 「上课周次」行右侧的「全部/单周/双周」：原来三个 `Chip`，换成三个 `RadioTag`。
- 星期芯片（一~日）与 1–20 周按钮仍用 `Chip`，不动。

### 60.4 检查更新（对齐上游）
新增 `settings/UpdateChecker.kt`：
- `object UpdateChecker`，常量 `OWNER = "Nuclear-Potatoes"` / `REPO = "lanhang-lite-miuix"`，
  接口 `https://api.github.com/repos/$OWNER/$REPO/releases/latest`。
- `sealed interface Result { Available(release) / Latest / Failed(message) }`；
  `data class Release(tag, version, title, notes, apkUrl, pageUrl)`，`apkUrl` 取
  `assets[]` 里第一个 `.apk` 结尾的 `browser_download_url`。
- OkHttp connect 8s / read 8s / call 12s；header `User-Agent: lanhang-miuix/<版本>`、
  `Accept: application/vnd.github+json`；404 → `Latest`、403 → `Failed("请求太频繁…")`、其它非 2xx → `Failed("服务器返回 <code>")`。
- `fun isNewer(latest: String, current: String): Boolean` **逐段比数字**，缺段按 0 ——
  `1.1.9` 与 `1.1.20` 按字符串比会得出「9 > 20」的错误结论。
- `mine/MineScreen.kt`（45,148 → 53,341 字节）：`settingsAboutItems` 加 `updateChecking` /
  `updateHint` / `onCheckUpdate` 三个参数，在「应用」卡里加一行「检查更新」（检查中显示
  「正在检查…」、完成后摘要变「已是最新」或「有新版 x.y.z」）。
- 状态 `checkUpdateContext` / `checkUpdateScope` / `updateChecking` / `updateHint` 必须提到
  `MineScreen` **顶层**：放进 `LazyColumn` 的 item 里会在滚出屏幕时被回收，检查结果直接丢失。
- **坑**：`onCheckUpdate` 是普通 lambda 不是 `@Composable`，里面**不能**用 `stringResource`，
  全部改 `ctx.getString(R.string.xxx, args)`。
- `res/values/strings.xml` 50,244 → 51,274 字节，新增 12 个 `settings_check_update*` 键。
- 实测：GitHub API 联网正常，弹窗「检查更新 / 当前已是最新版本（1.1.20）。/ 确定」。

### 60.5 连带修掉空周次文案的「· 周」
`weeks` 允许为空之后，课程摘要会拼出孤零零的「周」：「Test　一 · 第 1 节 · 周」。
改为条件拼接：
```kotlin
append(" 节")
if (c.weeks.isNotEmpty()) {
    append(" · ").append(compressWeeks(c.weeks)).append(" 周")
}
```

### 60.6 血的教训：整段替换 Composable 树吃掉了 `}`
本轮的「Chip → RadioTag」是用整段字符串替换做的，`Chip(text = label, selected = day == index,`
之后的 `onClick = { day = index },` 与 `)` 被吃掉，连带 `forEachIndexed` 与 `Row` 的 `}` 一起丢，
净少 3 个 `}`。症状极具误导性：
- Gradle 首报 `ManualCourseScreen.kt:165:17 Unresolved reference 'ManualCourseEditor'`、
  `223:1 Modifier 'private' is not applicable to 'local function'` —— 因为缺 `}` 让**后面**所有
  顶层函数变成嵌套函数，报错全落在后续组件上，真正病灶在更早的替换处。
- 第二轮 80 条 `Unresolved reference 'SectionCard'/'FormRow'/'Divider'/'MiniSwitch'` 同理。

**校验手法（本轮确立，务必沿用）**：
1. 逐行累加 `{` / `}` 打印 `行号 d=旧→新 原行`，找结构断点；
2. **net depth 归零 ≠ 结构正确** —— 存在「提前闭合 + 末尾多闭合」互相抵消的情形，
   必须同时扫「只匹配行首 `fun` / `private fun` / `object` / `data class`」的顶层声明，
   每条都应 `d=0`；某条开始 `d=1` 就说明它前面的函数少关了一层。
3. 一旦有多处 `}` 错位，**逐行补会来回震荡**（本轮 -1 → 0 → -1 反复三次），
   只能把整段（如「上课周次」卡片 66 行）按正确结构重写。

### 60.7 构建与产物
`assembleRelease` 成功（`BUILD SUCCESSFUL`，日志 `%TEMP%\b60e.txt` / `b60f.txt`），
APK 8,644,872 字节 → 项目根 `lanhang-miuix-release.apk`，`adb install -r` 输出 `Success`。
装机复核通过：① 1–20 周与三档周次全部未选；② 编辑进二级页、✕ 只退一层回添加页；
③ 检查更新弹「已是最新」；④ 空周次文案为「Test　一 · 第 1 节」。

### 60.8 上课星期的七个胶囊挤成一团（次日反馈）
用户 m01023 附两张对照图：「你的上课星期挤在一起了，参考图一，应该调整为图二的效果」。

根因：星期那行 `Row` 里的 `Chip` **没有 `weight`**，`Box` 的宽度由内容决定 ——
单个汉字的字号只有 14sp 上下，七个 `Chip` 各自缩成一个窄条，中间只靠 `spacedBy(6.dp)`
撑着，视觉上就是七个小方块贴在一起、几乎连成一排。

修法（`ManualCourseScreen.kt`）：
```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    DAY_LABELS.forEachIndexed { index, label ->
        Chip(
            text = label,
            selected = day == index,
            onClick = { day = index },
            modifier = Modifier.weight(1f).height(44.dp),
        )
    }
}
```
- `weight(1f)` 让七个胶囊**均分整行**（`spacedBy` 先扣掉六个间隙，剩下的按等分）；
- `height(44.dp)` 把高度钉住，宽高比接近 1:1，和参考图里那种「圆角方块」一致；
- 左右内边距从 12dp 改成 16dp，与卡片里其它行（标题、周次网格）对齐。
- `Chip` 本身一行没改 —— 1–20 周次与节次网格原本就用 `weight(1f)`，行为不受影响。

实测 dump（1220×2656、density 2.75）：七个文字节点中心等距 **154px**，胶囊 **132px 宽（≈48dp）× 121px 高（44dp）**；
未选中底色 `surfaceContainerHighest` 比卡片 `surfaceContainerHigh` 略深，边界清楚，不再糊成一片。

**教训**：`Row` + 等宽排布的按钮组，一定要给每个子项 `Modifier.weight(1f)`；
只给 `spacedBy` 的话，宽度会退化成「内容宽 + 间隙」，字越少越挤。

## 59. 第五十九轮（2026-11-03）：课程块改参考截图排版 / 课表底端与 tab 对齐 / 时间列右侧裁字

### 59.1 课表底端没和 tab 对齐（根因是重复扣除）
第 58 轮曾加 `bottomBarReserve = dp(66)` 去躲底部标签栏，实测 `activity_main.xml` 里
`fragmentContainer` 用的是真约束 `app:layout_constraintBottom_toTopOf="@id/shellBottomNav"`，
底边 2397 与 `shellBottomNav` 顶边 2397 本就严丝合缝 —— 那 66dp 是**重复扣除**，
把 `gridContent` 底边压到 2176（比 `gridScroll` 的 2382 少了 206px），于是「课表没铺到 tab」。
- 删掉 `bottomBarReserve`，改 `val availH = rawAvailH.coerceAtLeast(dp(200))`
- 结果：`gridContent [18,483][1202,2366]`，比修复前多 190px，底距底栏 31px

### 59.2 课程块排版对齐参考截图
参考 App（`com.haooz.che`）的课程区：圆角块 + 同色相极浅染底 + 深饱和字，块内三行
（课名 / 地点 / 教师）。按用户 m00427 的修正落地为：
- **居中**（不是参考图的左对齐）：`blockInner.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL`，
  `nameView` / `roomView` / `teacherView` 三者 `gravity` 全部 `Gravity.CENTER`
- **去掉 `@` 前缀**：`roomView.text = room`（曾试过 `"@$room"`）
- **课名贴顶、地点+教师贴底**：`blockInner.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))`
  插在 `nameView` 与 `roomView` 之间当弹性占位
- 新增 `teacherView`：`textSize = nameSize - ts(1f)`、`alpha = 0.8f`、`maxLines = 1`、`ellipsize = END`
- 圆角 `s.rounded(density, 14f, style.courseFill(color))`（原 10f）
- 留白加大：`gapH = dp(5)`（原 3）、`gapW` 三档 `dp(3)/dp(4)/dp(6)`（原 2/3/4）、
  `blockInner.setPadding(dp(5), dp(4), dp(5), dp(4))`
- `limit = (colW - dp(12))`（原 `colW - dp(8)`），`nameW` 上限同步
- 教师字段取自 `ScheduleModels.Course.teacher`（`@SerializedName("教师")`，本来就存在）

### 59.3 第 9/10 节课程块内容被裁
块高不够放「课名 2 行 + 地点 + 教师 + 上下 padding」。加字号收敛循环：
```kotlin
run {
    val padV = dp(10)
    var guard = 0
    while (fitNameSize > ts(7f) && guard < 80) {
        guard++
        val nameLines = kotlin.math.ceil(textPx(nameText, fitNameSize) / limit).toInt().coerceIn(1, 8)
        val sub = (fitNameSize - ts(1f)).coerceAtLeast(ts(6f))
        val roomH = if (room.isEmpty()) 0f else
            kotlin.math.ceil(textPx(room, sub) / limit).toInt().coerceIn(1, 2) * fontH(sub)
        val teachH = if (teacher.isEmpty()) 0f else fontH(sub)
        val need = nameLines * fontH(fitNameSize) + roomH + teachH + padV
        if (need <= h) break
        fitNameSize -= 0.5f
    }
    fitRoomSize = (fitNameSize - ts(1f)).coerceAtLeast(ts(6f))
    while (fitRoomSize > ts(6f) && room.isNotEmpty() && textPx(room, fitRoomSize) > limit) { fitRoomSize -= 0.5f }
}
```
因此 `val (top, h) = blockRect(ci)` 要从 render 中段**提前到函数开头**（收敛需要块高）。

### 59.4 左侧时间列「08:45」的 5 显示不全（用户 m00528）
`val timeW = (textPx("08:45", ts(8f)) + dp(4)).toInt().coerceIn(dp(26), dp(52))`
—— 时间列宽度**写死按 8sp 量**，但 `timeSizeCandidates` 是从 11sp 往下试，
行高够大时选中的是 11sp，「08:45」比格子宽出约三分之一，`setSingleLine(true)` 从右边裁掉。
修法：把「挑字号档位」那段整体上移到 `gapW` 之后、`timeW` 之前，再按**真实字号**量宽度：
```kotlin
val timeW = (textPx("08:45", timeSize) + dp(6)).toInt().coerceIn(dp(26), dp(64))
```
- 循环变量 `ts` 改名 `cand`、标签 `loop@` 改名 `loopPick@`，避免遮蔽 `ts()` 函数
- 无循环依赖：挑字号只依赖 `rowH`，不依赖 `timeW`/`colW`
- `timeW` 约 76px → 97px（实测 `08:45 [18,726][115,764]`），`colW` 自动收窄约 4px/列

### 59.5 验证
| 项 | 结果 |
|---|---|
| 构建 | `BUILD SUCCESSFUL in 2m 25s`，无编译错误 |
| APK | 8,623,244 字节 / SHA256 前缀 `85FB425AF6357567` |
| 时间列 | `08:45 [18,726][115,764]`、`20:40 [18,2306][115,2344]`，右侧不再缺角 |
| 底端对齐 | `gridContent` 底 2366、`shellBottomNav` 顶 2397，距 31px |
| 第 10 节 | `A101 / 张磊`、`A200 / 张中华`、`A104 / 马明辉` 三组全部显示 |
| 往返 | 课表→工作圈→课表，截图 362,925 → 363,653 字节（仅状态栏时钟变了），布局无变化 |

### 59.6 踩坑
- `activity_main.xml` 里 `fragmentContainer` 是**真约束**到 `shellBottomNav` 顶部，底栏不是浮层 ——
  第 58 轮「底栏叠在内容上」的结论是错的，那次的 `bottomBarReserve` 反而制造了 206px 空白。
- 时间列宽度绝不能按「最小候选字号」估 —— 候选择序是**从大到小**，实际选项往往是最大的那个。
- `Tee-Object | Select-String` 会返回假 `[exit code: 1]`，判断构建只看 `BUILD SUCCESSFUL`。
- 启动必须用 `adb shell monkey -p com.lanhang.miuix -c android.intent.category.LAUNCHER 1`，
  `am start -n .../.ui.MainActivity` 会报 `SecurityException: not exported`。
# AGENTS.md — 蓝航Lite（com.lanhang.miuix）

南昌航空大学官方 App「蓝航」（`cn.edu.nchu.nahang`，UA 标识 `NchuApp/1.0.14`）的第三方轻量客户端。
功能范围：账号登录 + 课表原生渲染（周视图/网格）+ 成绩（学期切换/统计）+ 工作圈（融云圈动态）+ 设置（亮/暗/跟随系统主题、课表导出 .ics/.csv/分享）；底栏四 Tab = **课表 / 工作 / 工作圈 / 我的**（2026-10-26 第六轮把原「成绩」与「我的 → 工作」互换：工作升为一级 Tab，成绩进「我的」二级页）；「我的」下挂**成绩** / 通讯录 / 消息 / **私信（融云 IM 2.9，2026-10-26 接入）** / 身份码 / 相册 / 个人资料 / 设置。不做 OA 应用列表。
（成绩曾被用户移除，2026-10-21 应用户要求加回。）

### 执行纪律（用户红线，2026-10-27 立）

> 用户原话：「以后绝对不能出现一个任务执行两小时的情况」。

这不是建议，是硬约束。落地成下面几条，每轮开工前先对照：

1. **单轮预算 ≈ 40 分钟**（含构建、安装、截图、写文档）。到点就把「已完成 / 未验证」照实讲清收尾，
   不准为了补一张图继续拖。
2. **单次等待上限 120 秒 × 最多 2 次**。禁止用 `Start-Sleep` 十几分钟去等一个「真实时间窗口」
   （课中、整点、跨天）。要那个窗口就换手段：
   debug-only 的「假装现在几点」注入开关 / PIL 预览图 / 单测，或者直接留给用户下次看。
3. **单步失败两次就停手汇报**：构建、安装、UI 定位、坐标点不中，各给两次机会，第二次还不行就
   说明现状 + 换方案，不再盲试第三种坐标。
4. **优先做用户真正要的功能**，验证按「能快速验证的优先」排序；耗时最长的验证放最后，
   且允许不做。做不到就明写「本轮未验证 + 原因 + 下次怎么验」。
5. **不做无害但费时的自动收尾**（重建预览图、反复截同一页、反复 dump 界面）。省下的时间用来
   把用户提的四件事做对。

---

## 58. 第五十八轮（2026-11-01）：课程文字铺满格子并放大 / 工作圈往返课表拉伸 / 工作圈图片压缩 + 按需显示原图 / 第 10 节被底栏遮住 / 教室名贴底

用户这一轮给了三条，随后又追加了三条反馈，全部落在课表网格与工作圈：

1. 「课表主页，课程文字四行换行做得很好，但是没有铺满整个课程格子，导致所有文字都挤在一个小范围里，要将文字在课程范围内均匀铺开。」
2. 「如果在课表页点击工作圈后再返回，课表会伸长到屏幕之外。」
3. 「工作圈中的图片不一定要加载原图，可以进行压缩以加快加载速度，在屏幕右下角加一个显示原图的按钮，只有按这个按钮时才加载原图」
4. 「我已经安装测试，你只是将字平铺了，但是字号并没有变大，要将平铺开的文字一并放大」
5. 「取消过大的行间距，所有文字向上对齐」
6. 「观察界面，现在第十节课超出了屏幕范围，无法正常显示，并且教室名需要放在课程区域的最底端」

### 58.1 课名铺满格子：字号要真的变大，行距不要摊开（`ScheduleFragment#renderGrid` / `render()`）

第 4 条反馈说明「平铺」被理解错了方向：把行距拉开只让文字「铺开」，字号一点没涨，看起来还是一小团字。

**字号基准（`nameSize`）**：

```kotlin
val nameSize = run {
    val availWSp = (colW - dp(6)).toFloat() / scaledDensity
    val availHSp = (rowH - dp(10)).toFloat() / scaledDensity
    // 宽度按「一行 3.5 个字」估，高度按「块内 2.9 行」估；上限提到 24sp，让大格子真正吃到大字号
    minOf(availWSp / 3.5f, availHSp / 2.9f).coerceIn(ts(8f), ts(24f))
}
```

第 56 轮是 `/4.2f`、`/3.2f` 夹在 `7.5~19sp`：按 4.2 字估宽会让字号白白小一圈（四字一行本来就只需要 4 个字宽），上限 19sp 又把高屏的大格子卡死。

**逐课收敛只保证「一行放得下 4 个字」**（原来量的是整串课名，10 字长名会被一路压到 7sp，这才是「字没变大」的真正原因）：

```kotlin
var fitNameSize = nameSize
while (fitNameSize > ts(8f) && textPx("国国国国", fitNameSize) > limit) {
    fitNameSize -= 0.5f
}
```

**垂直排布**：课名贴顶、教室名贴底、中间一个 weight=1f 的空占位把两边顶开 —— 课名占 1 行还是 5 行，教室名都稳稳在块底。不再用 `setLineSpacing` 把字摊到整块高（第 5 条反馈），行距固定 `1f`。

### 58.2 从工作圈返回后课表被拉长（第 2 条）

与 §53.4「切 Tab 课表拉伸」同源：`onTabSelected()` 里高度自检被写在 `contentFilled() != 0` 的分支里，`renderAll()` 那条路径从不刷新 `lastLayoutHeight`，于是「重新渲染过但没记下新高度」→ 下一次比较永远不等 → 网格按旧高度算 `rowH`。

修法：自检提到分支外无条件执行；`renderGrid()` 末尾回写 `lastLayoutHeight = view?.height ?: lastLayoutHeight`。**回写必须放在 `renderGrid()` 末尾**，否则每次都「高度不等」→ 无限重绘。

### 58.3 工作圈图片压缩 + 右下角「显示原图」（`moments/MomentsScreen.kt` / `moments/ImagePreviewActivity.kt`）

- **九宫格**：`ImageRequest.Builder(imgCtx).data(url).size(cellPx, cellPx).crossfade(120)`，`cellPx = LocalWindowInfo.current.containerSize.width / 3`。服务端常见 2000~4000px 的图，按格子尺寸解码省约一个数量级的内存与解码时间。
- **查看器**：`PreviewAdapter` 新增 `originalPages`（以 position 为键）、`screenW`、`screenH`；`onBindViewHolder` 里 `if (position in originalPages) size(Size.ORIGINAL) else size(screenW, screenH)`。
- **按钮**：代码动态创建的 `TextView`（`取得右下的 Gravity.BOTTOM or Gravity.END`，半透明黑底 + 白色描边圆角），`onCreate` 里 `binding.root.addView(originalButton)`；`updateIndex()` 每次翻页按 `position in originalPages` 切可见性，点过后该页隐藏按钮。
- `strings.xml` 新增 `moments_show_original` = 显示原图。
- 前置确认：`DragDismissLayout.onInterceptTouchEvent` 只在垂直拖动超 slop 时返回 true，`ACTION_DOWN` 返回 false，所以代码添加的按钮能正常收到点击。

### 58.4 第 10 节被底部标签栏遮住 + 教室名贴底（第 6 条）

`uiautomator dump` 实测（1220×2656、density 2.75）：第 9 节 `19:00` 在 y=2209-2247、`19:45` 在 2247-2285，第 10 节只剩节次号 `10`（y=2327-2382），`19:55` / `20:40` 节点**完全不存在**；而底部标签栏在 y=2498-2549。

真因：**底部标签栏是叠在内容之上的浮层**（`fragment_schedule.xml` 里 `contentGrid` 是 `0dp + weight=1`，标签栏不在同一个线性布局里），所以 `gridScroll.height` 把被标签栏盖住的约 158px（≈57dp）也算成了可用高度，`rowH` 算出偏大，最后一行被压进标签栏底下。

修法：

```kotlin
val rawAvailH = gridScroll.height
val bottomBarReserve = dp(66)                                   // 158px / 2.75 ≈ 57dp，再留余量
val availH = (rawAvailH - bottomBarReserve).coerceAtLeast(dp(200))
if (rawAvailH == 0 && !gridRelayoutPending) { /* 首帧补一次重绘 */ }
```

`usable` / `rowH` 的公式一个字没动（行高下界仍是 `dp(12)`，§57.5）。占位 View 就是 `blockInner.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))`。

### 58.5 本轮验证（模拟器 emulator-5554，1220×2656，第 9 周，深色）

| 项 | 结果 |
| --- | --- |
| `assembleRelease` | ✅ `BUILD SUCCESSFUL in 1m 46s`，无编译错误 |
| APK 放根目录 | ✅ `lanhang-miuix-release.apk` 8,623,244 字节，SHA256 前缀 `7B9B10DC0EB98AB0` |
| ① 字号放大 + 顶对齐 + 紧凑行距 | ✅ 截图：字号明显变大，文字自块顶起排；「习近平新/时代中国/特色社会/主义思想/概论」完整 5 行，只有极长的串被省略 |
| ⑥ 第 10 节完整可见 | ✅ `19:55` / `20:40` 都在标签栏上方；教室名 E302/C402/A101/B108 等全在每块最底端 |
| ② 工作圈往返不拉伸 | ✅ 课表 → 工作圈 → 课表，往返前后截图布局一致（297,893 字节 vs 基线） |
| ③ 图片压缩 | ✅ 缩略图/预览按屏幕尺寸解码；「显示原图」按钮 dump 实测 `中心=(1046,2477)` |
| ③ 按需原图 | ✅ 点击后按钮隐藏，截图从 3,579,217 → 3,753,166 字节（重新按原尺寸解码生效） |
| 测试数据清理 | ✅ 无残留手动课 |

本轮未 push（用户未要求），版本号仍是 72 / 1.1.20。

### 58.6 本轮踩坑

- **`string.Length` 是字符数不是字节数**：`[IO.File]::ReadAllText` 返回 .NET `string`，`.Length` 数的是字符，`ScheduleFragment.kt` 107,255 字节却只有 87,458 个字符 —— 用 `-lt 90000` 做「文件异常」守卫会误报中止（好在上限检查在 `WriteAllText` 之前，没写坏文件）。**字节校验一律用 `(Get-Item $p).Length`。**
- **重复导入直接把编译打挂**：给 `MomentsScreen.kt` 补 `import coil.request.ImageRequest` 时文件里已经有了，报 `e: .../moments/MomentsScreen.kt:65:21 Conflicting import: imported name 'ImageRequest' is ambiguous.`（同样 67:21）。**加 import 前先 grep 一遍。**
- **行距摊高会裁掉最后一行**：`mult = nameAvailH / (lineCount * lineH)` 看着像「均匀铺开」，实际最后一行常被裁掉；用户否掉后换成 `setLineSpacing(0f, 1f)` + 顶部对齐。
- **教室名只按 1 行估算会把课名挤掉**：「综合楼南310」会折成 2 行，`roomBlockH` 只算 1 行就会把课名可用高度估多，第 4 行字被裁。要 `ceil(textPx(room, fitRoomSize) / limit).coerceIn(1, 2)`。
- **验证红线又差点破**：把 dump 里 `中心=(180.5, 618.5)` 的「赞」按钮当成了图片，点下去把第一条动态点赞了（截图出现「已赞 10」、点赞者列表多了自己），撤销时 adb 还掉线了。重启模拟器、重装后 dump 确认只剩「赞 0 / 赞 0 / 赞 2」才收手。**点之前先看节点的 `text` / `content-desc`，别只看 bounds 形状。**
- **坐标只能取自 `uiautomator dump` 的 bounds 中心**：同一张截图里 344×344 的图片在 `中心=(254,841)`，风扇图在 `中心=(254,1355)`；点 `(200,700)` 命中的是头像（会跳进个人主页）。

## 57. 第五十七轮（2026-11-01）：课程名四字一行 / 手动课详情加编辑 / 时间滚轮过渡动画 + 时间同步 / 课表超界自适应

用户这一轮给了五条，都在课表与添加课程页：

1. 「课表页的课程文字设为四字一行，超过自动换行，如果有课程的名字过于长导致无法正确显示（比如超出课程边界），则显示省略。」
2. 「点击用户自己手动添加的课程时，对话框应多一个编辑按钮，点击后进入编辑课程页面，注意动画的连贯性。」
3. 「上课时间中数字之间的切换不连贯流畅，设计一个过渡动画。」
4. 「上课时间中开始时间与结束时间设置好后，右上角的时间与下面的时间不同步，在凌晨12点交界处尤其明显，修复这些问题」
5. 「如果设置的课程时间超出课表界限，课表应重新计算合适的大小并自适应显示在一个屏幕内，修复这些问题」

### 57.1 课程名严格四字一行（`ScheduleFragment#renderGrid`）

旧实现只把 `nameView` 撑到 `colW - dp(2)`（整列宽），于是「焊接技术与工程导论」在一列 200px 里能挤下 6 个字，换行落点由系统断行决定，长短课名的行数完全不可控，长课名还会把 `roomView` 顶出块外。

改法：**按「四个汉字」实测像素宽**来定 `nameView` 的宽度。

```kotlin
// 静态初始化：宽度按基准字号量四个汉字
nameView.layoutParams = LinearLayout.LayoutParams(
    (textPx("国国国国", nameSize).toInt() + dp(3)).coerceAtMost(colW - dp(2)), 0, 1f)
```

`render()` 里字号会随块高连续变化（见 56.2），所以每次重绘都要用**当次的实际字号**重算宽度，写到同一个 LayoutParams 上：

```kotlin
val nameW = (textPx("国国国国", fitNameSize).toInt() + dp(3)).coerceAtMost(colW - dp(2))
(nameView.layoutParams as LinearLayout.LayoutParams).also { it.width = nameW }
```

量的是 `"国国国国"`（四个最宽的常用汉字）而不是 `"XXXX"` —— 西文宽度只有汉字一半，用西文量会把宽度定窄，中文课名一行就塞不下四个字。

`maxLines` 由块高反算，保证文字不出块：

```kotlin
val lineH = fontH(fitNameSize)
val roomBlockH = if (room.isEmpty()) 0f else fontH(fitRoomSize)
val nameAvailH = (h - dp(8) - roomBlockH).coerceAtLeast(lineH)
nameView.maxLines = (nameAvailH / lineH).toInt().coerceIn(1, 8)
```

超长名的省略靠 `nameView.ellipsize = TextUtils.TruncateAt.END`（`roomView` 同样加了）。真机效果：「焊接技术与工程导论」→「焊接技术 / 与工程导 / 论」，「《何以为美：大学美育导引》」→ 四行不溢出，「习近平新时代中国特色社会主义思想概论」→ 五行刚好放下。

### 57.2 手动课详情多一个「编辑」按钮（`CourseItem` / `ManualCourseStore` / `ScheduleFragment` / `ManualCourseScreen`）

数据层加一个字段把「这门课是谁」带到渲染层：

- `CourseItem` 新增 `val manualId: String? = null`（教务课为 `null`）
- `ManualCourse.toCourseItem()` 里传 `manualId = id`
- `mergeCourses()` 用 `cur.copy(periodEnd = next.periodEnd)` 合并，`copy` 不丢新字段，合并后的块保留首条的 id —— 手动课本身按节存行，够用

详情弹窗按 `manualId` 决定出不按钮：

```kotlin
val actions = buildList {
    val mid = ci.manualId
    if (mid != null) add(getString(R.string.common_edit) to {
        ManualCourseNav.pendingEditId = mid
        showManualCourseEditor()
    })
    add(getString(R.string.common_ok) to {})
}
```

`MiuixDialogSpec` 的按钮样式规则是「最后一个为主色、前面的是 10% 灰次按钮」，所以「编辑 + 确定」天然读作「次操作 + 主操作」，不用改对话框。`strings.xml` 补 `common_edit` = 编辑。

**动画连贯性**是这条的重点。编辑页是添加页的二级页（`progress` 0→1 时编辑页从右侧滑入、添加页左移 `NavMotion.PARENT_TRAVEL`、中间压 18% 黑遮罩），而弹窗点「编辑」时外层覆盖层本身也在滑入 —— 如果编辑页再从 `progress = 0` 起一次二级动画，用户会看到「整页滑入 → 页面里又滑一次」两层叠加。

做法：给 `ManualCourseNav` 加一个一次性信标 `pendingEditId`，`ManualCoursePage` 首次组合时读走并清空，直接把 `editing` 初始化成对应的 `ManualCourse`：

```kotlin
val initialEditing = remember {
    ManualCourseNav.pendingEditId?.let { id ->
        ManualCourseNav.pendingEditId = null
        ManualCourseStore.all().firstOrNull { it.id == id }
    }
}
var editing by remember { mutableStateOf<ManualCourse?>(initialEditing) }
```

`animateFloatAsState` 首次组合不发动画（初值直接等于 target），所以 `progress` 一上来就是 1 —— 只有外层滑入这一层，弹窗一关编辑页就已经就位。返回键也由 `ManualCourseNav.onBack` 注册给 Fragment 的 `OnBackPressedCallback`，先关编辑页、再关添加页、最后才回课表。

真机确认：点「编辑」→ 标题「编辑课程」、名字/星期/节次/周次全部带入；按返回 → 回到「添加课程」页而不是课表。

### 57.3 时间滚轮的数字切换过渡动画（`ManualCourseScreen#WheelColumn`）

旧实现按索引硬切样式：

```kotlin
fontWeight = if (i == center) FontWeight.Bold else FontWeight.Normal   // 16sp ↔ 34sp
```

滚动时数字是「跳」着变大小的。改成按**这一项中心离视口中心多远**算连续权重，字号 / 字重 / 颜色全部插值：

```kotlin
val frac = if (info == null) {
    if (i == centerIdx) 1f else 0f
} else {
    val vpCenter = (vp.viewportStartOffset + vp.viewportEndOffset) / 2f
    val itemCenter = info.offset + info.size / 2f
    (1f - kotlin.math.abs(itemCenter - vpCenter) / itemPx).coerceIn(0f, 1f)
}
val k = frac * frac * (3f - 2f * frac)   // smoothstep：边缘更软、中间更实
fontSize = (16f + 18f * k).sp
fontWeight = if (k > 0.5f) FontWeight.Bold else FontWeight.Normal
color = lerp(scheme.onSurfaceVariantSummary, scheme.onSurface, k)
```

用 smoothstep 而不是线性：线性插值下「贴近边缘但还没出屏」的数字仍是大字号，看起来发糊；smoothstep 在两端变化慢、中段快，滚动过程中始终只有一个视觉焦点。`layoutInfo` 拿不到的项（刚进入/退出组合）退回索引判定。

真机实测同一屏内中心项 bounds 高 116px、边缘项 54px（≈2.1 倍），证明插值是连续的而不是两档跳变。

### 57.4 开始/结束时间与右上角不同步（两个独立 bug）

**bug 一：`LaunchedEffect` 的 key 漏了回调，事件回调停在首次组合。**

```kotlin
// 错的
LaunchedEffect(state, count, step) {
    snapshotFlow { state.firstVisibleItemIndex }
        .collect { onChanged(it) }        // ← 这个 lambda 捕获的 minute 永远是初始值
}
```

协程里的 `onChanged` 是**首次组合时**那个闭包，它捕获的 `minute` 不会更新。滚分钟列时 `onChange((minute / 60) * 60 + m)` 用的是旧的小时：23:55 往上滚一格，小时已经变 00，分钟却仍按 `23 * 60` 拼出 `00:55` —— 凌晨交界处最刺眼，正是用户报的现象。

修法：

```kotlin
val latestOnChanged by rememberUpdatedState(onChanged)
LaunchedEffect(state, count, step) {
    snapshotFlow { centerHeld.value }.collect { latestOnChanged(...) }
}
```

**bug 二：`rememberLazyListState(initialFirstVisibleItemIndex = …)` 只在首次组合生效。**

外部把值改掉（开始时间越过结束时间后结束时间被自动推后 45 分钟；或从「已添加的课程」点进编辑页换了另一门课）时，滚轮停在旧位置 —— 于是「右上角写着 23:59、滚轮还停在 8:45」。

修法：加一个只在「滚轮实际值 ≠ 外部值」时才滚的同步 effect，用户正在拖动时不抢：

```kotlin
LaunchedEffect(safeInitial, count, step) {
    if (state.isScrollInProgress) return@LaunchedEffect
    val curMod = (((state.firstVisibleItemIndex - base) % count) + count) % count
    if (curMod != safeInitial) state.animateScrollToItem(base + safeInitial)
}
```

真机验证：`08:00 - 08:45` 滑小时一格 → `09:00 - 09:45`（结束时间被自动推后且**结束滚轮跟着滚到 09:45**）；再滑分钟一格 → `09:05 - 09:45`（小时保持 09，不再是 `08:05`）。

### 57.5 自定义时间超出作息表时课表自适应（`ScheduleFragment#renderGrid`）

`yOfMinute()` 早就把越界分钟钳到 `minuteAnchors` 两端，所以块的**位置**从来没出过网格；真正卡住的是行高下界：

```kotlin
// 错的
val rowH = (usable / rowCount).coerceIn(dp(32), (dp(160) * textScale).toInt())
```

32dp 的下界意味着「可用高度 ÷ 行数」一旦小于 32dp 就撑不回去 —— 出现 12 节课、或加了早晚自定义时间的课之后，网格必然超出可视区，只能用滚动条。

改成 `coerceIn(dp(12), …)` 放开下界，让行数多时行高继续往小缩，整张表始终压进一屏。

真机确认：1–10 节全部在一屏内、无滚动条；添加一门时间在 05:05–09:45 的手动课后也能正常渲染（起始钳到第 1 节顶、结束钳到第 2 节底），不溢出边界。

### 57.6 本轮事故与恢复（务必记住这个坑）

`[IO.File]::WriteAllText($path, $null)` **会先把文件截断成 0 字节，再抛 `ArgumentNullException`** —— 异常被 PowerShell 的非终止错误机制吞掉，调用方以为只是「写入失败」，实际三个源文件已经被清空。

起因：脚本里定义了 `function R($p) { ... }` 和 `function W($p,$t) { ... }` 想省字，而 **`R` 是 PowerShell 的 `Invoke-History` 内置别名**，函数定义被别名覆盖，`$t = R $p` 实际执行的是 `Invoke-History`（失败返回 `$null`），接着 `W $p $null` 就把文件截断了。

规则：

- **绝不使用 `R` / `W` / `T` / `?` / `%` / `H` 这类单字母函数名或变量名**，它们大概率撞 PowerShell 别名。用 `Edit-Text` / `Read-Source` 之类可读名字。
- 写文件前必须 `if ($null -eq $text) { throw }`。
- 恢复来源只有 `push\repo`（当时落后到第 52 轮），因此第 53/54/55 轮在 `ManualCourseScreen.kt` 里的改动全部重做了一遍。教训：**每轮结束把 `src` 同步进 `push\repo`（或至少打一份 zip），别攒着**。
## 56. 第五十六轮（2026-10-31）：私信语音闪退加固 / 课表字号随分辨率自适应 / 刷新后日期跳周

用户三条（原话）：①「私信中语音功能有问题，长按松手后自动闪退」；
②「课表中文字要根据屏幕分辨率自动变大或变小，不应该固定在一个大小」；
③「课表刷新有问题，参考视频中的问题」（附 6.39 秒录屏，1840×2944 真机，第 14→15→16 周）。

### 56.1 语音闪退：整条链路跑在手势协程里，一处抛异常就把进程打死

「按下录音 → 松手发送」全部写在 `detectTapGestures(onPress = { ... })` 这个 **suspend lambda**
里。`MediaRecorder.start()` / `stop()` / 融云 `sendMediaMessage()` 三段都可能抛
`RuntimeException`（录音过短、麦克风被占、上手实例没释放、SDK 读不到本地文件），
只要有一处漏出，异常就顺着 Compose 的协程冒到主线程 → 直接闪退。

加固（`im/ImScreens.kt`、`im/ImRepository.kt`、`im/ImMedia.kt`）：

1. `onPress` 内三段分别 `runCatching`，失败只 Toast 提示 + `Log.e("ImVoice", ...)`；
   `recording = false` 放进 `finally`，手势被取消也不会留在「录音中」。
2. `VoiceRecorder.start()` 改成先 `reset()`/`release()` 旧实例再建新的；
   启动失败时在 `onFailure` 里把半成品 `r` 一起 `reset/release` 并删掉空文件
   （以前失败后 `r` 泄漏且占着麦克风，下一次点「按住说话」必然再失败）。
3. `VoiceRecorder.stop()` 的 `r.stop()` 失败会打日志，便于真机定位。
4. `ImRepository.sendRich()` 里融云的 `sendMediaMessage` / `sendLocationMessage`
   包进 `runCatching`，同步抛异常时把那条消息标红并写 `error`，不再闪退。

**本轮未验证**：模拟器无麦克风，`MediaRecorder.start()` 必然失败，走不到 `stop()` 之后，
无法复现真机的「松手即崩」。以上为防御式加固，真机若仍闪退，
请抓 `adb logcat -s ImVoice ImSend` 的日志（已埋点），能直接指到是哪一段抛的。

### 56.2 课表字号：从「三档死值」改成按格子像素尺寸连续换算

以前课程名只有 `colW < 30dp → 7.5sp / < 40dp 或 rowH < 40dp → 8.5sp / 否则 9.5sp`
三档，格子再宽再高也停在 9.5sp —— 高分屏（1840×2944 一类）上课名小到几乎看不清，
这就是用户说的「固定在一个大小」。

`ScheduleFragment.renderGrid()`：

- **课程名基准档**：`minOf(可用宽/4.2, 可用高/3.2).coerceIn(7.5sp, 19sp)`，
  宽按「一行排 4.2 个字」、高按「块内塞 3.2 行」估算，随格子实际像素连续变化。
- **逐课收敛**：在 `render()` 里按当前课名的实测字宽（`textPx`）从基准档往下试
  （每步 0.5sp，最低 7sp），短名吃满基准档、长名自动降，不会挤成竖排。
  房间号始终比课名小 1sp（最低 6sp）。
- **节次列候选档**：`13/12/11` → `17/16/15/14/13/12/11`；时间列 `8/7.5/7` → `11/10.5/10/9/8/7.5/7`。
  取档逻辑本来就是「从大到小试、第一个放得下就用」，所以行高大的屏自动吃大字号，
  矮屏仍一路降到 11sp / 7sp —— **窄屏行为完全不变**。
- **表头**：星期 `13sp` → `colW/2.6` 夹在 `11~17sp`，日期 = 星期 × 0.7（最低 8sp）。

### 56.3 刷新后日期跳周：日期表头不能用「现算的当前周一」推

`renderGrid()` / `renderWeekView()` 原本每帧现算：

```kotlin
val monday = LocalDate.now().with(DayOfWeek.MONDAY)
val weekMonday = monday.plusWeeks((currentWeek - actualCurrentWeek).toLong())
```

而 `actualCurrentWeek` 会在下拉刷新时被服务器返回的「本学期第几周」改写（`applyWeekInfo`）。
于是**刷新一下、`currentWeek` 没变，日期表头却整体跳了一周** ——
录屏里「第 14 周」下面挂着第 15 周的 `12/7`、课程块跟着日期重排，就是这条。

修法：新增 `private var termMonday: LocalDate?`，**只在第一次**拿到 `weekInfo` 时
按当时的本周一往回推 `(当前周 - 1)` 周并固化；之后一律
`termMonday + (周次 - 1)`。与刷新时机、跨天、跨周都无关。
未拿到 `weekInfo` 时回退旧算法，行为不变。

（录屏中切周动画的中间帧里「表头出现周二…周一」是**位图跟手滑动的正常表现**，
不是 bug：旧内容整张位图在滑出、新内容在下层，两者交界处看起来像表头错序。）

---

## 54. 第五十四轮（2026-10-31）：编辑页返回动画对齐 / 时间滚轮 5 分钟格 + 无限循环 + 选中强调

用户三条（原话）：①「从编辑课程页面返回时，过渡动画与应用内的不统一」；
②「上课时间的范围改为五分钟一格，5 10 15 20 以此类推，滚动方式改为无限滚动，23 点与 0 点衔接，55 与 00 衔接」；
③「被选中的上课时间不够突出，需要将字号调大，字体加粗，上下的待选数字改为一个，而不是两个」。

### 54.1 返回动画不统一的真因：子页当帧就被摘掉了

二级页的渲染条件原来是 `val target = editing; if (target != null)`。
`editing = null` 发生在**按下返回的那一帧**，于是子页立刻离开组合，只剩
「一级页独自滑回原位 + 遮罩独自淡出」这**一半**动作 —— 另一半「子页整体滑出右侧」根本不存在。

而全 App 的二级页走 `MiuiNavStack`：返回时 `leaving = shown`，正在退出的那页**保活到动画结束**
（`childFraction = p`，0→1 滑出），两半永远成对。同一个动作两种观感，用户就看出来了。

修法：把「编辑的是哪一门课」和「这一层还在不在」拆成两个状态。

```kotlin
var shownEdit by remember { mutableStateOf<ManualCourse?>(null) }
LaunchedEffect(editing) { if (editing != null) shownEdit = editing }
val editSettled = progress == 0f
LaunchedEffect(editSettled) { if (editSettled) shownEdit = null }
// 渲染条件改成 shownEdit
val target = shownEdit
```

几何 / 时长 / 曲线本来就取自 `NavMotion`（`DURATION_MS = 300`、`FastOutSlowInEasing`、
`PARENT_TRAVEL = 0.24f`、`PARENT_DIM = 0.18f`），与 `MiuiNavStack` 同源，补上「保活」这一条即成同一个动画。

### 54.2 时间滚轮：5 分钟一格 + 无限循环（第 2 条）

`WheelColumn` 重写，签名加了 `step`：

```kotlin
private fun WheelColumn(count: Int, initial: Int, modifier: Modifier = Modifier,
                        step: Int = 1, onChanged: (Int) -> Unit)
```

- **5 分钟一格**：分钟列 `count = 12`、`step = 5` → 只出 `00/05/10/…/55`；小时列 `count = 24`、`step = 1`；
- **无限滚动**：把 `count` 项复制 `LOOP = 1000` 份拼成超长列表（总项数 `count * 1000`），
  初始位置钉在正中那一圈（`base = count * 500`）。于是往任一方向都还有近万项可滚，
  **23 之后接着是 00、55 之后接着是 00**，再也看不到边界；
- 显示值 = `(i % count) * step`；回调值 = `(((首项 - base) % count) + count) % count * step`，
  跨圈自动接上，不用自己接管 touch 与惯性（仍用 `rememberSnapFlingBehavior` 吸附）。

### 54.3 选中项更突出、只露三行（第 3 条）

- 可见行数 `itemH * 5` → `itemH * 3`，`contentPadding` 上下各一行 → **只露「上一行 / 选中 / 下一行」**；
- 行高 `36.dp` → `44.dp`（大字号需要空间）；
- 选中行 `MiuixTheme.textStyles.title4 + FontWeight.Bold`（原来统一 `body2 + Medium`），
  未选中行 `body2 + Normal` + `onSurfaceVariantSummary` 弱化。
  实测 bounds 从 44×48 变成 60×62，视觉上一眼能认出选中的是哪个数。

### 54.4 本轮验证（模拟器 emulator-5554，1220×2656，第 9 周，深色）

| 项 | 结果 |
| --- | --- |
| `assembleRelease` | ✅ `BUILD SUCCESSFUL in 1m 59s` |
| APK 放根目录 | ✅ `lanhang-miuix-release.apk` 8,622,412 字节 |
| ③ 时间滚轮 5 分钟格 | ✅ dump：分钟列 `55 / 00 / 05`、`40 / 45 / 50` |
| ③ 无限衔接 | ✅ 截图：分钟 `00` **上方是 55**、下方是 05（23↔0 同一份代码） |
| ③ 选中突出 | ✅ 选中 `08`/`00` 明显更大更粗；上下各**只露一行** |
| ③ 页面内调整 | ✅ 「上课时间 08:00-08:45 ⌃」行下方原地长出「开始时间 / 结束时间」两组滚轮，**无遮罩、无弹窗** |
| ① 返回动画 | ✅ 按返回后立刻抓帧（`input keyevent 4; screencap` 同一条 shell）：子页仍在屏上（标题仍是「编辑课程」、左上角 `‹`），1 秒后回到「添加课程」页 —— 说明保活生效；逐帧手感仍需录屏确认 |
| ② 周次全不选（第 53 轮遗留） | ✅ 截图：1~20 全灰、全部/单周/双周三档都不高亮 |
| ④ 返回层级（第 53 轮遗留） | ✅ 点已保存课程进编辑页 → 系统返回 → 回到「添加课程」页（不是课表） |
| ⑤ 切 Tab 课表拉伸（第 53 轮遗留） | ✅ 课表 → 我的 → 成绩 → 返回 → 课表：第 9 周 1~10 节完整显示到底，无裁切无拉伸 |
| 测试数据清理 | ✅ `ZZtest` 已删，周一第 1 节恢复「高等数学B1」 |

本轮未 push（用户未要求），版本号仍是 72 / 1.1.20。

### 54.5 本轮踩坑

- **`am start -n` 在重装后可能报 `Permission Denial: … not exported`**（新实例上 Activity 的
  exported 标志未按预期解析）。改用 `adb shell monkey -p com.lanhang.miuix -c android.intent.category.LAUNCHER 1`
  一次成功。
- **模拟器冷启动 `-no-snapshot-load` 会起不来**：上一轮等了 60s + 120s 都没注册设备，误判成「模拟器坏了」；
  这一轮去掉该参数（吃快照）**20 秒就就绪**。以后直接 `Start-Process emulator.exe -ArgumentList "-avd","Medium_Phone_API_37.0"`，
  并在循环里每 5 秒轮询 `adb devices`，最多 120 秒。
- **抓转场中间帧的办法**：`adb shell "input keyevent 4; screencap -p /sdcard/r1.png"` ——
  两条命令在**同一个 adb shell 会话**里连跑，间隔只有几十毫秒，才能抢到 300ms 动画内的那一帧。
  分成两条 `adb shell` 会各多一次往返，抓到的永远是动画结束后的画面。
- **截图一定要落在纯 ASCII 路径再交给 `read_image`**：中文路径下 `read_image` 会报 not found，
  先 `adb pull` 到 `%TEMP%` 再读。

---
### 54.6 后续修正：高亮跑到边行 / 字号再放大（同一批需求的第二次反馈）

用户：「第一，时间页面向上或向下滑时，重点标号会到最上面或最下面的数字。第二，重点不够突出，可以再调大字号」。

**高亮为什么会跳**：上一版用 `i == state.firstVisibleItemIndex` 判高亮。
`firstVisibleItemIndex` 是「**第一个可见项**」——手指一拖、内容上移超过一整行，它就跳到下一项；
而视觉上「中间那一行」是按**滚动偏移量**精确落在视口中心的那一项。两者不同步，
于是拖动时高亮会突然窜到最上面那行，快速滑动时甚至整个跑出可视区。

正确算法（上下各留一行 padding 时）：

```kotlin
val itemPx = with(LocalDensity.current) { itemH.toPx() }
val centerHeld = remember(itemPx) {
    derivedStateOf {
        state.firstVisibleItemIndex +
            ((state.firstVisibleItemScrollOffset + itemPx / 2f) / itemPx).toInt()
    }
}
```

推导：静止吸附时「中间行 = 首项」；内容向上移动多少行（`off / itemH`），中心项就往后挪几个。
用 `derivedStateOf` 包住，只有「高亮真要换行」的那一帧才触发重组，滚动过程本身不重画全部可见项。
`onChanged` 也改成订阅 `centerHeld`，保证「写回的值」与「高亮看到的数」永远是同一个。

**字号**：`itemH` `44.dp → 46.dp`；选中行用
`MiuixTheme.textStyles.body2.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold)`，
未选中 `16.sp / Normal / onSurfaceVariantSummary`。
（用 `TextStyle.copy` 而不是直接给 `fontSize` 参数，避开 Miuix `Text` 参数表的差异；`sp` / `LocalDensity` / `derivedStateOf` 三个 import 要一起补。）

实测 dump：未选中数字 bounds `52×54`，选中 `114×116` —— 相对上一版的 `44×48 / 60×62` 一眼就能看出选中项。

**验证**（慢速拖动小时列，同一条 shell 里 `input swipe … & sleep 0.45; screencap` 抓拖动中途帧）：

| 帧 | 小时列 | 分钟列 | 顶栏 |
| --- | --- | --- | --- |
| m1（拖动 32%） | 08 / **09** / 10 | 55 / **00** / 05 | 09:00 - 09:45 |
| m2（再推进一行） | 09 / **10** / 11 | 55 / **00** / 05 | 10:00 - 10:45 |

两帧的高亮**都稳在中间行**，没有再窜到首行/末行；顶栏时间与滚轮一致。
## 53. 第五十三轮（2026-10-31）：周次默认全不选 / 时间改页面内滚轮 / 返回键层级 / 切 Tab 课表拉伸

用户五条（原话）：①「每次更新都要编译出 release 版放到你的工作文件夹的根目录」；
②「打开添加课程时，上课周次不应为全选，应为全不选」；
③「上课节次与自定义时间应用同一套交互逻辑，上课节次在页面内调整，自定义时间也应在页面内调整，而不应该再弹出对话框」；
④「编辑课程应视为添加课程的二级页面，返回时不应回到课表主页而应回到添加课程页」；
⑤「从课表页点进我的页，再随便点进一个二级页面退出后再进入课表页，课表会被异常拉伸到屏幕下侧，无法显示完全」。

### 53.1 周次默认全不选（第 2 条）

改两行：`weeks` 初值 `(initial?.weeks ?: emptyList()).toSet()`（以前 `(1..MAX_WEEK).toList()`），
`weekMode` 初值 `initial == null -> ""`（以前 `"all"`）。
空集合按「每周都上」保存（`ManualCourse.inWeek` 的既有约定），所以「全不选」是合法默认态。

### 53.2 自定义时间改成页面内滚轮（第 3 条）

原来的 `TimeSheetDialog`（全屏 35% 遮罩 + 底部 20dp 圆角面板 + 取消/确定 + 四列滚轮）**整段删除**，
只留滚轮本体，改成 `TimeWheelRow(minute, onChange)`：一行 `时 : 分`，
与节次形态的「起始节 / 结束节」两行**同构**。

| | 节次形态 | 时间形态 |
| --- | --- | --- |
| 行文案 | 上课节次 / 第 1 - 2 节 | 上课时间 / 08:00 - 08:45 |
| 点击 | `periodOpen = !periodOpen` | `timeOpen = !timeOpen` |
| 展开内容 | 起始节 + 结束节 两个 `PeriodGrid` | 开始时间 + 结束时间 两个 `TimeWheelRow` |
| 箭头 | `⌃` / `›` | `⌃` / `›` |

展开区仍是 `AnimatedVisibility`，长在「上课节次 / 上课时间」那张卡片内部 —— 不再有遮罩、不再开窗。
滚轮改动即时写回 `startMinute / endMinute`，并保证「结束晚于开始至少 45 分钟」（避免零长度课程）。

### 53.3 返回键的层级（第 4 条）

覆盖层是 `addView` 到 Fragment 根布局上的普通 View，返回键由 `manualCourseBack`
（`OnBackPressedCallback`）接管；但它看不到 Compose 内部「是否在编辑页」。

新增 `object ManualCourseNav { var onBack: (() -> Boolean)? = null }`（在 ManualCourseScreen.kt）：

- Compose 侧 `DisposableEffect(editing != null)` 注册「若在二级页 → 关掉它并返回 true」；
- Fragment 侧 `handleOnBackPressed()` 第一句 `if (ManualCourseNav.onBack?.invoke() == true) return`，
  然后才 `hideManualCourseEditor()`。

于是返回键顺序变成：编辑页 → 添加页 → 课表，与视觉层级一致。

### 53.4 切 Tab 回来课表被拉长（第 5 条）

真因：二级页打开期间底栏隐藏，`fragmentContainer` 从 2046 高变成 2305，课表按那一刻的高度算 `rowH`；
底栏恢复后容器变矮，但 `onTabSelected()` 只在 `contentFilled() == 0` 时才 `renderAll()`，
于是一整张网格比可用空间高、底部被裁 —— 用户看到的就是「拉伸到屏幕下侧、显示不全」。

修法：新增 `lastLayoutHeight` 字段，`onTabSelected()` 里 `root.post { }` 比较当前高度，
不一致就 `renderGrid()` 重算一次再 `ensureContentNotEmpty()`。

### 53.5 发版产物落到项目根目录（第 1 条）

`assembleRelease` 之后把 `app/build/outputs/apk/release/app-release.apk` 复制成
`<项目根>\lanhang-miuix-release.apk`。以后每轮都这么做。

### 53.6 本轮验证状态（诚实记账）

| 项 | 结果 |
| --- | --- |
| `assembleRelease` | ✅ `BUILD SUCCESSFUL in 1m 41s` |
| APK 放根目录 | ✅ `lanhang-miuix-release.apk` 8,622,412 字节 |
| ② 周次全不选 / ③ 时间内联 / ④ 返回层级 / ⑤ 课表拉伸 | ⚠️ **未设备验证**：本轮开工时模拟器随隔夜关闭，`adb devices` 为空；启动 `Medium_Phone_API_37.0` 后等 60s + 120s 仍未注册（启动失败），按「单步失败两次就停手」的纪律收手。四项改动均为编译级把握 |

本轮未 push（用户未要求）。

### 53.7 本轮踩坑（重要）

- **PowerShell 函数里 `Write-Output` 与 `return` 混用会污染返回值**：补丁脚本里写成
  `function Rep($t,$o,$n){ Write-Output "OK"; return $t.Replace($o,$n) }`，
  外层 `$t = Rep ...` 拿到的是**数组**（`["OK",...,"<全文>"]`），再
  `[IO.File]::WriteAllText($p,$t)` 会把数组按空格拼成字符串写盘 —— 文件开头多出
  `OK R5 OK R4 OK R3 OK R2 OK R1 `，差点毁掉整个 964 行文件（靠 `IndexOf("package ...")` 截断救回）。
  **教训：批处理脚本里日志与返回值必须分开收集（`$log +=` + 单独变量）。**
- **`read`/`edit`/`grep` 对含中文的工作区路径时好时坏**：同一路径前一次成功、后一次 `not found`。
  本轮所有源码改动改用 `pwsh` + `[IO.File]::ReadAllText / WriteAllText`（UTF-8 无 BOM）完成，
  已验证中文读写不乱码。**下次批量改源码优先走这条路，别在工具上反复重试。**

---
## 52. 第五十二轮（2026-10-30）：叠加块翻转几何 / 胶囊选择器 / 编辑二级页 / 节次时间互斥 / 切换视图下沉

用户五条（原话）：①「课程的叠加设置有问题，比如周四时有一节 1-3 节的课程，我又添加了一节 1-2 节的叠加课程，
这时这个叠加课程翻转时依旧会显示在 1-3 节的范围」；②「自定义时间、全部、单周、双周的选择器参考这个设计」（附参考图：
标题在左、右侧一行浅灰药丸、深灰字、无描边）；③「将编辑课程设为添加课程的二级菜单，过渡动画照样设计」；
④「上课节次与自定义时间只能存在一个，如果打开了自定义时间，则将上课节次替换为上课时间设置，并作过渡动画，
按照课上时间与课表时间的比例设置课程在课表中所占大小」；⑤「点击课表切换按钮时，课表会异常向下动一下再恢复」。

### 52.1 叠加块翻转：几何必须跟着「当前那门课」走

`renderGrid()` 里一个叠加组只算一次几何（`group.minOf { periodStart }` / `maxOf { periodEnd }` 的**整组并集**），
`render(index)` 只换文字与底色。于是 1-3 节 + 1-2 节叠在一起时，翻到 1-2 节那门课，块仍占满 3 节。

改法：**几何变成 render 的一部分**。

- 新增 `fun blockRect(ci: CourseItem): Pair<Int, Int>`（顶边 + 高度）；
- `lp`（`FrameLayout.LayoutParams`）从「render 之后」提到「render 之前」声明
  （Kotlin 局部函数不能引用其后声明的局部 `val`）；
- `render(index)` 末尾按 `blockRect(ci)` 重设 `lp.topMargin` / `lp.height` 并 `block.layoutParams = lp`；
- 首次渲染仍按 `group[0]` 预摆一次，`blockArea.addView(block, lp)` 用同一个对象。

`blockRect` 两条分支：`ci.byClock`（自定义时间）走分钟插值，否则走 `rowIndexOf(periodStart..periodEnd)`。

### 52.2 自定义时间：按「课上时长 / 课表时长」占位

`CourseItem` 新增 `startMinute` / `endMinute`（默认 `-1`）+ `val byClock`，
`ManualCourse.toCourseItem()` 在 `hasCustomTime` 时把分钟带下去。

`renderGrid()` 里用可见节次当锚点建一张单调递增的「分钟 → 像素」表：

```kotlin
minuteAnchors += PeriodClock.startMinute(p) to yOfRow(i)
minuteAnchors += PeriodClock.endMinute(p)   to (yOfRow(i) + rowH)
fun yOfMinute(m: Int): Int   // 分段线性插值，出界夹到两端
```

于是 08:20-09:10 这种课自然落在两节之间，块高等于真实时长占比（休息带也被算进去），
不需要再换算成「第几节」。

### 52.3 节次 / 上课时间互斥（用户第 4 条）

原来是「节次网格照常显示，时间只是补充说明」（`if (periodOpen || customTime)` 展开网格）—— 语义错了。
现在这一行有两种形态，`AnimatedContent(targetState = customTime)` 切换：

| 形态 | 标题 | 右侧值 | 点击 | 展开内容 |
| --- | --- | --- | --- | --- |
| 节次 | 上课节次 | 第 1 - 2 节 ｜ ⌃ | 展开 / 收起 | 起始节 + 结束节 两组 1~12 胶囊 |
| 时间 | 上课时间 | 08:00 - 08:45 ｜ › | 弹 `TimeSheetDialog` | 无（`AnimatedVisibility(!customTime && periodOpen)`） |

外层套 `Modifier.animateContentSize()`，行切换走 `fadeIn + slideInVertically{it/4}` / `fadeOut`，
时长取 `NavMotion.DURATION_MS` 的一半（150ms），保证「换形态」比「进出页面」快半拍。

### 52.4 编辑课程 = 添加课程的二级页（用户第 3 条）

以前点「已添加的课程」里的一行，是**就地换标题**（同一个表单把「添加课程」改成「编辑课程」），看不出层级。
现在 `ManualCoursePage` 里叠两层，参数全取 `NavMotion`（全 App 唯一参数表）：

```kotlin
val progress by animateFloatAsState(if (editing != null) 1f else 0f,
    tween(NavMotion.DURATION_MS, easing = NavMotion.Easing))
Box { 一级页.graphicsLayer { translationX = -PARENT_TRAVEL * progress * size.width } }
if (progress > 0f) Box.fillMaxSize().background(Color.Black.copy(alpha = PARENT_DIM * progress))
if (target != null) Box.graphicsLayer { translationX = (1f - progress) * size.width } { 编辑页 }
```

配套改动：

- `ManualCourseEditor` 新增 `showSavedList: Boolean = true`，编辑页传 `false`（二级页不该再列一遍「已添加的课程」）；
- 顶栏左键按语义换图标：一级页 `✕`（关掉整页）、二级页 `‹`（返回上一页）；
- 新增 `editKey`：换一门课进编辑页时 `key(editKey)` 强制重建，否则 `remember` 会把上一门课的值留住。

### 52.5 胶囊选择器照参考重做（用户第 2 条）

参考图是「标题在左、右侧一行等宽药丸、浅灰实心底、深灰字、无描边」。原来的 `Chip` 是 10dp 圆角，
看着像标签而不是药丸。改两处：`RoundedCornerShape(10.dp)` → `RoundedCornerShape(50)`；
「全部 / 单周 / 双周」三个加 `Modifier.weight(1f)` 均分标题右侧剩余宽度。
星期、节次、周次网格共用同一个 `Chip`，一并变成药丸。

### 52.6 切换视图「向下动一下」（用户第 5 条）

`slideSwap()` 只写 `translationX`，理论上不该有竖向位移。真因是**切换瞬间两层内容高度不同**，
外面的 `SwipeRefreshLayout` 跟着重新测量并把「下拉刷新」的位移抖了一帧；自绘 View 不吃 Compose 的布局缓存，
看起来就是整块内容下沉一下再弹回。

修法（`switchView` 内）：

1. 切换窗口内 `swipeRefresh.isEnabled = false`，`MainActivity.TAB_SWAP_MS + 60ms` 后恢复；
2. 两个滚动容器的 `overScrollMode` 钉成 `OVER_SCROLL_NEVER`；
3. 切换与收尾各把 `contentGrid / contentWeek.translationY` 显式归零。

### 52.7 验证（模拟器 emulator-5554，1220×2656，第 9 周，深色）

| 项 | 结果 |
| --- | --- |
| 胶囊选择器 | ✅ 截图：三档与星期 / 节次 / 周次全部变成等宽药丸，未选中浅灰实心、选中主色 + 白字 |
| 节次 ↔ 上课时间互斥 | ✅ 打开「自定义时间」后该行变为「上课时间 08:00 - 08:45 ›」且节次网格收起；关掉后恢复「上课节次 第 1 节 ⌃」 |
| 编辑二级页 | ✅ 点「已添加的课程」里的 TTest → 标题「编辑课程」、左上角变 `‹`、名称回填、**不再显示已添加列表** |
| 保存 / 删除 | ✅ 两门测试课都能存进列表（「2 门」）、点「删除」逐条移除，删空后卡片整体消失 |
| 课表恢复 | ✅ 清空测试课后周一红点、周四黄点都消失，课表与改动前一致 |
| 叠加块翻转高度 | ⚠️ **未验证**：`adb shell "input tap x y; input tap x y"` 的两次间隔仍 >300ms，被 `GestureDetector` 判成两次单击（弹出课程详情）。§49.5 的老问题，需真机手指双击确认 |
| 切换视图不抖 | ⚠️ **未验证**：需要录屏逐帧比对「点按钮瞬间」的内容位移，本轮时间不够 |

本轮未发版（用户未要求 push），`versionCode` 仍是 72 / 1.1.20。

### 52.8 本轮踩坑

- **`input tap` 点到「标签」而不是「输入框」**：`FormRow` 的输入区在右侧，点 `(210,577)` 命中的是左侧标签空白，
  `input text` 完全没进去（`必填` 占位一直在）。改成点 `(900,577)` 后一次成功，
  `dumpsys input_method` 里也能看到 `mInputShown=true`。**验证输入类交互，先确认 `mInputShown` 再输入**。
- **`input text` 之后不要按 `keyevent 4` 收键盘**：添加页挂了 `OnBackPressedCallback`，第一次返回会被它吃掉，
  整个覆盖层直接关掉（本轮因此白丢一次「已保存的课程」现场）。要收键盘就点页面空白处。
- **点空白处可能点中别的可点行**：点 `(620,1420)` 想收键盘，结果命中「上课节次」行把网格展开，
  后面所有坐标整体下移 600+ px。**收键盘用卡片内的死区，或者干脆先做输入再做点击类步骤**。
- **截图目测列位置会偏一整列**：据 `s6.png` 目测「周四列中心 x=645」，实际 dump 里周四列是 `[701,901]`，
  645 落在**周三**，双击打到了别的课。**一律用 dump 的 bounds 算列中心**（本文件 §49.6 已记过同款坑）。

---

## 51. 第五十一轮（2026-10-30）：后端与接口代码闭源搬进 aar（1.1.20 / versionCode 72）

用户原话：「进行问题 4 的处理，push 并触发 release」——问题 4 即第 48 轮列的第 ④ 项
「把后端与接口代码全部闭源搬进 aar」。本轮把**主 API 协议层整体搬出 app 源码树**。

### 51.1 方案：独立 `:engine` Android Library + 预编译 aar

```
engine/                                   ← 只在本地，.gitignore 已排除
  build.gradle.kts                        namespace = "com.lanhang.engine"
  consumer-rules.pro                      Gson 反射模型的 keep 规则
  src/main/AndroidManifest.xml            空 manifest（纯类库）
  src/main/res/values/strings.xml         协议层自己要抛的 10 条错误文案
  src/main/java/com/lanhang/miuix/{net,core,session,moments,schedule}/…
app/libs/engine.aar                       ← 提交进仓库的二进制（169 KB）
```

**关键设计（省掉了 24 个文件的 import 改动）**：Gradle 的 `namespace` 只决定 R / BuildConfig
的包名，**与 Kotlin 源码的 `package` 完全独立**。所以 engine 里 `namespace = "com.lanhang.engine"`，
而源文件仍声明 `package com.lanhang.miuix.net`（原样不动）→ app 侧所有
`import com.lanhang.miuix.net.ApiClient` 一个字都不用改。engine 内部三处
`import com.lanhang.miuix.R` 改成 `import com.lanhang.engine.R`，用自己的 R。

搬走的 15 个文件（app 侧全部删除，否则重复类冲突）：

| 目录 | 文件 | 说明 |
| --- | --- | --- |
| `net/` 整个目录 | `ApiClient`（域名 `nginx-1.app.nchu.edu.cn` + `/api` 前缀）、`RceApi`、`MiniappApi`、`MomentsApi`、`ContactsRepository`、`Security`（RSA 公钥）、`Models`、`WorkModels`、`ContactsModels` | 接口清单的唯一持有者 |
| `core/` 整个目录 | `ApiException`、`TextRef`、`Errors` | 协议层错误载体 |
| `session/Session.kt` | 登录态 / token / 各服务地址 | |
| `moments/MomentModels.kt` | 工作圈协议模型（`MomentFeed` 等 8 个类） | |
| `schedule/AppContext.kt` | **本轮新抽出来的**（原来定义在 `ScheduleRepository.kt` 第 139 行） | 协议层要 Context，但不能反向依赖业务源码 |

`app/build.gradle.kts` 依赖改成：

```kotlin
implementation(files("libs/engine.aar"))   // 本地 aar 不传递依赖，所以 okhttp/gson/coroutines 仍要 app 自带
```

根 `build.gradle.kts` 补 `id("com.android.library") version "8.13.2" apply false`。

### 51.2 闭源落点：源码不入库，条件 include

`settings.gradle.kts` 改成**条件 include**：

```kotlin
if (file("engine").exists()) { include(":engine") }
```

`.gitignore` 加 `engine/`。于是：本地有源码（可维护、可重编），公开仓库里只有
`app/libs/engine.aar`（app 源码树里看不到任何接口面），CI 上目录不存在就跳过该模块、
直接用已提交的 aar —— 两边都能构建。

**重建 aar**（每次改协议层）：

```
gradle :engine:assembleRelease        # 产物 engine/build/outputs/aar/engine-release.aar
copy engine\build\outputs\aar\engine-release.aar app\libs\engine.aar
```

若哪天想让源码也公开：删掉 `.gitignore` 里那行 `engine/` 即可（条件 include 会自动接上）。

### 51.3 踩坑：跨模块的 `val` 属性不能 smart cast

构建 app 时报两处（`im/ImScreens.kt:959 / 975`）：

```
e: Argument type mismatch: actual type is 'String?', but 'String' was expected.
```

根因不是搬迁本身，而是**类型换了模块**：

```kotlin
val briefName = brief?.name
val name = when {
    brief?.name?.isNotBlank() == true -> brief.name   // ← 同模块时 smart cast 成 String
```
`StaffBrief` 从「同模块的 app 源码」变成「aar 里的外部模块」后，Kotlin **不再对跨模块的
`val` 属性做 smart cast**（外部模块的属性视为可能被重写/有 getter 副作用），于是
`brief.name` 退回 `String?`，`when` 的整体类型变成 `String?`，下游
`LanhangAvatar(name=String)` / `Text(text=String)` 一起报错。

修法：把可能为空的属性先落到**局部变量**，smart cast 就回到局部作用域里：

```kotlin
val briefName = brief?.name
val name = when {
    briefName != null && briefName.isNotBlank() -> briefName
    isGroup -> …
    peerName.isNotBlank() -> peerName
    else -> stringResource(R.string.moments_anonymous)
}
```

**教训**：搬模块前先 `grep` 一遍「对搬走的类的属性做 smart cast」的写法
（`x?.a?.isNotBlank() == true -> x.a`、`if (x.a != null) x.a.foo()`），它们会在搬迁后集体失效；
编译器会一次性报全，改起来很快，但别误判成逻辑 bug。

### 51.4 验证

| 项 | 结果 |
| --- | --- |
| `:engine:assembleRelease` | ✅ `BUILD SUCCESSFUL in 27s`，产物 169,385 字节 |
| aar 内容 | ✅ `classes.jar` + `R.txt` + `res/values/values.xml` + `proguard.txt` |
| app `assembleRelease` | ✅ `BUILD SUCCESSFUL in 1m 55s`（修掉 smart cast 后） |
| 装机 | ✅ `install -r` 输出 `Success`（未截断，§50.8 的教训） |
| 课表渲染 | ✅ 第 9 周网格、时间列、周次栏全部正常（`Session` 从 aar 读登录态成功） |
| 工作圈联网 | ✅ logcat `MomentsApi: GET /timeline?from_feed_id=&size=10 -> 200`，动态列表渲染出真实内容 → **协议层从 aar 端到端可用** |
| 闭源效果 | ✅ `app/src/main/java` 下 `nginx-1.app.nchu.edu.cn` / `/api` / `/staffs/*` / `/message/*` / `/moments/api` 命中数为 **0**（仅剩若干注释里的路径描述与门户网页地址） |

**仍然公开的部分**（诚实记账）：`campusnet/SrunApi`（校园网认证站点）、`library/LibrarySeatApi`
（图书馆座位站点）、`work/WorkCatalog`（办事大厅网页入口，30 多个 `go.nchu.edu.cn/?id=` 之类
的**网页**地址）、`ui/OaWebViewActivity` 的 host 判断 —— 这些是各自独立子系统的站点地址，
不涉及主 API 协议；要一并搬需要另开一轮（它们和 UI/WebView 耦合更紧）。

### 51.5 本轮踩坑

- **同步脚本不能带 `/PURGE`，但删文件必须显式删**：robocopy `/E` 只加不减，dev 树里删掉的
  `net/`、`core/` 在 repo 里仍在，`git status` 一片平静 —— 必须 `git rm -r` 显式删掉，
  否则 CI 上 aar + 源码两份同名类直接重复类失败。
- **`.gitignore` 只影响 git，不影响 robocopy**：`engine/` 被忽略不等于不会被同步过去，
  必须在 `/XD` 列表里也加上 `engine`。

---

## 50. 第五十轮（2026-10-30）：深浅色真机切换 / 双击回顶 / 添加页转场与周次交互 / 叠加块翻转（1.1.19 / versionCode 71）

用户五条（按原话）：①「切换深浅色模式时，app 没有正确切换深浅色」；②「双击滚到页面顶部后，再次滚到页面下部，
点击个人头像后，没有任何操作，app 就自动滚到了页面顶端」；③「课程添加页面进入与退出页面缺失动画，参考进入
二级菜单的动画；全部/单周/双周可以都不选，用户可以选择特殊周次上课；选择上课周次时参考长按滑动快速多选；
在上课周次旁边添加自定义时间，可以选择一天中任意上课时间，动效与交互参考视频四」；④「给课表中的叠加课程
增加翻转动画，动效参考 lanhang-lite」。（⑤=③的拆分，用户按视频编号叙述。）

### 50.1 深浅色：把「系统真值」从 Activity 缓存里救出来

上一轮（§48.1）已经把 `ThemeState` 改成读 `Application.resources.configuration`，
但真机仍是「切了不生效」。这轮补三点：

1. `ThemeState.init(app)` / 新增 `refresh(context)` 里改用 **`UiModeManager.getNightMode()`
   判真值**：`MODE_NIGHT_YES` → 深，`MODE_NIGHT_NO` → 浅；只有 `AUTO` / `CUSTOM`
   （本机没登记日落到日出时段）才退回 `resources.configuration.isNightConfiguration()`。
   原因：HyperOS 的深色开关是**改系统级 uiMode 标志**，不保证 `configChanges` 派发到我们的
   Activity；`UiModeManager` 读的是系统服务，永远是最新值。
2. `App.onActivityResumed` 里做**对账**：`wasDark` 与 `refresh()` 后的 `isDark` 不同就
   `decorView.post { invalidate() }` + `MainActivity.onSystemThemeChanged()`，
   让 Compose 与 XML 背景（`page_bg` / `card_bg`）同帧换色。回到前台是对账的最好时机——
   用户从通知栏下拉切完深色必然回到 App。
3. `MainActivity.onSystemThemeChanged()`（`isFinishing || isDestroyed` 守卫）里额外
   `binding.root.invalidate()` + 课表 `onThemeChanged()`，因为自绘课表不吃 Compose 重组。

**验证**：模拟器 5 条路径（设置切档位、`cmd uimode night y/n`、杀进程重启）全部正常，
但**无法复现用户录屏里的真机现象**（HyperOS 通知栏开关）。修法是针对「config change 未可靠派发」
这一根因做的加固，需真机确认。

### 50.2 双击回顶后又自己跳回顶部（真因：`LaunchedEffect` 用了旧 tick 重跑）

`ScrollToTop` 是 `object { var tick; var tab }` 的全局信号，各页 `LaunchedEffect(ScrollToTop.tick)`
监听。问题出在**从二级页返回时**：列表的组合被销毁重建，新的 `LaunchedEffect` 拿到的是**同一个旧 tick 值**，
于是「上一轮的请求」被当成新请求又执行一次 —— 用户看到的就是「点一下头像（无操作）就自己滚回顶部」。

`ScrollToTop.tab` 仍留着上次的 tab 值，所以「tab 相等」的守卫拦不住。

修法：新增 `@Composable fun ScrollToTopEffect(tabIndex, onScrollTop)`，
内部 `val handled = remember { intArrayOf(ScrollToTop.tick) }` 记录**本次组合的基线**，
`LaunchedEffect` 里 `if (ScrollToTop.tick == handled[0]) return`，只有真的收到新 tick 才滚。
四处一次性替换（工作圈 / 工作 / 我的 / 成绩）。

**教训**：全局事件信号 + `LaunchedEffect(key)` 的组合，必须用 `remember` 存「进入组合那一刻的基线值」，
否则组合重建会把历史事件重放一遍。

### 50.3 添加页转场：复用二级页那套参数表

新增 `ui/miuix/NavMotion.kt` 之外的唯一参数来源仍是 `NavPush` 里的 `NavMotion`
（`DURATION_MS = 300` / `FastOutSlowInEasing` / `PARENT_TRAVEL = 0.24f` / `PARENT_DIM = 0.18f`）。
`ScheduleFragment.showManualCourseEditor()` / `hideManualCourseEditor(animated)` 用
`ValueAnimator + PathInterpolator(0.4f, 0f, 0.2f, 1f)`：

- 覆盖层 `translationX = (1 - p) * w`（右侧滑入）；
- 父层（`swipeRefresh`，fragment 根 FrameLayout 的 child 0）`translationX = -0.24f * p * w`；
- 黑色 scrim `alpha = 0.18f * p`。

退出时反相。`manualCourseClosing` 标志防重入，`onDestroyView` 里 `animated = false` 同步摘除。

### 50.4 周次：三档都能取消 + 长按拖选

**「全部」再点取消判不出来的坑**：原来用 `oddEven` 当档位状态，而 `oddEven` 的 `""` 既表示「全部」
又表示「未选」——`if (oddEven == code && code.isNotEmpty())` 对「全部」永远为假，点了没反应。
修法：引入独立 `weekMode`（`"all" | "1" | "2" | ""`），`selected = weekMode == code`，
再点自己就 `weekMode = ""` **并清空 `weeks`**（「都不选」应该就是全空）。
取消档位后手动点格子 / 长按拖选，都会把 `weekMode` 与 `oddEven` 一起清掉。

**长按滑动批量多选**（视频三）：整块周次网格挂 `pointerInput` + `detectDragGesturesAfterLongPress`，
`onGloballyPositioned` 记 `weekGridSize`，`weekAt(x, y)` 按 `6 列 × rows` 反算周号，
`applyWeekRange(from, to)` 按 `dragSelect`（起点是选中还是未选中）决定这一笔是「刷上」还是「刷掉」。

### 50.5 自定义时间（视频四）

- `ManualCourseStore.ManualCourse` 新增 `startMinute` / `endMinute`（`-1` = 用节次），
  JSON 里存 `sm` / `em`，`hasCustomTime` 判断是否启用；
- 「上课星期」行右侧加「自定义时间」+ 自绘 `MiniSwitch`（42×24dp，`animateDpAsState` 滑 19dp）；
  不用 Miuix 的 `Switch`——它自带 48dp 行高，塞进标题行会把整行顶爆；
- 开关打开后「上课节次」行文案变 `08:00 - 08:45`，点击弹 `TimeSheetDialog`：
  全屏 35% 黑遮罩 + 底部 20dp 圆角面板 + 取消/上课时间/确定 + **四列滚轮**（时:分 — 时:分），
  每列 `LazyColumn` + `rememberSnapFlingBehavior`，上下各留两行 `contentPadding`，
  于是「首项 = 中间那一行」——选中值直接取 `firstVisibleItemIndex`，不用算偏移；中间行加粗高亮。
- 编辑器根容器从 `Column` 改成 `Box`，sheet 叠在上面（不开窗、不 Dialog，和覆盖层同一套）。

### 50.6 叠加块翻转（视频参考 lanhang-lite）

`onDoubleTap` 从「裸换内容」改成绕 Y 轴翻面：`animate().rotationY(90f)`（130ms，Decelerate）
→ `withEndAction` 里换 `flipIdx` / `render()` → `rotationY = -90f` → 转回 0（AccelerateDecelerate）。
`pivot` 显式设为中心，翻转中再双击直接忽略（否则两条动画抢 `rotationY`，块停在半路）。

### 50.7 验证（模拟器 emulator-5554，1220×2656，第 9 周）

| 项 | 结果 |
| --- | --- |
| 添加页进入/退出动画 | ⚠️ 静态截图只能证明页面渲染正确，动画需真机/录屏确认 |
| 三档全不选 | ✅ 点「全部」后三档都不高亮、1~20 全部熄灭，截图确认 |
| 空周次保存 | ✅ 名字填 `NoWeek` + 空周次 → ✓ 点亮 → 保存成功（「已添加的课程 2 门」） |
| 长按拖选 | ⚠️ 代码已接（`detectDragGesturesAfterLongPress`），`adb` 长按+拖动难以稳定模拟，需真机确认 |
| 自定义时间开关 | ✅ 开关打开 → 节次行变「08:00 - 08:45」，节次网格自动展开 |
| 时间滚轮面板 | ✅ 四列 06..10 / : 00..02 / 08..10 / : 43..47，中间行 08:00 — 08:45 加粗；点「确定」关闭并回写 |
| 叠加块翻转 | ⚠️ **未验证**：第 9 周无叠加课（每格一门），需在有叠加的周上双击 |
| 深浅色 | ⚠️ 模拟器无法复现真机现象；已按「UiModeManager 真值 + 回前台对账」加固 |
| 双击回顶 | ⚠️ 代码已修（`remember` 基线），`adb` 双击间隔 >300ms 无法模拟 |
| 测试数据清理 | ✅ `TestTn` / `NoWeek` 都已从「已添加的课程」里删掉，课表无残留 |

### 50.8 本轮踩坑

- **`adb install -r` 的输出被 `Select-Object -Last 1` 吃掉**，看起来装上了其实装的还是上一版 ——
  于是「点『全部』没反应」被我当成逻辑 bug 又查了一遍。教训：**装包后必须看到 `Success` 字样**，
  并且用「只有新版本才有的字符串」（例如「上课时间」）确认代码真的进了包。
- **截图坐标与 `uiautomator dump` 的 bounds 差一个缩放系数**（本轮实测约 1.41，且随 y 增大而增大）：
  我一直用「看截图估坐标」的方式点，连点四次全空。**一律 dump 取 bounds 中心**，别再目测。
- **覆盖层只能覆盖到 `fragmentContainer` 的底边（y=2397）**，y>2397 的「删除」按钮被底栏吃掉，
  点击直接切到别的 Tab。要删列表项必须先把内容滚上来。

---

## 49. 第四十九轮（2026-10-30）：课表右上角「+」→ 整页添加课程（1.1.18 / versionCode 70）

用户原话：「在课表的右上角添加一个加号键，作为添加课程的按钮，点击即进入，不需要二次确认，
参照这个添加课程的页面做你的ui，课程颜色不需要配置，按照你的取色逻辑保证不要与现有课程重复，做完后push」。

### 49.1 入口：右上角，不是左上角

`ScheduleHeader` 的加号按钮从「回到本周」右边（左上区）挪到**最右槽位**（视图切换按钮之后）。
实机 dump 到的 bounds：`[1117,382][1173,438]`。

### 49.2 弹窗 → 整页

旧版点「+」弹 `showMiuixContent` 卡片，字段全摊在一个小卡片里，还会被圆角裁切。现在改成**整页**：

| 区块 | 内容 |
| --- | --- |
| 顶栏 | `✕` / 标题（添加课程·编辑课程）/ `✓`（课程名非空才点亮） |
| 卡片一 | 课程名称（必填）、地点、教师，左标签 + 右对齐占位 |
| 卡片二 | 上课星期：一~日 七个胶囊 |
| 卡片三 | 上课节次：折叠成「第 N - M 节 ›」，点开是起/止两段 `1..PeriodClock.count` 网格 |
| 卡片四 | 上课周次：全部 / 单周 / 双周 + 1~20 六列多选，手点格子会清掉单双周标记 |
| 卡片五 | **没有** —— 用户明确说课程颜色不用配，交给 `CourseColors` |

页内另有「已添加的课程 N 门」，点一行进编辑、行尾「删除」。

承载方式是**课表根 FrameLayout 的覆盖层**（`ComposeView` + `addView`），不是 Dialog：
等价于进了个子页面，底下课表的数据与滚动位置都还在。配套 `OnBackPressedCallback`
（否则按返回直接退到桌面）与 `onDestroyView` 里清引用（否则下次点了没反应）。

### 49.3 颜色：沿用「整学期课名均分色相」，不另做调色板

用户要「按你的取色逻辑保证不要与现有课程重复」。项目原有 `CourseColors`：把本学期**所有**课程名
排序后 360° 均分、`hueOf(name)` 查表、表空时哈希兜底。手动课以前不在表里 → 走兜底 → 可能撞色。

修法**不是**另写分配器（第 28 轮已被用户否决），而是把手动课名喂进同一张表：
`ScheduleRepository.allCachedCourseNames()` 返回前 `addAll(ManualCourseStore.usedNames())`。
增删手动课后必须 `CourseColors.invalidate()` + 清 `ScheduleFragment.courseColorMap`，否则还是旧表。

### 49.4 顺手修掉三个真 bug

1. **连堂课被拆成两块**（本轮引入）：`mergeCourses` 原来只看排序后的**相邻两条**。
   手动课正好落在连堂第一节时（周一高数 1-2 节 + 新加的课第 1 节），高数 1 和 2 之间夹了别的课，
   合并被打断 → 网格上裂成「第 1 节」+「第 2 节」两块。改成先按（课名 + 教室）**分组**、
   组内各自合并，再统一按起始节排序。
2. **课程块点了没反应**（第 48 轮就埋下了）：`GestureDetector` 用 `SimpleOnGestureListener`
   而**没有覆写 `onDown`** —— 它默认返回 false，GestureDetector 拿到 DOWN 就认为「这次触摸不归我管」，
   后续 UP 与第二次 DOWN 全部丢弃，单击详情、双击翻转一起失效。补 `override fun onDown(e) = true`。
3. **返回键直接退出 App**：覆盖层是普通 View、不在返回栈里，加 `OnBackPressedCallback`。

### 49.5 验证（模拟器 emulator-5554，第 9 周）

| 项 | 结果 |
| --- | --- |
| 右上角「+」 | ✅ dump `[1117,382][1173,438]`，在视图切换右侧 |
| 整页渲染 | ✅ 各卡片与参考图同构（✕/标题/✓、三行输入、星期、节次、周次网格） |
| 输入 + 保存 | ✅ 输入 `TestCourse` → ✓ 变蓝 → 保存后页内出现「已添加的课程 1 门」 |
| 进网格 | ✅ 周一第 1 节出现叠加块（右下角红点），手动课确实进了当天数据 |
| 连堂不裂开 | ✅ 修 `mergeCourses` 后周一 1-2 节恢复整块 |
| 单击看详情 | ✅ 点块中心弹出「高等数学B1 / E302 / 舒琳馨 / 4-19 周 / 01-02 节」 |
| 双击翻转 | ⚠️ **未验证**：`adb input` 两次点击间隔 >300ms，被判成两次单击（弹详情）。代码路径与上游一致，需真机手指确认 |
| 返回键 | ⚠️ 未单独验证（回调已就位） |
| 清理测试数据 | ✅ 测试课已删，高数恢复 3 块、无残留 |

### 49.6 踩坑（重要）

- **`adb` 截图缩放导致坐标全错**：本轮前四次点击全部点空（课程块、✓、✕），原因都是「拿截图目测的
  坐标去点」，而 `uiautomator dump` 的 bounds 与截图位置差 100~170px。**一律先 dump 再取 bounds 中心**。
  这次连「点了没反应」都被误判成代码 bug，白花了半小时去查 `GestureDetector`。
- **PowerShell 5.1 不能用来改 UTF-8 源码**：`Get-Content -Raw` + `[IO.File]::WriteAllText`
  把 `build.gradle.kts` 的中文注释写成 GBK 乱码、还吃掉换行，直接 BUILD FAILED，只能从
  `push/repo` 拷回再用 edit 工具改。改源码一律用 edit。

---

## 48. 第四十八轮（2026-10-30）：跟随系统深浅色 / 课表叠加翻转 / 手动添加课程 / 工作圈滚动记忆

用户五条：①「深色模式下 app 颜色没有随系统自动切换」；②课程叠加时应显示与课程颜色对应的点、
双击翻转而不是分两列；③手动添加课程（参考上游 lanhang-lite + nexio 课表 UI，miuix 化）；
④把后端与接口代码全部闭源搬进 aar（见 §48.6，**本轮未开始**）；⑤工作圈已经在顶部时再下滑、
点头像进他人主页返回仍跳回顶部。

### 48.1 深浅色不跟随系统的真因（两处，都修了）

**第一处**：`util/LocaleHelper.wrap()` 在「界面语言 ≠ 跟随系统」时用
`base.createConfigurationContext(config)` 包一层，而 `config` 是
`Configuration(base.resources.configuration)` 的**整份快照（含 `uiMode`）**。
这个 override 优先级高于系统，`uiMode` 被永久冻在「包裹那一刻」的值 ——
系统之后怎么切夜间模式，`Activity.resources` 都是旧值。
修法：wrap 的基准改成 `(base.applicationContext ?: base).resources.configuration`，
不再把 Activity 那份缓存配置整份拷进去。

**第二处**：全 App 的 Compose 主题入口各自读 `isSystemInDarkTheme()` / `Context.isNightMode()`，
读的都是 Activity 缓存的那份 configuration，而切 uiMode 是否会真的让 Activity 重建
取决于机型、`configChanges` 声明与 AppCompat 的 `applyDayNight` 时机，不可靠。

新增 `ui/miuix/ThemeState.kt`：**进程级 Compose 状态**，读
`Application.resources.configuration`（进程真值、不被 LocaleHelper 包裹），并注册
`Application.registerComponentCallbacks` 接住后续变化。`isDark` 把「系统值」与用户强制档位
（`Session.themeMode` = system/light/dark）合成最终结果。

改造点：`App.applyTheme()` 先 `ThemeState.applyMode(...)`；`App.onCreate` 在
`AppLanguage.apply(...)` 之后调 `ThemeState.init(this)`；`MainActivity.nightMode`、
`MineFragment` / `MomentsFragment` / `WorkFragment` / `OaWebViewActivity` 的
`LanhangMiuixTheme(darkTheme = ...)`、`ScheduleStyle` 全部改成读 `ThemeState.isDark`。

**保留 `AppCompatDelegate.setDefaultNightMode`**：XML `colorResource` 那一层（`page_bg` /
`card_bg`）和自绘 View 仍靠 resources configuration，去掉会花屏；Compose 层读
`ThemeState.isDark` 则负责「立即重组、不等 Activity 重建」。

**设备实测（emulator-5554，双向，都通过）**：

| 操作 | 结果 |
| --- | --- |
| 主题模式设为「跟随系统」+ 系统 light（`ui_night_mode=1`） | App 立刻浅色（白底、彩色浅块） |
| `cmd uimode night yes`（不重启 App） | App **立即**变深色，无需重建 Activity |
| `cmd uimode night no` | 立刻变回浅色 |

**排障提示（下次别再绕）**：一开始看到「系统浅色、App 仍全黑」以为修复没生效，其实是
`Session.themeMode` 被用户手动设成了 `dark`（设置 → 外观 → 主题模式 显示「暗色」）——
**先确认档位再怀疑代码**。另外 `adb shell input tap` 的坐标要用
`uiautomator dump` 里的 `bounds` 中心，凭截图肉眼估的 y 会偏 10~300px（截图被缩放显示），
本轮因此白按了三次「外观」行。

### 48.2 课表叠加：合成一块 + 右下角色点 + 双击翻转

原来是泳道分列（`laneLayout` 算出 `lane/lanes`，同一时段的两门课各占半列）。
用户要求改成上游 lanhang-lite 的做法：**叠成一整块，右下角一个与课程同色的点，
双击在同一组的几门课之间翻转**。

- 新增 `overlapFlip: HashMap<String, Int>`（Fragment 级，键 = `天|课名@起始节/…`），记住每组当前翻到第几门；
- `laneLayout` + `LaneInfo` 删除，新增 `overlapGroups(blocks)`：按 `periodStart` 升序求**区间连通分量**
  （`b.periodStart <= currentEnd` 就并入同组，并延长 `currentEnd`），一组 = 一个叠加块；
- `renderGrid()` 的课程块循环改成 `for (group in overlapGroups(dayBlocks))`：
  一个全宽 `FrameLayout`（`colW` 宽、`spanRows` 高）+ `nameView` + `roomView`
  + 仅当 `group.size > 1` 时加的 6dp 圆点（`Gravity.BOTTOM or END`），
  局部 `fun render(index)` 负责背景/文字色/圆点色的整体重刷；
- 手势换成 `GestureDetector`：`onSingleTapConfirmed` → `showCourseDetail(...)`（**不能用
  `onSingleTapUp`**，否则双击的第一下就把详情弹出来了）；`onDoubleTap` → `flipIdx = (flipIdx + 1) % group.size`，
  写回 `overlapFlip` 并 `render(flipIdx)`。

**本轮未验证**：第 9 周课表没有叠加课程（每格一门），只有编译级把握。
下次在第 6 周或任意有叠加的周上双击确认「圆点出现 + 翻转 + 不再分两列」。

### 48.3 手动添加课程（新功能）

- `schedule/ManualCourseStore.kt`：`data class ManualCourse(id/name/teacher/room/dayOfWeek/
  periodStart/periodEnd/weekStart/weekEnd/oddEven)` + `object ManualCourseStore`
  （SharedPreferences + JSON，内存缓存，`all/upsert/remove/forDay`）。
  纯本机数据，不上传、不参与任何接口。
- **注入点是唯一的**：`ScheduleFragment#getCoursesForDay()` 末尾
  `result.addAll(ManualCourseStore.forDay(dayIdx, week))` 再 `sortedBy { periodStart }`，
  于是网格 / 列表视图 / 导出 / 桌面组件全部自动包含手动课，不用各处再判断。
  注意原来那行 `data.schedule?.find{...} ?: return emptyList()` 也改成**回退到手动课**，
  否则服务端没数据的那天手动课会消失。
- `schedule/ManualCourseScreen.kt`：miuix 面板（`ManualCoursePanel`）+ 表单（`ManualCourseForm`），
  列表 → 点一门进编辑（照 nexio 的层次，不把所有字段摊在一页）。字段全走
  `MiuixTheme.colorScheme` 令牌；星期/单双周用自写 `MiuixChip` 胶囊。
- 入口：`ScheduleHeader` 新增 `onAddCourse`，在最左侧「回到本周」右边加 `MiuixIcons.Add` 图标
  （都是「改课表本身」的操作，语义一组）；`ScheduleFragment#showManualCourseEditor()`
  用 `showMiuixContent(activity) { dismiss -> ... }` 承载，保存/删除后 `renderAll()`。

**踩坑**：第一版两个 `Column` 只有 `verticalScroll` 没有最大高度 —— 内容比弹窗卡片高时会被顶出卡片，
标题「手动课程 / 添加课程」被圆角切掉一半。修法：`.heightIn(max = 520.dp)` + `verticalScroll`
（夹住高度才滚得起来）+ `padding(vertical = 6.dp)`。

**设备实测**：页头「+」→ 面板（标题/说明/空态/添加课程/关闭，标题不再被裁）、
「添加课程」→ 表单（课程名/教师/教室 + 周一~周日 chip + 节次 1~12 + 周次 1~30 + 每周/单周/双周 +
保存/取消）全部正常渲染。**未验证**：真正保存一门课并看它出现在网格里（本轮时间用尽）。

### 48.4 工作圈「回顶部」记忆失效（第五项）

根因：原来在 Compose 侧用 `LaunchedEffect(page)` 存滚动位置，但它在**列表页已经离开组合之后**
才跑，`firstVisibleItemIndex` 读到的是 0 —— 存进去的「记忆位置」永远是顶部；
再加上双击 tab 回顶之后基准本来就是 0，问题更明显。

修法：**在 Fragment 里、导航之前同步存**。

```kotlin
// 文件级
private const val TAB_LIST_KEY = "tab_moments"

// 字段
private var feedRestoreIndex = 0
private var feedRestoreOffset = 0
private var feedRestorePending = false
private val feedListState get() = com.lanhang.miuix.ui.miuix.stickyListStateFor(TAB_LIST_KEY)

private fun saveFeedScroll() {
    if (page != MomentPage.LIST) return
    feedRestoreIndex = feedListState.firstVisibleItemIndex
    feedRestoreOffset = feedListState.firstVisibleItemScrollOffset
    feedRestorePending = true
}
```

`openProfile()` / `openPublish()` / `openSearch()` 在离开 LIST 之前各调一次 `saveFeedScroll()`；
Compose 侧只留「数据到位后补回」：

```kotlin
LaunchedEffect(page, items.size) {
    if (focusFeedId != null) return@LaunchedEffect
    if (page == MomentPage.LIST && feedRestorePending && items.isNotEmpty()) {
        feedListState.scrollToItem(feedRestoreIndex, feedRestoreOffset)
        feedRestorePending = false
    }
}
```

配套把 `ui/miuix/PageListStates.kt` 的 `stickyListStateFor(key)` 抽成非 Composable 访问器
（`rememberStickyListState` 内部复用它），这样 Fragment 才能直接拿到同一个 `LazyListState`。

**本轮未验证**：只有编译级把握（第 5 项要求「双击回顶后再下滑、进他人主页、返回仍在原位」，
需要多步手势 + 真实数据，本轮时间不够）。做法与 §47.5 的相册修复同一套，风险低。

### 48.5 本轮诚实记账

| 项 | 状态 |
| --- | --- |
| ①跟随系统深浅色 | ✅ 代码 + **设备双向验证通过** |
| ②课表叠加色点/双击翻转 | ⚠️ 代码 + 构建通过，**未设备验证**（第 9 周无叠加课） |
| ③手动添加课程 | ⚠️ 面板/表单设备验证通过，**保存后进网格未验证** |
| ④后端接口闭源搬 aar | ❌ **本轮未开始**（约 20 文件的结构性搬迁，需独占一轮） |
| ⑤工作圈滚动记忆 | ⚠️ 代码 + 构建通过，**未设备验证** |

基线扫描（下次做第 ④ 项时直接用）：公开源码里 `nchu.edu.cn` 域名出现在
`homePageUrl = "http://go.nchu.edu.cn/?id=10&state=<uuid>"` 一类常量；接口路径字面量去重后 10 类
（`/api`、`/api/`、`/message/history$q`、`/message/unread`、`/message/unread_count`、
`/moments/api`、`/staffs/$userId`、`/staffs/%s`、`/staffs/batch`、`/staffs/search`）。

本轮未发版（用户未要求 push）。

---

## 47. 第四十七轮（2026-10-29）：评论输入框 / 深浅配色 / 课表自适应「至少保证表头」

用户三条：①深浅模式配色不对（深色顶栏与工作圈应纯黑、评论区灰；浅色顶栏与内容应淡灰、评论区更深）；
②工作圈回复别人时输入的内容不显示、也发不出去，「只有最后一次输入的内容才会被发送」；
③自适应课表要**至少保证**行标题周一~周五、列标题 1~10 节完整（即使某天/某节没课），
只有周末有课或 11/12 节有课才追加并重新分配空间，别破坏泳道设计。

### 47.1 评论/回复输入框「打字不显示、发出去是最后一次」的真因

`ui/miuix/MiuixDialog.kt` 的 `showMiuixInput`，`text` 原来是 `setContent` 外的一个普通 `var`：

```kotlin
var text = ""          // ← Compose 外的普通变量
setContent { TextField(value = text, onValueChange = { text = it }, ...) }
```

`TextField` 的 `value`/`onValueChange` 确实改了它，但它**不是 Compose State**，值变不触发重组——
于是打的字屏幕上永远不更新；只有点「发送」那一刻 `text.trim()` 才把累积值一次性取走。
用户看到的就是「输入的不显示、发出去变成最后一次输入的那份」。

修法：把 `text` 移进 `setContent`，改成 `var text by remember { mutableStateOf("") }`。

**教训**：任何喂给 Compose 组件做 `value` 的变量都必须是 `MutableState`；
Compose 外的普通 `var` 当 `value` 传进去，等于给组件一个永不更新的快照。

### 47.2 深浅配色：顶栏/内容区同色，评论区分层

顶栏（`ShellBars`）与各页面内容区都用 `MiuixTheme.colorScheme.background`，
工作圈评论区用 `surfaceContainerHigh`。改一处色板就全覆盖（`ui/miuix/LanhangMiuix.kt`）：

| 模式 | background（顶栏 + 内容区） | surfaceContainerHigh（评论区） | surfaceContainerHighest |
| --- | --- | --- | --- |
| 深色 | `Color.Black`（纯黑） | `0xFF242424`（灰，比纯黑高一档） | `0xFF2D2D2D` |
| 浅色 | `0xFFF5F5F5`（淡灰） | `0xFFE4E4E4`（比淡灰更深） | `0xFFDCDCDC` |

深色=顶栏/内容纯黑、评论区灰；浅色=顶栏/内容淡灰一致、评论区更深一档拉对比。

### 47.3 课表自适应改成「至少保证表头完整」

上一轮（§46.2）是「只画有课的行列」——上半学期没早八、周末没课就砍行/砍列，表头跟着缺。
这次改成**保底 + 追加**：

```kotlin
val rowOrder = (1..PeriodClock.count).filter { p ->
    p <= 10 || dayCourses.any { list -> list.any { ci -> ci.periodStart <= p && p <= ci.periodEnd } }
}
val colOrder = (0 until 7).filter { d -> d < 5 || dayCourses[d].isNotEmpty() }
```

节次行至少画满 1~10、星期列至少画满周一~周五；只有 11/12 节、周六/周日真的有课才追加，
再按可见序号重新分配行高/列宽。行列自适应只改内部排布、不改内容层尺寸（§46.3），泳道三图跟手不变。

### 47.4 本轮验证状态（诚实记账）

- ✅ 评论输入框：输入 `hello123test` **即时回显**（修复前不显示）；发送取同一 State 故内容随之正确（未真发评论）。
- ✅ 深色配色：课表 + 工作圈顶栏/内容纯黑、评论区灰（截图 c1/c2）。
- ✅ 课表自适应：第 9 周显示周一~周五 × 1~10 节，表头完整（截图 c1）。
- ⚠️ 浅色配色：色值已改，**代码级确定**；但 `adb shell cmd uimode night no` 未能把 App 切到浅色，
  未拿到浅色设备截图。下次进 App 设置 → 外观 → 亮色 再截确认。

本轮未发版（用户未要求 push）。

### 47.5 进他人个人主页返回丢滚动位置（本轮补充修复）

用户：「工作圈或我的-相册里点击帖子别人的头像或名字进入别人的个人主页后，再返回没有回到帖子的原位置，
而是直接滚动到页面顶端」。

根因：进他人主页是页面级导航（工作圈 `page=PROFILE` / 相册 `MinePage.PROFILE_OTHER`），源列表页的组合被
**销毁重建**，`LazyListState` 虽是 sticky 对象（`rememberStickyListState` / `PageListStates.of` 跨组合保留），
但列表「先变空再填满」时下标会被夹走。工作圈已有 `feedRestoreIndex` save/restore（`MomentsFragment` 里、
`MiuiNavStack` 组合外，`LaunchedEffect` 存「下标+偏移」、返回且数据到位后 `scrollToItem` 补回）；
**相册 / 自己主页此前只有 `pageLists.of(key)` 拿 listState、没有 save/restore** → 返回跳顶。

修法（照通讯录 `ContactsScreen` L423-428）：在 `MineScreen` 的 `MinePage.ALBUM` / `MinePage.PROFILE` 分支：

```kotlin
val albumListState = pageLists.of(MinePage.ALBUM)
DisposableEffect(Unit) {
    onDispose { pageLists.savePosition(MinePage.ALBUM, albumListState) }
}
LaunchedEffect(state.selfItems.size) {
    if (state.selfItems.isNotEmpty()) pageLists.restorePosition(MinePage.ALBUM, albumListState)
}
```

`restorePosition` 内部 `savedPositions.remove(key)` 一次性补回；`items.isNotEmpty()` 才恢复（列表先空后满时
把存档留到数据回来再补，否则空列表 `scrollToItem` 会被夹到 0）。

设备实测（模拟器 emulator-5554）：
- 我的→相册 滚到评论区（张嘉辉/冯雅涵）→ 点张嘉辉头像 → 他人主页 → 返回 → **回到评论区**
  （图片重新加载但位置补回，m3/m5 一致）✓。
- 工作圈 滚到中段（阳圣骐/王梓歆/田雨潇）→ 点阳圣骐头像 → 主页 → 返回 → **回到原位置**（w2/w4 一致）✓
  —— 工作圈既有 `feedRestoreIndex` 生效。

**教训**：页面级导航（进/退二级页会销毁重建源列表页组合）的列表，滚动位置不能只靠 sticky 的 `LazyListState`
对象——「列表先空后满」会把下标夹走；必须显式「离开存下标+偏移、数据回来补回」（`PageListStates.savePosition`/
`restorePosition` 或等价手动变量），且恢复要等 `items` 非空。

---

## 46. 第四十六轮（2026-10-28）：第 12 节课 + 课表行列自适应

用户两条：①「给课表的时间安排加一个第 12 节课，时间为 21:45 到 22:30」；
②「做一下自适应课表功能：若某一行/某一列没有课程安排，则自动隐藏这一行/这一列，
剩下的行/列重新分配屏幕空间，以达到更高的屏幕空间利用率；注意不要破坏原本的泳道动画设计」。

### 46.1 第 12 节：只改 PeriodClock，其余全部改成引用它

`schedule/PeriodClock.kt` 的 `ranges` 末尾加 `21:45-22:30`（与第 11 节间隔 10 分钟，和其余节次节奏一致），
`count` 自动变成 12。**不要再往别处抄时间表** —— 这次顺手把现存的两份副本都改成引用：

| 文件 | 原来 | 现在 |
| --- | --- | --- |
| `ScheduleExporter.kt` | 自己一张 `periodTimes` 列表（11 项硬编码） | `(1..PeriodClock.count).map { ... PeriodClock.start/end ... }` |
| `ScheduleExporter.kt` ×2 | `if (node !in 1..11) continue` | `if (node !in 1..PeriodClock.count) continue` |
| `widget/ScheduleWidgetData.kt` | `if (number !in 1..11) continue` | `if (number !in 1..PeriodClock.count) continue` |
| `ScheduleFragment.kt` | 自己一张 `periodTimes` map（11 项硬编码） | 直接用 `PeriodClock.rangeText(p, p)` |
| `ScheduleWidgetRenderer.kt` | 早就用 `PeriodClock.count` | 不用改 |

`ClassReminder.kt` 的边界闹钟段本来就是 `for (period in 1..12)`，第 12 节天然有槽位。

### 46.2 行列自适应：只画「有课的」行列

`ScheduleFragment#renderGrid` 以前是「一律画满 11 节 + 固定 7 列」，于是只要后半学期没早八、
或者周末没课，屏幕就有一大片空气。改成先算两组「有课的索引」：

```kotlin
val rowOrder = (1..PeriodClock.count).filter { p ->
    dayCourses.any { list -> list.any { ci -> ci.periodStart <= p && p <= ci.periodEnd } }
}
val colOrder = (0 until 7).filter { d -> dayCourses[d].isNotEmpty() }
val rowIdx = IntArray(PeriodClock.count + 2) { -1 }   // 节次 -> 可见行序号
val colIdx = IntArray(7) { -1 }                        // 星期 -> 可见列序号
```

然后所有几何全部按「可见序号」算，**不再出现节次号直接当行号**：

- 行高 `usable / rowCount`（上限仍是 `dp(160) * textScale`）；
- 列宽 `(availW - timeW - gapW * colCount - 1) / colCount`（以前是写死 `/ 7f`）；
- 纵坐标 `yOfRow(i) = i * (rowH + gapH) + 上方休息带数 * bandH`；
- 课程块：`startIdx/endIdx` = 起止节次映射到的可见行，高度按 `spanRows` 而不是「节次数」——
  这样即使块中间有被隐藏的空行（正常数据不会出现，但兜底），块也不会画歪；
- 表头/时间列/网格线/课程块全部只遍历 `colOrder` / `rowOrder`。

**休息带（午休 4 之后、晚休 8 之后）**：只有「上方有可见行、下方也有可见行」时才画。
否则会出现「第 1-3 节有课、第 5 节之后没课」却挂一个孤立「午休」胶囊在底部的怪东西。
休息带自身的 y 取「k 之后第一个可见行的 y − bandH」，因为 k 这一行本身可能被隐藏。

### 46.3 为什么不破坏泳道动画（必读）

切周是三张静态位图跟手，位图按**整个内容层**尺寸拍（`capturePagerBase`）。
行列自适应只改变「内容层内部的排布」，**不改内容层自身尺寸**（contentGrid 是 match_parent，
行高变大就把内容填满、变小就留白，宽高都不外溢），所以：

1. 位图尺寸 == LayoutParams 尺寸 的前提不变（见 §40.1 的教训）；
2. 钉住时间轴的裁剪宽度 `gridAxisW = timeW + gapW` 与列数无关；
3. `startPager` 拍旧周、`post` 里拍新周，两个位图各自反映各自的周次布局，
   正好让「不同周行列数不同」的切换看起来是自然的重排，而不是错位。

本轮实测 9 → 10 → 11 → 10 连续切周，无 `第 -1 周`、无残影、无跳帧。

### 46.4 验证（模拟器 emulator-5554，1220×2656，深色）

| 周次 | 有课的星期 | 有课的节次 | 结论 |
| --- | --- | --- | --- |
| 第 9 / 10 / 11 周 | 周一~周五（周六日隐藏） | 1~10（第 11、12 节隐藏） | 列变宽、行变高，课程名一行放得下 |
| 第 6 周 | 周一、二、三、五（**周四隐藏**） | 1~10 | 4 列各占 1/4 宽，无空洞 |
| 第 3 周 | 无 | 无 | 「本周无课」提示（新加的 `rowCount==0||colCount==0` 兜底分支） |

### 46.5 踩坑：`input swipe` 的起止点必须落在 400~1100

用 `input swipe 150 1500 1150 1500` / `1050→150` 试切周，**整次手势被系统边缘返回手势吃掉**：
App 直接退到后台、前台变成桌面（`topResumedActivity=...googlequicksearchbox...MinusOneActivity`），
随后所有 `uiautomator dump` 全空 —— 看起来像「App 崩了」，实际是坐标踩到边缘手势区（§38.1 同款坑，
这次是两端都踩）。可复现的稳定区间：**`input swipe 1050 1500 450 1500 200`（下一周）/
`input swipe 450 1500 1050 1500 200`（上一周）**。

### 46.6 列表视图同步隐藏空日（用户追加要求）

用户：「列表视图也可以隐藏掉，做完之后发版到 github 即可」。

`renderWeekView` 先一次性把 7 天的课程算好：

```kotlin
val mergedByDay = (0 until 7).map { d -> mergeCourses(getCoursesForDay(data, d, week)) }
val totalCourses = mergedByDay.count { it.isNotEmpty() }
for (dayIdx in 0 until 7) {
    val merged = mergedByDay[dayIdx]
    if (merged.isEmpty()) continue   // 空日：摘要卡 + 课程卡整块不画
    ...
}
```

- 空日连**摘要卡**（`item_day_summary`）一起不画，日期条剩几天就几天，宽度自动均分；
- 原来「周 X 没有课程」那行文案的整段 if/else 删掉（空日已经 continue）；
- 一天课都没有时（`totalCourses == 0`）在列表流里补一条「本周无课」。

实测：第 6 周列表只剩周一/二/三/五四张卡（周四整天消失），第 9 周全 5 天都在。

### 46.7 坑：覆盖层空态 `tvEmpty` 会压在日程卡上

`fragment_schedule.xml` 里 `tvEmpty`（`layout_gravity="center"`，文案「本周无课」）是
`contentWeek` 上的**覆盖层**，一直是代码里点亮/熄灭它来表示「本周没课」。

改成空日隐藏之后出现了很怪的现象：**第 6/7 周明明画出了 4 天课，「本周无课」却浮在周一日程卡中间**。
排查过程（记下来，下次别再绕）：

1. `renderWeekView` 结尾日志确认 `有课天数=4`、设的是 `View.GONE`（8），代码逻辑没问题；
2. 静置后 `uiautomator dump` 里**查不到** `tvEmpty` 节点 —— 说明它多数时候确实是 GONE；
3. 但 `screencap` 截图里它稳定可见，位置固定在 `[516,1398][704,1467]`（页面正中）。

结论：它是覆盖层，**只要有任何一条路径把它点亮一次**（切周动画期间的空帧、`ensureContentNotEmpty`
的兜底分支等），它就会浮在所有内容之上，而且因为它是 `wrap_content` + `center`，
看起来「贴在某个卡片中间」而不是「页面空态」。我们 2 次尝试用条件收紧点亮逻辑都收不干净。

最终办法是**从视图树里删掉它**（最省事也最彻底）：

```kotlin
tvEmpty = view.findViewById(R.id.tvEmpty)
(tvEmpty.parent as? android.view.ViewGroup)?.removeView(tvEmpty)
```

空态改由 `renderWeekView` 在列表流里补 `noDataHint(R.string.schedule_week_no_course)`，
路径只有一条：有课就啥都不加，没课就补一条居中文案。删掉之后截图立刻干净。

**教训**：表示「空态」的元素不要做成覆盖层（`center` + `visibility`）——
列表还没画出来时它一定会浮在上面；做成内容流里的一个普通节点，状态就自然跟着内容走。

## 45. 第四十五轮（2026-10-28）：用户晚间 11 项清单（1.1.15 / versionCode 67）

用户两条：①「浅色下顶栏处在中间态时进二级页，会先弹到完全展开再收缩，应以当前大小为初始逐渐收缩」；
②「深色下顶栏状态改变依旧有严重拖影，参照浅色逻辑重新设计所有顶栏动画，统一成一个实现方式」。

### 45.1 「先弹到完全展开」的物理来源：两个 Animatable 拼 max

旧 `LanhangTopBar` 有两份动画状态：形态档 `morph`（0=大标题 96dp，1=紧凑 56dp）与滚动档
`scrollTerm`，最终值是 `max(shape, scroll)`。进二级页那一帧：

- `spec.hasBack` 变真 → `scrollTerm` 立刻归零（二级页不吃滚动）；
- `morph` 还停在上一页的 0（它的 `LaunchedEffect` 要下一帧才跑，`snapTo` 也在下一帧）；
- 拼出来 = `max(0, 0)` = **0 → 完全展开**。

于是无论上一页滚到哪里，都必须先弹回 96dp 再重收一遍。上一轮（第十二/十三轮）只补了
「已经收起到 0.995 以上就直接 snap」，中间态完全没覆盖。

### 45.2 改法：一个 Animatable，它就是画出来的收放度

`rememberBarCollapse(target, scroll)` 重写为单个 `Animatable`，三档分流（老规则全部保留）：

| 情形 | 动作 |
|---|---|
| 换 Tab（`ShellScroll.activeTab` 变了） | 与页面转场同拍的 tween |
| 形态档变了（进/退二级页、切课表） | **从当前值** tween 到目标，绝不回 0 重来 |
| 同一页手指滚动 | `snapTo`，逐帧跟手 |

进二级页时 `anim.value` 就是当前真实 0.5 之类 → 从 0.5 收到 1，不再经过 0。
`morph` / `lastCollapse` / `latch` 三个中间变量全部删除，只剩这一个数。

### 45.3 拖影：过渡期间子节点溢出 + 依赖外层 View 背景

自绘顶栏是个 `BoxWithConstraints(height = lerp(96dp,56dp,collapse))`，而子节点尺寸是按两端
**固定值**算的（按键行恒 56dp、标题位移按 96dp 推算）。过渡中 Box 比 56dp 还矮时，这些子节点
的像素会溢出边界；顶层 ComposeView 的背景是 XML 里的 `@color/page_bg`，它只在这个 View
被判定失效时重画 —— 溢出的白字/白块在深色底上残留一两帧，就是「拖影」。

两条修法（都加在顶栏本体上）：

```kotlin
.height(barHeight)
.background(MiuixTheme.colorScheme.background)   // Compose 每帧整块重画，不靠外层 View
.graphicsLayer { clip = true }                    // 溢出像素一律裁掉
```

### 45.5 课表：快速连滑两次跳到「第 -1 周」（第三项）

**已用 `adb shell input swipe` ×3 稳定复现**：第 9 周连滑三次左 → 周次栏显示「第 -1 周」，且再也回不来。

真因是**多条收尾动画互相踩共享字段**。`finishPager` 的 `onAnimationEnd` 里直接读
`pagerTarget` / `pagerBase`，而这两个字段会被下一段手势改写、或被 `cleanupPager` 复位成哨兵值
**-1**；`ACTION_DOWN` 撞上上一段动画时走的是 `finishPager(false)`——那只是**又起一条** 210ms 动画，
字段要等它结束才复位。于是新手势在「currentWeek 已改、pagerBase 还是旧值甚至 -1」的状态里起手，
最终把 `currentWeek` 写成 -1。

修法三条：
1. 新增 `pagerSeq` 手势序号：`startPager` / `finishPager` / `abortPager` 各自 `++`，
   `onAnimationEnd` 第一句 `if (seq != pagerSeq) return` —— 过期动画一个字段都不许写，
   并且目标/基准/序号在起动画前**先快照**（`myTarget` / `myBase`），不再读共享字段；
2. 新增 `abortPager()`：`ACTION_DOWN` 撞上未结束的切周时**同步**清场（取消动画、`cleanupPager`、
   `currentWeek` 复原到手势开始的那一周、`renderAll`），不再等动画；所有写 `currentWeek`
   的地方都夹 `if (base in startWeek..maxWeek)`；
3. `startPager` 起手先 `pagerAnim?.cancel()`，`layer.post` 里也带 `seq != pagerSeq` 校验。

复测：9 →（左×3）12 →（右×3）10 →（左×2）11，全部正确，再无 -1。

### 45.6 课表：左滑时时间列一闪而过的拖影（第九项）

`startPager` 原来是**等 `layer.post` 里拍完「新周」位图之后**才把真实内容藏起来。于是手势的
第一帧是「没动的真实内容（自带一条时间列）+ 已经跟手的旧周位图（也自带一条时间列）」两份同屏，
零点多秒就消失 —— 正是用户说的「极短时间的拖影」。

修法：ghost 一拍好就立刻 `hideContentChildren(layer)`；`post` 里拍新周前
`showContentChildren(layer)` 放出来、拍完马上再藏回去（两个函数互补，成对调用）。

复测：20fps 抽帧看左边缘（时间列）四帧，每帧只有**一份** 5/6/7/8 节次列。

### 45.7 课表：下拉刷新时周次栏闪回本周（第十项）

`refresh()` 里 `applyWeekInfo(it)` 无条件 `currentWeek = it` **并顺手 `updateWeekPill()`**，
刷新的第一帧周次栏先被改成本周（用户看到的闪一下），`refresh` 后面才 `currentWeek = browseWeek` 复原。

修法：`applyWeekInfo(data, keepBrowseWeek: Boolean = false)` —— 刷新路径传 `true`，
只更新 `actualCurrentWeek`（本学期第几周），不动浏览周，也不在里面刷胶囊。

复测：先切到第 11 周，录屏 20fps 覆盖整次下拉刷新（圆环出现并旋转，已确认真的刷新了）——
**周次栏全程没有任何一帧与末帧不同**，刷新前后都是「第 11 周」。

### 45.4 本轮验证状态（诚实记账）

- `assembleRelease` 通过。
- 设备级逐帧验证**没做成**：用 `screenrecord` 录「我的（滚到中间态）→ 设置」，抽 20 帧测顶栏
  文字框高度，前 16 帧完全静止（深像素数恒定 150990）、后 4 帧才开始变化 —— 说明抽帧窗口没盖住
  真正的过渡段；且新加的 `background` 让「顶栏底色 vs 内容底色」不再有肉眼可见分界，
  原来的「按行找色块边界」量法失效。下轮改用「录屏 + 逐帧求相邻帧差分的最大值出现在哪一帧」
  来定位过渡段，再在过渡段内量标题字号。
- 深色拖影只有结构性把握（clip + 每帧重画），没有逐帧对比图。

### 45.14 页面级加载态统一 + 全项回归（第十一项收尾）

上一轮只做了「图片类」加载态，页面级还有三处是「图标 + 一行字」——用户看不出是在跑还是卡死：

| 屏 | 原状 | 现状 |
| --- | --- | --- |
| 通讯录 | `ContactsStateBox`（通讯录图标 +「加载中」） | `LoadingBlock(label=…)` 转圈 + 文案 |
| 消息列表 | `MessageStateBox`（同上） | `LoadingBlock(Modifier.fillMaxWidth().height(240.dp))` |
| 私信列表 | `ImStateBox`（同上） | `LoadingBlock(Modifier.fillMaxWidth().height(240.dp))` |

**坑**：`LoadingBlock` 内部是 `fillMaxSize()`，放进 `LazyColumn` 的 `item {}` 里高度约束是无限的
（会撑成 0 或吃掉整屏），必须给显式高度（240dp）。非 lazy 的 `when` 分支（通讯录）才能直接用。

工作页的 `loading` 恒为 `false`（本地 `WorkCatalog`，进页面即有内容），不需要加载态 —— 这是设计而不是遗漏。

### 45.15 第五轮全项回归（同一台模拟器，1220×2656，第 9 周）

装包 `pm install -r` 后逐项过：

| 项 | 结果 |
| --- | --- |
| 通讯录 / 消息 / 私信三页 | 全部正常渲染（`通讯录 → 组织架构 教职工 2368 人`、`消息 → 全部/评论/赞`、`私信 → 私信已连接 + 张敏佳会话`） |
| 私信连接（第五项回归） | `私信已连接`，一次成功，仍无 `IPC_DISCONNECT` |
| 课表连滑（第三项回归） | 9 →（左×2）11 →（左）11（已到学期末，到头不再前进）；→（右，起点 x=500）9 →（右）8，全程无「第 -1 周」 |
| 切周右滑又踩了老坑 | 起点写 x=250 的那两次**只生效一次**——`AGENTS.md` §38.1 早就记过：x≈250 会被系统边缘返回手势接管。留证：起点改 x=500 后连续两次右滑 10→9→8 全对 |

### 45.13 加载界面：图片占位 + 全屏看图转圈（第十一项）

用户原话：「所有加载场景（工作圈图片、我的身份码等）都要有加载界面」。
现状盘点：全项目只有 3 处 `CircularProgressIndicator`（成绩、身份码、工作圈列表），
**图片这一整类完全没有加载态** —— 九宫格在取图期间是透明洞，全屏看图是纯黑一片。

新增 `ui/LoadingUi.kt` 收敛成两个组件（新页面直接用，别再各画各的）：

| 组件 | 用途 |
| --- | --- |
| `LoadingBlock(modifier, label)` | 整页/整块加载中：居中转圈 + 一行 `footnote1` 说明文字 |
| `ImageLoadingBox(modifier, corner)` | 图片自己的骨架：圆角 `secondaryContainer` 浅色块 + 20dp 转圈 |

两处接线：

1. **工作圈九宫格**（`MomentsScreen.MomentImageGrid`）：`AsyncImage` 改成 `SubcomposeAsyncImage`，
   用 `loading = { ImageLoadingBox(...) }` / `error = { ImageLoadingBox(...) }` /
   `success = { SubcomposeAsyncImageContent() }`。取图中和取图失败都画骨架，
   失败也**不留透明洞**（看着像图丢了）。
   注意 coil 2.6 的 `SubcomposeAsyncImage` **没有** `content` lambda 这个重载，
   只有 `loading` / `success` / `error` 三个槽 —— 第一版写成 `) { state -> ... }` 直接编译失败。
2. **全屏看图**（`ImagePreviewActivity`）：图片 `load(url)` 有 `listener(onSuccess, onError)`，
   用它在黑底中央显示/隐藏一个代码 addView 的 `ProgressBar`（`DragDismissLayout` 本身是
   FrameLayout，加一个居中的子 View 不影响它自己的拖拽手势逻辑）。改成动 xml 会牵连
   `binding` 与 `currentPageView()` 的强转，风险不值得。

**验证**：装包后工作圈、个人主页、全屏查看器全部正常渲染（无崩溃、无空帧）；
但**没能截到「加载中」那一帧** —— coil 磁盘缓存命中，配 `adb emu network speed gsm` 限速后
1 秒内图就出完了，且 release 包不可 `run-as` 删缓存。所以占位骨架目前只有代码级 + 编译级把握。

**还没覆盖的**（诚实记账）：通讯录、消息列表、私信、个人主页首次加载这类「页面级空白」仍各写各的
（多数已有转圈，少数是空白）。下一步是把这些分支统一改成 `LoadingBlock`，并给身份码页
「进页面到拿到 token」之间补一层骨架。

### 45.12 课表字号与行高的大屏自适应（第六项）

用户原话：「课表所有文字与 UI 自适应，平板字号略微放大」。

**原来只朝一个方向自适应**：列宽/行高不够就按候选档降字号（`numSizeCandidates` 13/12/11、
`timeSizeCandidates` 8/7.5/7），但候选档的**上限是写死的** —— 平板上格子又宽又高，字还是手机那么大，
看着又小又空；行高上限也钉在 `dp(96)`，11 行铺不满就留下一大片空白。

改法（新增 `ui/UiScale.kt`，只放大静态字号与上限，不碰「放不下就降档」的判定）：

| 屏幕 | 系数 |
| --- | --- |
| 手机（`screenWidthDp` < 480） | 1.0 |
| 大屏手机 / 折叠展开（≥ 480dp） | 1.08 |
| 平板（`smallestScreenWidthDp` ≥ 600dp） | 1.15 |

`ScheduleFragment` 里加 `textScale` + `ts(v)`，把 7 处写死的 `textSize = 9.5f/8.5f/12f/13f/9f/10f`
与课程块 `nameSize` 三档全部过 `ts()`；两个候选数组整体乘系数、时间列量字宽也从 `8f` 换成 `ts(8f)`。
xml 里写死的 sp（`item_day_summary` 13/10sp、`item_day_section` 14sp）用 `scaleXmlText()`
在 inflate 之后递归乘一遍 —— **必须在设置代码字号之前调用**，否则会把后面显式设定的字号盖回去。

行高上限：`dp(96)` → `dp(160) * textScale`。160dp 只是极端保护（竖着看 4:3 平板别把一行撑成广场），
正常平板上 `usable / 11` 已经小于它，所以课表真正铺满到 tab 栏。

**设备验证**（同一台模拟器，改 `wm size / wm density` 模拟平板）：

- 平板 `1600x2560 @ density 200`（= 1280×2048dp，`smallestScreenWidthDp` ≥ 600 → 1.15）：
  课名/节次/时间全部放大且不裁字，行高填满，底部空白从约 400px 收到约 60px；
- 还原 `1220x2656 @ 468`：与改动前逐像素观感一致（`textScale == 1` 时 `ts()` 原样返回，无回归）。

### 45.8 「我的」消息角标：进过消息页就清零（第四项）

**现象**：我的页「消息」行右上角挂着数字点，点进去把消息看完、再退回我的页，数字还在。

两个独立原因，都修了：

1. **谁都不记账**：`GET /message/unread` 只是个查询，服务端没有「标记已读」的口子。
   新增 `mine/MessageReadStore.kt`：把「进消息页时看到的 messageId」记在本机
   （SharedPreferences，只留最近 500 条）。角标 = 当前未读 id − 已看过的 id。
   于是新互动仍会亮（新 id），老互动不再亮，重启也不复活。
   配套把 `InteractionFilter` 拆出 `unreadIdsMine()`（`unreadCountMine()` 改成它的 size），
   因为**要 id 才能记账**，光有数量没用。
2. **刷新时机不对**：`refreshUnread()` 只挂在 `MineFragment.onResume` 上，
   而消息页是 Fragment 内的 Compose 二级页 —— 返回时 `onResume` 根本不会再来一次。
   在 `goBack()` / `goRoot()` 退回根目录后补一次 `refreshUnread()`。

实测：装包前「我的」页角标 2，进消息页再返回后角标消失（`uiautomator` 里「消息」行后不再有数字节点）。

### 45.9 私信列表「ipc_disconnect」（第五项）

`IPC_DISCONNECT` 是融云 SDK 与本机 IPC 服务**短时断开**：进程刚被系统回收、刚强杀过 App、
SDK 内部服务还在重启，都会碰到。它本质是瞬时错误，而 `ImRepository.onError` 以前直接
`status = FAILED` 并把裸错误码当提示丢给用户（「IM 连接失败：IPC_DISCONNECT」），看着就像坏了。

现在：`isRecoverable()` 把 IPC_DISCONNECT / NETWORK_UNAVAILABLE / TIMEOUT / CONNECT_NAVI
判为可恢复（**token 类错误一律排除**，重连没用），可恢复且未超 3 次就退避重连
（0.8s / 3.2s / 7.2s），状态留在「连接中」，文案换成中文；`ensureStarted()` 在 FAILED 状态
重新进页面时把重试次数归还。真的连不上才失败，并提示「点右上角刷新重试」。

**未在本机复现**该错误（本次连接一次成功，logcat 是 `connect ok`），所以只有代码级把握。

### 45.10 个人主页长按动态 = 自由复制（第七项）

个人主页复用的是工作圈那张 `MomentCard`，卡片级 `combinedClickable.onLongClick` 会在
`isMine` 时弹「删除这条动态」—— 在自己主页里长按自己的动态正好命中。

给 `MomentsScreen` / `MomentCard` 加 `postMenuEnabled: Boolean = true`，
`UserProfilePage` 传 `false`：主页里长按整张卡片什么都不做，手势落到正文的
`SelectionContainer`（可拖把手选任意段落复制）。工作圈动态流仍是 true。

**本轮未做设备验证**（时间预算用尽），只有编译级把握。

### 45.11 发布工作圈：返回时询问保存草稿（第八项）

新增 `moments/PublishDraftStore.kt`（文本 + 图片 Uri 字符串，图片换行分隔，不含 bitmap；
恢复时逐条 `Uri.parse`，失效的直接跳过）。

- `openPublish()` 回填草稿（图片按 `MAX_MOMENT_IMAGES` 上限收）；
- `closePublish()` 在「有文字或有图片」时先弹「保存草稿？」，两个按钮各有行为：
  **保存草稿** = 落盘后离开，**不保存** = 销毁后离开；点弹窗外什么都不做（内容还在，最安全）；
- `publish()` 成功后 `PublishDraftStore.clear()`（草稿已用掉）。

**踩坑**：第一版用了 `showMiuixConfirm`，它的 `dismissText` 按钮绑定的是**空动作** ——
点「不保存」只关弹窗、留在发布页，草稿也还在。这类「两按钮都要有行为」的询问必须用
`showMiuixDialog(MiuixDialogSpec(actions = listOf(a to {...}, b to {...})))`。

实测（模拟器，未真发）：

| 步骤 | 结果 |
| --- | --- |
| 发布页输入 `drafttest` → 返回 | 弹「保存草稿？/ 你已经输入了内容…」+ 不保存 / 保存草稿 |
| 点「保存草稿」→ 重开（含重启 App） | 文本回填 `drafttest`、计数 9/2000 |
| 再返回 → 点「不保存」 | 回工作圈列表；重开发布页 → `0/2000`、无图（草稿已销毁） |

## 44. 第四十四轮（2026-10-28）：小部件「N 分钟结束」不刷新（1.1.14 / versionCode 66）

### 44.1 现象与真因

18:05 桌面 4×2「今日安排」还写着「思想道德与法治 · 5 分钟结束」，而课表上这节课 16:00-17:40。
**下课时间没有算错**，是小部件没有被重绘 —— 它停在 17:35 那次渲染上。

真因在 `ScheduleWidgetUpdater.refreshTick`（排「分钟心跳」的函数）第一段：

```kotlin
val ids = manager.getAppWidgetIds(ComponentName(app, ScheduleWidgetSmallProvider::class.java))
if (ids == null || ids.isEmpty()) { cancelTick(app); return }   // ← 只认 2×2
```

只放了 **4×2** 的用户，`ids` 永远是空 → 直接 `cancelTick` → 分钟心跳根本不存在。
小部件于是只在打开 App / 午夜闹钟 / 改尺寸时才重绘，课上完也不会自己消失。

真机证据（`adb shell dumpsys alarm`）：修复前只有
`*walarm*:com.lanhang.miuix.widget.ACTION_MIDNIGHT`，**没有任何 ACTION_TICK**；
修复后装包即出现 `*alarm*:com.lanhang.miuix.widget.ACTION_TICK`，
手动 `am broadcast` 一次也能立刻续上下一次（说明链式闹钟在跑）。

### 44.2 改法

- `refreshTick` 同时看 **2×2 与 4×2** 的实例：2×2 还要「翻到今天」才需要心跳，
  4×2 只要有实例就需要（它显示今天/明天，今天才有倒计时）。周课表变体没有倒计时，不参与。
- 兜底再上一层：两个带倒计时的小部件 xml 从 `updatePeriodMillis="0"` 改成 `1800000`（30 分钟）。
  主力仍是自己的分钟心跳，但心跳链一旦被系统清掉 / 应用被强行停止，
  系统这 30 分钟一次的 `onUpdate`（→ `updateAll`）能把「N 分钟结束」这种陈旧状态救回来。
  注意：`updatePeriodMillis` 属性**不能**在 XML 属性列表里插注释（编译期 AAPT 会报错），说明写在根元素之前。

### 44.3 教训

- 「没有这个变体」和「这个变体不需要」是两回事：按变体取实例的地方，要写清它到底覆盖哪几个 Provider。
- 任何依赖**链式闹钟**的 UI 刷新都要有一层系统级的兜底周期，否则一次丢链就静默停摆。

## 43. 第四十三轮（2026-10-28）：双击底栏回顶统一（1.1.13 / versionCode 65）

### 43.1 「一跳一跳」是分段 tween 造成的

`animateScrollToTopSlow()` 原来是**按可见项一屏一屏地 `animateScrollBy`**，每一屏一条独立 tween：

```kotlin
while (firstVisibleItemIndex > 0 && guard++ < 80) {
    val step = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: break
    animateScrollBy(-step.toFloat(), tween(perPage))   // ← 每屏重启一次动画
}
```

项高的页面（我的）看不出问题，列表项矮的**工作圈**就成了「一跳一跳地往上窜」——
屏与屏之间有停顿，每屏还要重新加速。

改成**一次连续动画**，节奏对齐课表页的 `scrollSlowly`（ValueAnimator +
`DecelerateInterpolator(1.7)`、时长 380~820ms 按距离）：

```kotlin
val distance = firstVisibleItemIndex * avgItemHeight + firstVisibleItemScrollOffset
val duration = (380 + (distance / 4f).toInt()).coerceIn(380, 820)
animateScrollBy(-distance, tween(duration, easing = Easing { t -> 1f - (1f - t).pow(1.7f) }))
```

距离靠「已过项数 × 可见项平均高度」估算：懒加载列表拿不到精确总高度，
但 `animateScrollBy` 滚过头会被夹在顶部，估大安全；估小时末尾补一记 `animateScrollToItem(0)`。

### 43.2 工作 Tab 双击没反应：Tab 常量写错了

`WorkPage` 里写的是 `if (ScrollToTop.tab == ShellState.TAB_MINE)`（从我的页复制过来的），
而工作页属于 `TAB_WORK`，所以工作 Tab 双击永远不满足条件。

### 43.3 验证

| 用例 | 结果 |
| --- | --- |
| 工作圈 滚动后双击 | 精确回到顶端（与全新打开顶部同帧，差异仅状态栏分钟数） |
| 工作 滚动后双击 | `wk1 vs wk2 = 23.99`（确实滚过）、`wk0 vs wk2 = 0.00`（回到原位） |
| 我的 滚动后双击 | 1.55（噪声级） |
| 动画连续性 | 12fps 抽帧相相关：运动期间**每帧都有位移**，无中途 dy=0 的停顿 |

### 43.4 教训

- 「慢慢滚」不要用多次 `animateScrollBy` 拼：每次调用都是一条新动画，观感必然分段。
  要一条曲线就一次性给足距离和时长。
- 复制粘贴的 `LaunchedEffect` 里最容易漏改 Tab 常量 —— 它是编译期合法的死逻辑，不看根本发现不了。

## 42. 第四十二轮（2026-10-28）：连堂的下课时刻 + 岛副标题（1.1.12 / versionCode 64）

### 42.1 「一节课」不等于 45 分钟

作息是 45+10+45（08:00-08:45 / 08:55-09:40），连堂就是 45+10+45+10+45。
课表数据把连堂合并成一条 `CourseBlock(start..end)`（见 `ScheduleWidgetData`），
但 `ClassReminder.infoOf` 原来写的是：

```kotlin
val endAt = PeriodClock.end(slot.period)   // ← 只取起始那一节，45 分钟后就「下课」了
```

后果：连堂的「距下课 N 分钟」提前约一小时响，超级岛倒计时也提前一小时归零，
课中勿扰在课间就被恢复。

修法：`lastPeriod = slot.courses.maxOf { it.end }.coerceAtLeast(slot.period)`，
`endAt` 取 `PeriodClock.end(lastPeriod)`；节次文案在 `lastPeriod > period` 时用新增的
`class_reminder_period_range`（「第 3-4 节」），否则仍是「第 3 节」。
`armBoundaries` 的下课边界闹钟、课中提醒阈值、岛倒计时全部走同一个 `info.endAt`，改一处全对。

小组件那一侧本来就用的是 `course.end`（`PeriodClock.endMinute(it.end)`），不受影响 —— 只有提醒这条链路错了。

### 42.2 超级岛副标题

`bigIslandArea.imageTextInfoLeft.textInfo.content` 原来是空串。现在填
「第 N-M 节 ｜ 教室 ｜ 教师」（`subtitleOf`：左侧设置成「地点」时不重复教室）。
注意：**收起的胶囊态只画标题 + 倒计时**，副标题要展开岛才看得到，这是系统模板决定的，改不了。

### 42.3 教训

- 时间类逻辑只有一个「唯一的作息来源」还不够，还要问一句「这个节次字段代表的是起点还是整段」。
- `Slot` 只带起始节次时，凡是要「结束时刻」的地方都得回到 `CourseBlock.end`。

## 41. 第四十一轮（2026-10-28）：切周位移 + 课中提醒关不掉（1.1.11 / versionCode 63）

### 41.1 「长按拖动时左侧时间轴向右伸展、周一到周日日期向下伸展」

真因不是缩放，是**位移**：内容层 `contentGrid` 是有 padding 的 `FrameLayout`
（`paddingHorizontal=4dp`、`paddingVertical=3dp` → 2.75 密度下 11px / 8px），
子 View 会被放在 padding 之内。三个叠加位图（幽灵、时间轴条、新页）是**在屏幕坐标里整层拍的**
（`capturePagerBase` 已经把 `content.left/top` 平移进去了），再被 padding 推一次 →
整体右移 11px、下移 8px。看起来就是「时间轴往右伸、日期行往下伸」。

实测证据：用户录屏里轴列 `08:00` 文字框 x0 = 32（静止）→ 45（拖动中），+13px；
我们的设备上复现为 +12px。

修法：新增 `overlayParams(layer, w, h)`，给三个叠加 ImageView 加
`leftMargin = -layer.paddingLeft`、`topMargin = -layer.paddingTop`，把位图摆回内容层的 (0,0)。

复测：`08:00` 文字框静止 `(29,955,55,51)`、拖动中 `(29,955,55,51)` —— 位置和尺寸完全一致。

同一处顺带给 `weekScroll` / `gridScroll` 加 `android:overScrollMode="never"`：
HyperOS 的 stretch 过滚动会缩放整块内容，是同一类「看着像放大」的观感来源。

### 41.2 课中提醒关掉后仍有课中提醒

`handleClassStarted` 原来是「课中提醒开着 **或者** 超级岛开着」就干活，
所以只关课中提醒、岛还开着时，课上依旧会把岛切成「已上课」并挂着；
另外 `rearm()` 只撤槽位闹钟，`RC_BOUNDARY_BASE` / `RC_IN_CLASS_BASE` 段落的边界闹钟会残留，
到点照样触发一次。

修法：
1. `handleClassStarted` 里 `if (!inClass) { 收岛 + 取消课中通知 + return }` —— 课中提醒关掉就等于课中彻底安静；
2. `rearm()` 里当「课中提醒 / 岛 / 自动勿扰」三者全关时，把边界闹钟整段撤掉。

### 41.3 教训

- 有 padding 的容器里摆「整层截图」，一定要用负 margin 抵消 padding，
  否则就是几像素的整体位移 —— 用户会形容成「伸展」。
- 开关语义要问「关掉它等于什么」，而不是「哪个变量控制它」：
  课中提醒关掉，岛、通知、残留闹钟三处都得跟着停。

## 40. 第四十轮（2026-10-28）：切周「放大一下」+ 底部留白（1.1.10 / versionCode 62）

### 40.1 「按住课表滑动时课表被放大一下，松手又缩回去」

真因：位图**按滚动容器尺寸拍**（`gridScroll` 1864px 高），却塞进
`FrameLayout.LayoutParams(layer.width, layer.height)`（内容层 1900px 高）+ `FIT_XY`，
两者差 2%，整页被拉长——肉眼看就是「课表被放大」。

实测证据：在 x=1100 一列上找行分隔线，静止时间距 142px，拖动中 145px（+2.1%）。

修法：新增 `capturePagerBase(layer, content)`，位图**永远按内容层尺寸**创建、
先铺 `page_bg`，再把滚动容器按它自己的 `left/top` 平移后画进去。位图尺寸 ==
LayoutParams 尺寸，`FIT_XY` 不再产生任何缩放。时间轴裁剪宽度同步改成
`content.left + axisWidthPx()`（内容层坐标）。

复测：静止 149px，拖动中 149px —— 完全一致；顶栏区域在拖动中与静止帧**逐像素相同**
（差异采样点 0），所以「顶栏闪动」是同一个拉伸引起的，一并消失。

### 40.2 时间表底端下移，贴近 tab 栏

原状：`gridContent` 底 2297，tab 栏顶 2397 —— 空了 100px。两个来源：
1. `renderGrid` 的 `panelPad = dp(22)` 是估算值，实际面板上下内边距只有 ~dp(9)，
   多留的 52px 让 11 节永远排不满；
2. 布局里 `contentGrid` 的 `marginBottom=8dp` + `paddingVertical=6dp` +
   `gridScroll` 的 `paddingBottom=2dp` 又是一层空白。

修法：`panelPad → dp(9)`、行高上限 `dp(56) → dp(96)`（高屏真的能撑满）、
`marginBottom → 2dp`、`paddingVertical → 3dp`、`gridScroll paddingBottom → 0`。
复测：`gridContent` 底 2365，tab 栏顶 2397，只剩 12dp 呼吸位。

### 40.3 教训

- 位图的 `LayoutParams` 必须等于**位图自身尺寸**；FIT_XY + 不匹配的尺寸 = 隐性拉伸。
- 「预留常量」不要写估算值（`dp(22)`），量不到就量不到，估出来的空白用户一眼就看见。

## 39. 第三十九轮（2026-10-28）：切周三个真 bug（1.1.9 / versionCode 61）

用户反馈「不同周的课表叠加在一起，且左侧时间轴会跟着课表一起移动」。逐帧放大后定位到三处：

1. **拍错对象**：`startPager` 先把「旧周位图」加进内容层，之后才 `post` 去拍「新周」——
   而拍的是外层 `FrameLayout`，于是**旧周位图被拍进了新周那张图里**，滑进来的那一页
   自带旧周的像素和一条偏移的时间轴 → 两周叠加。
   修法：新增 `activeContentScroll()`，位图**只拍滚动容器**（`gridScroll` / `weekScroll`），
   拍之前还把旧周位图临时 `INVISIBLE` 做双保险。
2. **透明底互相透出来**：`View.draw()` 得到的位图背景是透明的，两张图边缘叠在一起时
   能互相看见 → 更重的「叠加」观感。修法：`opaqueCopy()` 先铺 `page_bg` 再画内容。
3. **z 序**：新周那张图是 `post` 里最后 `addView` 的，默认压在时间轴之上；overdrag 时
   它还会盖住时间轴。修法：插入后 `pagerAxis?.bringToFront()`。

## 39.1 结论

切周现在是**三张实心位图**：钉住的时间轴（`[0, axisW)`，`translationX` 恒 0，永远最上层）
+ 旧周整页（跟手）+ 新周（从 `axisW` 起裁，不含轴）。手势期间内容层全部 `INVISIBLE`，
不重绘、不测量。

## 39.3 第四个 bug：手势起手点落在课程块上就滑不动

`OnTouchListener` 挂在 `gridScroll` 上时，手指落在课程块（可点击的 View）上，
事件被课程块消费，监听器根本收不到 —— 只有落在空白处才切得动周，
而且左右两侧空白位置不同，看起来像「往回切周随机失效」。

修法：新增 `SwipePagerLayout extends FrameLayout`（课表内容层容器，见 `fragment_schedule.xml`
的 `contentWeek` / `contentGrid`），在 `dispatchTouchEvent` 里**先于子 View** 拿到事件：
`pagerTouch` 返回 true 就吃掉后续事件，并给子 View 补一个 `ACTION_CANCEL` 复位按下态。
`ACTION_DOWN` 一律不消费，所以课程块点击、竖向滚动都照常。

## 39.2 教训（写死在这里，别再犯）

- 拍位图**只拍真正装内容的那一层**；任何覆盖物（ghost / overlay / shadow）都会被拍进去。
- 位图做动画前必须**垫不透明底色**，否则透明区域会互相透出。
- 手动 `addView` 的覆盖层，z 序要靠 `bringToFront()` 保证，不要依赖添加顺序跨帧成立。

## 38. 第三十八轮（2026-10-28）：切周定稿为「静态三图跟手」（1.1.8 / versionCode 60）

用户第三次反馈「闪动 + 拖影 + 颤动」。上一版（1.1.7）虽然去掉了强制测量，但手势期间
内容层还在被 `renderAll()` / 布局反复重排，而且整页（含时间轴）一起滑，滑出滑进看着就像坏了。
这一版把切周彻底做成**不碰视图的动画**：

1. **手势期间内容层全部 `INVISIBLE`**，屏幕上只剩三张静态位图：
   - 旧周整页（跟手指走）；
   - **钉住的时间轴**（从旧图裁 `[0, axisW)`，`translationX` 恒为 0）；
   - 新周位图（**从 `axisW` 开始裁**，所以滑进来的那页不会把自己那条时间轴也带进来）。
2. 新周位图在 `layer.post {}` 的下一帧拍 —— 那时系统已正常布局，**全程没有手动 measure/layout**。
3. `axisWidthPx()` 直接用 `renderGrid()` 里算出来的 `timeW + gapW`（存进 `gridAxisW`）。
   前两次尝试「猜结构」（headerRow 首子视图 / timeCol / 找「周一」文字）全部量到 0 或 12px，
   因为时间列会随重建失效，别再猜了。
4. 收尾 `cleanupPager()` 统一恢复 `visibility = VISIBLE` 与 `translationX = 0`。

## 38.1 踩坑：测试脚本自己制造的「往回滑没反应」

`adb shell input swipe` 从 x≈250 起手会被系统**边缘返回手势**接管（logcat 里
`edge-swipe is stealing input gesture`），看起来像「往回切周失效」，其实是整个 Activity 被返回掉了。
往后验证一律从 x≥400 起手：`input swipe 600 1500 1120 1500 500`。

## 38.2 仍未验证

- 真机手指连续快滑的手感（模拟器只能用 `input swipe` 近似，静态截图只能证明「没有错帧」）。
- 超级岛（HyperOS 焦点通知）文案 —— 本机不是 HyperOS，岛不可见。

## 37. 第三十七轮（2026-10-28）：切周「往下缩一下」的真凶 + 提醒补发（1.1.7 / versionCode 59）

### 37.1 画面「往下移一下又回来」+ 时间轴消失

上一轮为了拿到「新周」的位图，在拖动开始时调了 `layer.measure(EXACTLY w, EXACTLY h)` +
`layer.layout(...)`。**这就是元凶**：自绘课表被按一个不匹配的尺寸强制重排了一次 ——
行高被压缩（画面整体缩一下再弹回），左侧节次/时间轴那条整列画没了。
录屏拆帧可见定格证据（`artifacts/vid/f3/f024.png`：整张表被压扁、时间轴消失）。

修法：**永远不要手动 measure/layout 内容层**。现在只在拖动开始时拍一张「旧周」位图，
把周次切到目标周后交给系统正常布局（新周先摆到隔壁屏，旧周位图负责跟手），
任何一帧都不会看到重排过程。

### 37.2 刷新圆环盖住「第 N 周」

`swipeRefresh.setProgressViewOffset(false, 96dp, 150dp)`，把圆环挪到表头之下。

### 37.3 提醒「补发」：上课前启动也要提醒

用户口径：「课前 30 分钟提醒，如果 App 在课前 26 分钟才启动，仍应发送提醒」。

根因在 `ClassReminderPlan.upcoming()`：它只收 `triggerAt > now` 的槽位，
课前 26 分钟启动时那条 `triggerAt` 已经过去 4 分钟 → 整条被丢掉。

修法：
- 过滤条件改成 `startAt > now`（**课程还没开始**就还给用户），补发槽位的 `triggerAt`
  在过去，交给 AlarmManager 就是立刻响；
- 去重：`ClassReminderStore.markReminded/isReminded`（SharedPreferences 里的
  `epochDay-节次` 集合，写入时清掉今天之前的旧记录），`rearm()` 跳过已提醒的槽位，
  `handleAlarm()` 发出后记一笔 —— 否则每次重排（含 resync 心跳）都会立刻再响一次；
- 已经上课的槽位仍然排除（迟到补发只会干扰课堂）。

### 37.4 超级岛文案（未能真机验证）

按用户参考图把正文改成 `节次 ｜ 地点 ｜ 教师`，底部左侧标签「即将上课 / 已上课」+ 倒计时，
底部右侧「地点」+ 教室，`showContentDivider = true`。
**坑**：岛是 HyperOS 自己按 `miui.focus.param` 画的，模拟器不是 HyperOS，全部岛内容在本机不可见，
所以这一项只有编译级把握；参考图里的「上课勿扰」按钮需要协议里的按钮位（本地没有可靠 schema），未做。

### 37.5 未完成（诚实记录）

时间轴「滑出去再滑回来」只解决了一半：本轮加了「从旧图裁一条钉在上层」的补丁，
但**只在列表视图生效**（`showingGrid == false`）；7 列网格视图的时间轴画在自绘网格里，
`daySections` 不参与，所以网格模式下时间轴仍会跟着滑出去。下轮要么给自绘网格加一个
「只画时间轴」/「轴宽」的对外接口，要么把轴单独拆成一个 View。

## 36. 第三十六轮（2026-10-28）：课表切周的真凶找到并修掉（1.1.6 / versionCode 58）

用户录屏反馈「依旧非常严重的闪动、拖影，两周的课表叠成一个反复切换的 bug」。把录屏用 ffmpeg 拆帧
（`fps=8, scale`）逐帧看，问题终于定位 —— **不在动画，在手势分发**，三个原因叠在一起：

1. **`onTouch` 一直返回 `false`**：横向拖动被压在下面的 `ScrollView` 拿去做自己的横向滚动
   （课表内容比屏幕略宽，有横向滚动余量）。于是「我平移的位图」和「它滚动的真实内容」同时在动，
   速率还不一样 —— 这就是拖影与错位。
2. **`SwipeRefreshLayout` 被拉起来了**：录屏里能看到白色刷新圆环正好压在「第 N 周」胶囊上转圈
   （帧 `artifacts/vid/p014.png` 定格证据），它一边转一边触发刷新重绘，「两周叠在一起乱闪」。
3. **`ACTION_CANCEL` 被当成正常松手**：父控件抢走手势时 `event.x` 归零，算出巨大负位移，
   直接判成切周 —— 内容停在半路，周次和内容对不上。

### 修法（`ScheduleFragment.kt`）

- **横向必须吃掉事件**：判为横向后 MOVE/UP 返回 `true`，ScrollView 再也不会自己滚。
- **DOWN 就 `requestDisallowInterceptTouchEvent(true)`** 占住手势；只有判定为**竖向**才交还，
  下拉刷新照旧（已实测：竖向拉动仍会出圆环并刷新）。
- 判定为横向时把 `swipeRefresh.isEnabled = false`，手势结束再恢复。
- **整个拖动期间只跟两张静态位图**：开始时拍「旧周」、临时渲染「新周」再拍一张、立刻恢复原周，
  然后把内容层子 View 全部 `INVISIBLE`，只留两张 `ImageView` 跟手；松手后清场再 `renderAll()`。
  渲染的时机被完全移出动画过程 —— 结构上不可能叠影。
- 过阈值判定只用最后一帧 MOVE 的 `pagerDx`（快速甩动时 UP 的 `event.x` 不可靠）。
- `ACTION_CANCEL` 一律回弹，绝不切周。

### 验证（本轮实测）

- 录屏拆帧：拖动中出现的是「左边第 9 周、右边第 10 周」的标准分页画面（`f2/f095.png`），
  没有重叠、没有残影；松手后画面归位干净（`f2/f097.png`）。
- 双向切换：9→10、10→9、9→10、10→11 连续成功；`uiautomator` 读到的周次与画面一致。
- 下拉刷新：竖向拉动仍会出现刷新圆环并完成刷新（`c0.png`）。
- 已知小瑕疵：刷新圆环的位置会盖住「第 N 周」胶囊（历史遗留，只在主动下拉时出现），
  要的话下轮用 `setProgressViewOffset` 往下挪一点。

## 35. 第三十五轮（2026-10-28）：课表切周重做 · 提醒选项改对话框 · 弹窗圆角与外壳统一（1.1.5 / versionCode 57）

用户原话：「第一，课表左滑右滑的效果非常诡异，拖影严重，也没有做到无极切换，参考 app 中的泳道设计修改课表的
设计。第二，上课提醒中，涉及到选择选项的情况，都参照成绩中学期选择中的对话框设计，将二级页面改为对话框形式。
第三，将 app 内的所有对话框的圆角都调大一些，参考我截图中屏幕的圆角，让圆角整体与屏幕协调。第四，将所有
对话框都统一成一份代码。」

### 35.1 课表切周：照泳道分页（HorizontalPager）的模型重写成两页跟手

上一轮「跟手」是错的：拖动时平移**整个内容层**，松手又让 `slideSwap` 在层内部再平移子 View ——
两套位移叠在一起，旧内容的幽灵快照跟着层一起走、新内容自己又滑入，于是「两张课表叠着抖」，
既拖影又不是 1:1。现在照 app 里真实的泳道分页（`MainActivity` 底栏的 `HorizontalPager`）建模：

- MOVE 过 touchSlop：把旧内容拍成快照塞进内容层（盖最上面），同时把目标周**真渲染**进内容层并挪到隔壁屏；
- 拖动中快照与内容**各自**跟手（快照 0→dx，内容 dx±屏宽），**层本身不动** → 没有叠影；
- 到头（第一周/最后一周）只做 0.35 阻尼，绝不渲染不存在的周；
- 松手位移过 20% 屏宽就顺势完成翻页（180ms，Decelerate），否则 200ms 回弹。
- 新逻辑集中在 `beginWeekDrag / applyWeekDrag / applyBoundaryDrag / endWeekDrag`；
  `switchWeek`（点箭头、跳周对话框）仍走原来的 `slideSwap`。

### 35.2 上课提醒：选项全部改成「学期选择」那种对话框

二级页整套删掉（`PickerPage` / `Page` / `OptionRow`），7 个值行改为弹 `showMiuixPicker` ——
就是成绩页「学期」、课表「跳转周次」用的同一个组件（标题 + 单选列 + 取消，选中行打勾）。
选项文案在组合作用域里先算成 `List<String>`（对话框只能在普通 lambda 里弹，那里不能调 `stringResource`）。

### 35.3 对话框圆角：跟屏幕物理圆角同量级

`ui/miuix/MiuixDialog.kt` 新增：
- `rememberDialogCornerDp()`：读 API 31+ 的 `WindowInsets.getRoundedCorner`（四角取最大），转 dp 后
  夹在 24~40，读不到就 **34dp**（原来是硬编码 26dp，大圆角屏看着「方」）；
- `MiuixDialogCard()`：**所有弹窗唯一的卡片外壳**，圆角只在这一个地方。

注意：`ViewCompat.getRootWindowInsets` 返回的是 `WindowInsetsCompat`，**没有** `getRoundedCorner`，
必须用平台的 `view.rootWindowInsets` 并加 SDK 判断（这一版编译报过 Unresolved reference）。

### 35.4 弹窗统一成一份代码

`MiuixDialogSpec` 新增 `content: (@Composable (dismiss) -> Unit)?`：给了它就渲染自定义内容，其余字段留空。
新增 `showMiuixContent(context) { dismiss -> ... }`。组织架构的成员卡（`MemberCardBody`）由此从
「页面里一张自制 `Dialog` + `Card`」改成走同一套弹窗 —— 全项目已经没有第二个 `Dialog(` 调用点。

**已知未完成**（诚实记账）：`showMiuixDialog` / `showMiuixInput` / `showMiuixProgress` 三个入口仍各自
复制了一份「Dim 动画 + ComposeView 宿主 + 窗口参数」的样板（约 120 行 ×3），只有卡片外壳和圆角统一了。
下一步应该抽成 `hostMiuixDialog(context) { dismiss -> ... }` 一个宿主函数，三个入口都调它。

### 35.5 本轮验证

- 课表：左滑 9→10、右滑 10→9→8、再左滑 8→9 均正常（`input swipe` 的注入位移只有命令值的 ~45%，
  所以右滑要用更长的行程才过 20% 阈值；真机手指行程更大）。
- 上课提醒：点「提前提醒时间」弹出对话框（标题 + 8 档 + 取消，45 分钟前打勾），
  截图 `artifacts/r35-advance-dlg.png`；圆角明显比 26dp 时更圆，与屏幕协调。
- **未验证**：成员卡弹窗没有在设备上打开过（组织架构要点 学院→部门→成员 三层，本轮超时），
  只保证编译通过 + 与其它弹窗共用外壳；下次顺手点开确认。

## 34. 第三十四轮（2026-10-28）：上课提醒改成 Nexio 的「一页开关 + 二级页选值」 · 课表周切换跟手 · 图片双击缩放（1.1.4 / versionCode 56）

用户原话：「第一，ui 交互设计太不美观了，原样复刻 nexio 课程表的交互设计即可，不要把所有选项都展示在第一页面，
参考 nexio 课程表的处理方式。第二，工作圈图片全屏展示时，双击放大，再次双击缩小。第三，课表周次切换时参考
nexio 课程表的周次课表切换效果，做到无极跟手切换，而非每次滑动一段时间后才切换。」

### 34.1 上课提醒：一页开关 + 当前值摘要，档位进二级页

上一轮把 8 档提前量、4 档时机、5 档时间、3 档勿扰**全摊在一页**，滚三四屏才到底 —— 这就是用户说的「不美观」。
现在照 Nexio 的层次重写 `ClassReminderScreen`：

- 第一页每一组只有**开关 + 一行「名字 … 当前值 ›」**（`ValueRow`）。看一眼就知道开没开、现在是多少。
- 点值行进二级页（`PickerPage`）：只有一个分组标题、一行「‹ 返回」、一列单选行，选完自动回第一页。
- 二级页**各自持有独立的 LazyListState**（`remember(page) { LazyListState() }`）：
  共用第一页的 state 会让二级页一进去就停在中段（第一版实测就是这个问题）。
- 覆盖：提前提醒时间 / 提醒时机 / 次日提醒时间 / 勿扰档位 / 岛左侧 / 课中岛右侧 / 缩略态精简。

### 34.2 课表周切换：跟手（`ScheduleFragment#installWeekSwipe`）

原来是「攒够 56dp 再一次性跳过去」：手指在屏幕上滑了一半什么都不会动，松手才突然切走。
现在手指一过 touchSlop，当前内容层就 1:1 跟着手指走；到头（第一周/最后一周）按 0.35 衰减，明确告诉用户「到头了」。
松手判据只看**松手时的位移**（`abs(dx) > 屏宽 20%` 且 `abs(dx) > abs(dy)*1.2`），
不再依赖 MOVE 里是否置过 `horizontal` —— 快速一甩系统常常只发一两个 MOVE 甚至不发，旧判据会漏。
过阈值：先按手势方向把手上这一层滑出（140ms），再切周（新内容从另一侧滑入）；没过：180ms 弹回原位。

**踩过的坑**：阈值一开始写 30% 屏宽，`input swipe` 实测只交付 ±285px（1208 宽的 23.6%），一直判不过；
改成 20% 后两向都通。真机手指滑距比注入事件大，但 20% 也更符合「随手一划就翻页」的手感。

### 34.3 工作圈图片：双击放大 / 再双击缩小

`moments/ZoomImageView` 原本有一条自研的双击判定（第一下挂 400ms 的「稍后关闭」，第二下取消关闭并切换缩放）。
问题是手上稍慢一点第二下就落到窗口外，被当成新的单击 → **直接退出查看器**，用户感知就是「双击没放大，反而关了」。
现在：

1. 双击窗口 400ms → **520ms**；
2. 额外接系统的 `onDoubleTap`（300ms 内必触发）作为第二条判定路径，两条路谁先到都算双击且都会 `cancelPendingClose`；
3. 系统 `onDoubleTap` 与自研 `onSingleTapUp` 路径互斥（GestureDetector 认定双击后不再回调 `onSingleTapUp`），不会双触发。

双击锚在指尖放大到 2.5x，缩小回 base 时锚点改视图中心（否则会飘）。

## 33. 第三十三轮（2026-10-28）：深色小组件进场「闪一下 + 第 N 周不明显」整类修复 · 上课提醒复刻 Nexio 全功能（1.1.3 / versionCode 55）

用户原话：「深色模式下通过我刚才的方式进入应用时，课表会闪动一下，同时第 n 周的标记会非常不明显，
在触发这个 bug 的基础上，彻查该 bug 引起的所有 ui 问题，彻底修复这些 ui  bug；第二，完全复刻 nexio
课程表课程提醒的所有功能，包括超级岛样式，提醒时间，勿扰档位以及超级岛布局等全部功能全部复刻到上课提醒的功能栏内。」

### 33.1 「闪动一下」的三个真实来源（都已就地修掉）

1. **顶栏标题首帧淡入**（`ui/shell/ShellBars.kt`）：`LaunchedEffect(spec.title)` 在**首次组合**时也会跑，
   于是进页面第一帧标题只有 25% 不透明度，再 220ms 亮回来 —— 肉眼看就是顶栏闪一下。
   现在用 `titleSeen` 记住「已经有过第一帧」，首帧直接 `snapTo(1f)`，只有标题真的被改写时才淡入。
2. **切回课表无条件重画整张网格**（`schedule/ScheduleFragment.kt#onTabSelected`）：
   原来每次都 `renderAll()`，那是 `removeAllViews()` + 重建全部课程块，中间必然有一帧是空的。
   现在先 `contentFilled()` 数一下当前内容层的子 View，**只有真的是 0 才重画**，否则原样复用。
3. **容器里残留的位移动画**（`ui/MainActivity.kt#applyPendingTab`）：进场前把 `fragmentContainer`
   每个子 View 的 `translationX/Y`、`alpha` 与进行中的 View 动画就地归零，避免新旧两帧错位叠在一起。

### 33.2 「第 N 周」不明显

`schedule/ScheduleHeader.kt` 的胶囊文字色改成 `accentColor(accent)`（深色自动提亮一档），
底色仍是同色 32%（深）/12%（亮）淡铺。原来深色下拿 primary 直接压在 32% 同色底上，亮度差不够，
看着像灰的 —— 这就是用户说的「非常不明显」。

### 33.3 上课提醒复刻 Nexio（`schedule/ClassReminderScreen.kt` 等）

新增并全部**真实接线**（没有任何占位开关）：

- **提前提醒时间**：0/5/10/15/20/30/**45/60** 分钟。
- **课中提醒 + 提醒时机**：全程 / 距下课 5·10·15 分钟开始；上课时刻与「下课-N」两条边界闹钟共用同一段逻辑
  （早于 showFrom 的那次直接返回，不重复弹）。
- **次日课程提醒 + 提醒时间**：每晚 19/20/21/22/23 点整，播报第二天第一节的课名/节次/地点（`ACTION_NEXT_DAY`）。
- **上课自动勿扰 + 三档**：静音（ALARMS）/ 勿扰（PRIORITY）/ 完全勿扰（NONE），走系统
  `setInterruptionFilter`，需要 `ACCESS_NOTIFICATION_POLICY`（只能用户手动授予，页面上给「去授权」入口）；
  上课前的档位存在 `lanhang_class_dnd` 里，下课时原样恢复，连堂课不会被覆盖成「勿扰」。
- **超级岛样式**：左侧内容（课程名称 / 上课地点）、课中岛右侧（距下课倒计时 / 不显示，倒计时到 `endMillis`）、
  息屏显示（`aodTitle` 开关）、缩略态精简（原始 / 精简地点 / 精简课程 / 两者，确定性规则：把
  「教学楼/综合楼/实验楼/图书馆/体育馆」等取首字、去「栋」「-」与空格，不调用任何模型）。
- **权限检查区**：通知 / 精确闹钟 / 勿扰 / 电池优化，各一行「已授权 / 未授权」并可点进对应系统页。

### 33.4 仍然不移植（保持诚实，不做假功能）

- 假日数据、分享卡片（本应用没有对应数据源）。
- Shizuku 提权路径（不做，勿扰走公开 API 已足够）。
- AI 精简（没有后端与模型 key，只保留确定性精简）。
- 教室标签（Nexio 的 `ClassroomLabelPrefs`，属课程备注功能，与本轮提醒无关）。

## 32. 第三十二轮（2026-10-28）：工作圈搜索展示整条动态 · 头像/蓝色姓名统一进主页 · 测试提醒立刻弹出 · 移植小米超级岛 · 顶栏单节点（1.1.2 / versionCode 54，已发版）

### 32.1 工作圈搜索结果 = 整条动态，不是一段文字

用户原话：「工作圈搜索功能应展示搜索出动态的全部内容，而非只展示动态中的文本部分」。

第三十一轮的首版搜索结果是「头像 + 姓名 + 4 行高亮摘要」，信息不全，看着像另一个 App 的列表。这一轮改成
**和动态流里那张卡同构**：40dp 头像、蓝色姓名 + 院系、右上时间、全文（不截断）、九宫格图片、赞/评论计数、
右下角蓝色「查看」。

- 版式来源：`moments/MomentSearchScreen.kt` 的 `SearchResultCard`（重写整个文件）。
- 九宫格复用 `moments/MomentsScreen.kt` 的 `MomentImageGrid` —— 因此把它的可见性从 `private` 提到 `internal`。
  **不要复制一份新的九宫格**：三张/四张/九张的排布规则（行数、末行宽度、圆角、间距）只该有一处。
  `onImage` 依旧透传到 `ImagePreviewActivity`，搜索结果里的图能直接全屏看。
- 点卡片本体 → `onOpenPost(item)`：清空 `searchQuery`、把 `focusFeedId` 设成这条的 `feed_id`、`closeSearch()`
  退回动态流，由既有 `focusFeedId` 机制滚到那条并闪一次背景。赞/评论/删除**只在动态流里做**，
  搜索页不摆假按钮（`赞 0`/`评论 0` 只是计数，和动态流里那张卡的计数显示一致）。
- 命中片段仍用 `MomentTextMatcher.hits` 铺主题色 28% 底；匹配口径（全角转半角、多词 AND、也匹配作者名）
  与「工作圈过滤」共用同一套函数，没有第二份实现。

### 32.2 头像与蓝色姓名 = 进个人主页（全应用统一）

用户原话：「点击动态发送者的头像与姓名也应该跳转到他的个人主页，以后所有涉及到头像以及蓝色姓名的场景都这样处理」。

这一轮把「可点」变成默认约定：

| 位置 | 状态 |
|---|---|
| 动态流卡片作者头像 / 姓名 | ✅ 第三十一轮已有 |
| 动态评论区头像 / 姓名、`@` 提及、回复对象 | ✅ 第三十一轮已有 |
| **工作圈搜索结果**头像 / 蓝色姓名 | ✅ 本轮新增（走 `onOpenProfile`） |
| 消息列表（赞/评论通知）头像 / 姓名 | ✅ 已有（`mine/MessagesScreen.kt` 的 `onOpenProfile`） |
| 私信会话列表与会话内头像 | 见 32.6 遗留 |

硬规则：**新写任何头像/姓名行，都必须接一个 `onOpenProfile: (String) -> Unit`**，
`LanhangAvatar` 与姓名 `Text` 各自 `.clickable`（整行不要包 clickable，否则卡片内的行点击会吃掉
图片/姓名自己的点击）。自己（`userId == Session.userId`）交给「我的」Tab，不压自己的主页。

### 32.3 「发一条测试提醒」立刻弹

用户原话：「上课提醒功能点击发一条测试提醒之后应立即弹出测试通知」。

旧实现是「排一个 60 秒后的闹钟 → 闹钟响 → 广播发通知」，点完 60 秒内毫无反馈，用户以为坏了。
现在 `ClassReminder.scheduleTest()` 直接调 `showTestNow()`：走和真实提醒**完全相同**的发布路径
（有岛进岛，没岛普通通知），内容换成「上课提醒（测试）」，不再经过闹钟。
`ACTION_TEST` 广播保留（外部可触发），耗时 0。

### 32.4 移植 Nexio 的小米超级岛（HyperOS 焦点通知）

新增 `schedule/ClassReminderIsland.kt`，移植 `HaoZai000/NexioSchedule` 的
`reminder/IslandNotificationHelper.kt` 中上课提醒真正用到的部分。机制（真机验证过的写法，不要「优化」）：

1. 一条普通 `Notification`（channel `class_reminder_island`）；
2. `notification.extras.putString("miui.focus.param", json)` —— JSON 描述卡片：
   `param_v2.business="course_reminder"`、`protocol=1`、`enableFloat`、`islandFirstFloat`、`updatable`、
   `sequence`（单调递增）、`aodTitle`、`baseInfo{type:2,title,content,…配色}`、
   `picInfo`、`hintInfo{type:2, timerInfo{timerType:-1 倒计时 / 0 正计时, timerWhen, timerTotal, timerSystemCurrent}}`、
   `param_island{islandProperty:1, islandTimeout:3600, bigIslandArea{templateNo:2, imageTextInfoLeft, sameWidthDigitInfo}, smallIslandArea{picInfo{pic:"miui.focus.pic_small"}}}`
3. `addExtras(Bundle{"miui.focus.pics": Bundle{miui.focus.pic_app_icon(_dark), miui.focus.pic_small(_dark): Icon}})` ——
   JSON 里 `"pic": "miui.focus.pic_small"` 是按这几个 key 去取图的；
4. 倒计时**不自己刷**，交给系统 Chronometer（`timerType=-1` + `timerWhen=上课时刻`）。

三个时间点：
- 课前（提前量到点）→ `notifyPreClass` 发 `ID_COUNTDOWN`；
- 上课那一刻 → 边界闹钟 `ACTION_CLASS_STARTED` → `notifyStarted` 发**另一个 id** `ID_STARTED`
  并撤掉倒计时那条（同 id 更新会把 Chronometer 卡在 00:00）；
- 下课那一刻 → `ACTION_CLASS_ENDED` → `cancelAll()` 收起。边界闹钟在课前那次发布时一起挂上
  （`RC_BOUNDARY_BASE=5300`，与 5000 槽位段、4500 小组件段不重叠）。

可用性判断：`ClassReminderIsland.isSupported()` 反射读 `persist.sys.feature.island`，
读不到＝不支持，`showSlot()` 自动回落 `ClassReminderNotify` 普通通知（**这是模拟器/非 HyperOS 机的常态**，
设置页会写「当前设备不支持超级岛，会使用普通通知」）。开关 `ClassReminderStore.islandEnabled` 默认开。

**没有移植**（并在设置页保持沉默，不摆空壳）：课中提醒、免打扰（需要通知策略权限）、教室标签、
假日数据、分享卡片、Shizuku 旁路。原因：它们要么依赖 Nexio 自己的账号/数据，要么需要额外系统权限，
在这个 App 里没有真实数据源，做出来就是假功能。

### 32.5 顶栏「叠加 / 全部异常」：单节点标题 + singleTask

两条改动：

1. `ui/shell/ShellBars.kt` 的标题原来是 `AnimatedContent(targetState = spec.title)` ——
   **同一时刻存在两份标题内容层**（进出各一份、互相叠着淡入淡出）。改成单个 `Text`，
   标题变化时只做一次 `Animatable` 颜色淡入（`snapTo(0.25f)` → `animateTo(1f)`，时长/曲线仍取
   `BarAnimation`）。物理上不可能再出现两个标题节点。
2. `MainActivity` 加 `android:launchMode="singleTask"`。小组件的 `PendingIntent` 是
   `getActivity(NEW_TASK|CLEAR_TOP|SINGLE_TOP)`，`standard` 模式下「任务里已有实例但不在栈顶/被
   `ImagePreviewActivity` 顶过」时仍可能**再建一个新实例**，两个实例各有一套 shell + 顶栏 → 叠加。
   `singleTask` 让任何入口（小组件 / 通知 / 分享 / 桌面图标）都必定复用同一个实例、只走 `onNewIntent`。

验证（模拟器，深色）：工作圈 → 点图片（`ImagePreviewActivity`）→ 返回 → 桌面 → 点小组件 →
顶栏只有一个「课表」、只有一个「第 9 周」胶囊、周课表只有一份。

**模拟器陷阱（务必先排除再怀疑代码）**：`debug.hwui.renderer` 默认 `skiagl` 时，这一路径会把
**整页**画成两份错位叠影（顶栏、周次胶囊、表头、课程块全部双份，且 uiautomator 里只有一份语义节点）。
`adb shell setprop debug.hwui.renderer skiavk` 后立刻干净 —— 即纯渲染器 bug。真机上看到的
「标题叠成一团」才是 32.5 那两条要修的。

### 32.6 遗留 / 未做

- 私信（`im/ImScreens.kt`）的头像与对话标题还没接 `onOpenProfile`（该文件是第三方 IM UI 移植，改动面大，
  下一轮单独做）。
- 超级岛只在真 HyperOS 上真正可见；模拟器上只能验「不支持时回落普通通知」+「测试通知立刻出现」。

## 31. 第二十九轮（2026-10-28）：顶栏叠加修复 · 工作圈搜索/关键词过滤 · 深色评论区阴影 · 长按复制与评论删除 · 上课提醒（1.1.1 / versionCode 53，已发版）

用户五条原话：「从桌面小组件进入后再点击别的 tab，会出现顶栏叠加到一起的情况」/「工作圈搜索、工作圈过滤功能，移植过来，其中工作圈过滤功能放到设置里，工作圈搜索在顶栏加一个搜索按钮。遵照 miuix 设计规范」/「深色模式下工作圈评论区的阴影丢失」/「给工作圈加一个长按文字自由复制的功能，将评论的删除功能做到评论的左下角，蓝色字体，点击后询问是否删除，可以参考工作圈帖子删除的实现逻辑」/「上课提醒功能，也一并放到设置中」。

### 31.1 顶栏叠加：不是两个栏，是一个栏的标题在打架

复现三次（含用户给的确切路径：工作圈 → 打开图片 → HOME → 小组件进课表）都**没能**在模拟器上截到双标题；8 秒后的截图与 3 秒后完全一致，说明早期读到的「双标题」是缩放伪影。真正机制是**标题写入竞态**：从小组件进来时 `applyPendingTab()` 把 Tab 切到课表，而各 Fragment 的 `onResume → syncToolbar()` 同时往 `shell.title` 写自己那一页的标题，`AnimatedContent` 的目标在一两帧内翻转，两段标题动画叠在一起。两条确定性修法（都对，都要留）：

- `ShellBars.kt` 标题切换改成**顺序淡出→淡入**（`fadeOut` 先、`fadeIn` 延后半个 `BarAnimation.DURATION_MS`），任何时刻最多只有一个标题可见；
- `MainActivity` 暴露 `val activeTab: Int`，`MomentsFragment` / `MineFragment` / `WorkFragment` 的 `syncToolbar()` 开头加「不是我这个 Tab 就直接 return」。切 Tab 时只会有一个 Fragment 在写标题。

### 31.2 顶栏动作支持图标（搜索按钮的落点）

`ShellState` 加 `actionIcon: ImageVector?`，与 `actionText` **互斥**（图标优先）。`showToolbarMain(title, actionIcon, onAction)` 收成单个函数（不要再加重载：默认参数会让单参调用变成歧义）。图标只在 `else if (label == null)` 分支画，避免搜索图标和「发表」抢位置。图标来自 `top.yukonga.miuix.kmp.icon.extended.Search`（**注意包名是 `icon`（单数）+ `extended` 子包**，写成 `icons.MiuixIcons.Search` 编不过）。

### 31.3 工作圈搜索（本地真实搜索，不假装全量）

`moments/MomentSearchScreen.kt` + `MomentTextMatcher`（全角/半角归一 + 位置映射，高亮用 `SpanStyle(background = primary.copy(alpha = .28f))`）。入口：工作圈顶栏右上角放大镜 → `MomentPage.SEARCH`（`MiuiNavStack` 推入，返回箭头/返回键都能退，`backCallback` 里也要认这个页）。**只搜已加载的帖子**，页面底部固定写出「只在已加载的 N 条动态里搜索」，不做假的「全站搜索」。

### 31.4 工作圈过滤（设置 → 工作圈 → 工作圈过滤）

`moments/MomentsFilterStore.kt`：4 个预设档（广告推广 / 兼职招聘 / 二手交易 / 房屋租赁）+ 最多 30 条自定义规则，一条规则内空格分隔多词 = 同时包含，命中即在本机隐藏。`MomentsFragment` 里 `feedVisible = items.filter { match(...) == null }`，列表底部给一行「已按关键词过滤 N 条动态」，免得用户以为动态丢了。

- **总开关默认关闭**（第一版默认开、装上就少内容，属于「像 bug 的功能」，本轮已改）；预设只是预先勾好，用户打开开关立刻生效。
- 自己发的动态、寻物/招领（`PROTECTED` 词表）永不隐藏。
- 过滤只影响本机显示，不碰服务端。

### 31.5 深色模式评论区阴影 / 长按复制 / 评论删除

- 深色下阴影会被深底吃掉，所以深色改成「深色投影 + 1dp 白色 7% 描边」，浅色保留原来的 4dp 黑色 10% 投影；
- 帖子正文与评论正文包 `SelectionContainer`，长按即系统选中菜单，可自由复制（不再只有整段复制）；
- 评论删除从「长按评论菜单」挪到**评论左下角蓝色小字**，仅自己的评论显示（`uid == Session.userId`），点击走和删帖同一套确认对话框。

### 31.6 上课提醒（设置 → 通用 → 上课提醒）

移植上游四个文件并适配本项目：`ClassReminderStore`（开关 + 提前量 0/5/10/15/20/30，默认 15）、`ClassReminderPlan`（纯逻辑：窗口 7 天、按节次聚合、学期边界外不排、`triggerAt > now` 才收，错过不补发）、`ClassReminder`（AlarmManager 排 `5000+序号` 的 requestCode 区间，每次重排全量对账，不写台账文件；每天 00:05 一个 `ACTION_RESYNC` 兜底）、`ClassReminderScreen`。

- 课表数据直接复用小组件的 `ScheduleWidgetData.load(date)`：它已经处理周次换算、单双周、连堂合并，另写一份必然两边不一致。
- 清单加 `SCHEDULE_EXACT_ALARM`（没授权时退回 `setAndAllowWhileIdle`，只晚几分钟，页面上明说）、`ClassReminderReceiver` 订阅 BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED —— **应用更新后不重排就会静默失效**，这条是必需的。
- 「下一次提醒」**即使开关关着也照常算并显示**（第一版关着返回 null，页面显示「最近 7 天没有课」，明明有课却说过没有，是明确的错误信息，本轮已改）。

### 31.7 本轮验证（模拟器 emulator-5554，1220×2656，第 9 周）

| 项 | 结果 |
| --- | --- |
| 构建 | `assembleRelease` 成功（三次，含两处修正） |
| 工作圈顶栏搜索图标 | ✅ 右上一颗放大镜，标题不再被挤 |
| 搜索页 | ✅ 输 `28` 命中 1 条并高亮「28」；输 `16` 显示「没有匹配的动态」+ 范围说明 |
| 设置页两个新入口 | ✅「上课提醒 / 已关闭」「工作圈过滤 / 已关闭」 |
| 工作圈过滤页 | ✅ 开关 / 4 预设 / 自定义输入 / 0·30 计数 / 页脚全部正常 |
| 上课提醒页 | ✅ 提前量 6 档选中态正确；「下一次提醒」= 焊接技术与工程导论 10月28日 08:00 第 1 节 |
| 闹钟真的落地 | ✅ `dumpsys alarm` 见 `CLASS_REMINDER_ALARM` 07:45 / 09:45 与 `ACTION_RESYNC` 00:05；关掉开关后再查，class 闹钟全清 |
| 顶栏叠加 | ⚠️ 三次复现均未出现（含用户确切路径）；已按机制修掉，等用户实测确认 |
| 深色阴影 / 长按复制 / 蓝色删除 | ⚠️ 代码已改、编译通过，未做逐项截图（当轮时间预算已用尽） |

已知小遗留：关掉提醒开关后 `ACTION_RESYNC` 那条每日闹钟仍留着（无害，下次启动 `rearm` 会清）。工作 Tab 的常用功能横条仍未做视觉检查。

### 31.8 本轮没做的事

用户随后明确要求「push 到 github 并发布 release」，已按既有流程推 `main` + 打 `v1.1.1` 标签触发 Actions 出包。语音通话 / 视频通话继续搁置。

## 30. 第二十七 / 二十八轮（2026-10-27）：校园网自助服务合并 · 小组件进课表 · 关于页致谢 · 课程配色全局唯一（未发版，versionCode 仍 52）

用户原话（三轮连着说的，一起做）：

> 「可以将校园网一键认证功能去掉了，校园网登录已经取代了这个功能。另外，将校园网账号设置合入
> 校园网登录-网络认证自助服务区域，点击这个功能则跳到原来的校园网账号设置页面。另外有个bug，
> 如果我提前切换到非课表tab（如工作、工作圈或我的）然后回到桌面点击小组件进入程序，顶栏不会
> 换为课表，仍然会显示工作、工作圈或我的。另外，把关于中原项目lanhanglite移到致谢一栏中。」

> 「扩展课表中课程选色的多样性，保证可读性的同时也要保证不要有课程的颜色出现重复的情况，
> 并且要保证课程颜色与小部件中课程的颜色同步，比如我的高数B和航空航天概论就出现了颜色重复
> 的情况，不能出现颜色的重复」

### 30.1 校园网：一键认证删掉，账号设置并进「网络认证自助服务」

- `work/WorkCatalog.kt`：删掉 `校园网一键认证`（原生「校园网登录」已完全取代它）**和**
  `校园网账号设置`（搬去校园网登录页）。51 → **49** 条，`网络服务` 组从 6 项变 4 项。
- `campusnet/CampusNetScreen.kt`：这一块现在只有**一行**「校园网账号设置」（`SettingsLinkRow`），
  跳转原来工作页那个应用（`ACCOUNT_SETTINGS_URL = http://Go.nchu.edu.cn/?id=7`，
  `ACCOUNT_SETTINGS_APP_ID = i7FIaf9wQXotusT8MjxrBs`），仍走 `OaWebViewActivity.open(..., appId = ...)`，
  网关换取 token 的行为和以前完全一样。
  **演进记录**：27 轮先做成「自助服务 + 账号设置」两行卡；用户随即说
  「直接删除网络认证自助服务这个功能就行，只保留校园网账号设置」——28 轮把自助服务那行和
  本地常量 `SELF_SERVICE_URL` 一起删掉（`SrunApi.SELF_SERVICE_URL` 保留，认证本身还要用），
  行组件改名 `SettingsLinkRow`，底部提示语也从「自助服务需在校园网内使用」改成
  「校园网账号设置需在校园网内使用」。
- 新增字符串 `campus_net_account_title` / `campus_net_account_subtitle`（中/英各一份）；
  `campus_net_self_service*` 两条字符串现在没有引用（留着，认证页将来若要挂快捷入口还能用）。

**踩过的坑（务必记住）**：第一版用「可跨块的非贪婪正则」去删某个 `WorkApp(...)` 条目 ——
`WorkApp\(\n(?:\s+[^\n]*\n)*?\s+name = "校园网一键认证",...` —— 正则从**文件里第一个** `WorkApp(`
开始吞，一路吃到目标条目，把「教学办公 / 校园服务」整两组的条目全删了，结果工作页
「常用功能」里的查用电 / 缴电费 / 智慧教务全消失（用户当场发现）。
**教训：整块删除 JSON 风格条目不要用能跨块的正则；直接从 `artifacts/apps.json` 重新生成整张表。**

### 30.2 「从桌面小组件进来，顶栏还是旧 Tab」（bug）

现象：切到「工作」→ 回桌面 → 点小组件进 App：页面和底栏都是课表，**顶栏却写着「工作」**。
复现截图 `artifacts/r29-bug-tab-before.png`（修复后 `artifacts/r29-tab-fix.png`）。

原因：`ScheduleWidgetUpdater.clickPendingIntent()` 的 intent 本身带对了
`EXTRA_OPEN_SCHEDULE`，问题在于**执行时机** —— `onNewIntent` 回调时窗口还停着
（`onRestart/onStart` 之前），此时 commit 的 Fragment 事务 + `startPagerSwap` 的顶栏/滑页动画
会半途卡住，于是内容切了、标题没跟上。

修法（`ui/MainActivity.kt`）：

- `handleWidgetIntent()` 只把目标记到 `pendingTab`，并**立刻 `removeExtra`**（否则主题 recreate
  会再执行一遍）；
- 新增 `applyPendingTab()`：三个时机各试一次 —— `onCreate`（冷启动）、`onResume`（窗口将显示）、
  `onNewIntent` 里 `binding.root.post { }`（App 在前台时不会再来 onResume）；
- 执行时临时把 `restoring = true`，即「从桌面跳进来」不做左右滑动（也不给卡半截动画留机会），
  切完再 `applyTitle(shell.tab)` 对齐一次标题 —— 这一条是兜底，杜绝「页面课表 / 顶栏工作」。

### 30.3 关于页：「原项目 lanhang-lite」进「致谢」

`mine/MineScreen.kt`：`mine_credits_upstream` 那一行从「开源」卡搬到「致谢」卡首行
（`aboutStack` 里补 `LocalContext`，因为点击要 `openUrl`）。「开源」卡现在只剩「开源地址」。

### 30.4 课程配色：全学期均分色相，课表与小组件共表（`schedule/CourseColors.kt`，新增）

老代码两条路各算各的：

| 位置 | 老算法 | 毛病 |
| --- | --- | --- |
| 课表 `ScheduleFragment.courseColor` | 固定 11 色板 + 「离已用色最远」贪心 | 课一多色板用完，只能从用过的色里再挑 → 高数B1 与 航空航天概论 同一种绿 |
| 小组件 `courseHue` | `hashCode * 137.508` | 和课表**根本不是一种颜色** |
| 小组件 `distinctHues` | 同屏色相差 < 42° 就转开 47° | 「按屏去重」把颜色改掉 → 桌面与课表不同色 |

现在统一到 `CourseColors`：`ScheduleRepository.allCachedCourseNames()` 扫一遍已缓存的
`schedule_w{N}.json`（周次范围取自 `schedule_cache.json` 的 `起始周次/结束周次`），课程名排序后
**均分 360°**（每门间隔 360/n，起点偏 8° 避开正红）。于是：

- 不同课必然不同色（十几门课 → 间隔 20° 以上，严格不重复）；
- 同课必然同色（纯函数，与出现顺序/周次/只画某一天无关）；
- 课表和小部件取的都是 `CourseColors.hueOf(name)`，**同一色相**，再各自派生：
  课表走 `ScheduleStyle.courseFill/onCourseTint`（同色相染色底 + 深字），
  小组件走 `tintOf/inkOf`（对比度 ≥ 4.5）—— 可读性仍由各自负责。
- 小组件 `distinctHues()` 现在**只取表值、不再转色**（否则同屏转色就破坏同步）。
- `ScheduleRepository.refresh()` 写缓存后调 `CourseColors.invalidate()`，加退课/转专业后下次取色重建；
  取不到课程表（缓存全空）时才退回老的 hash 黄金角兜底。

验证：`artifacts/r30-schedule-colors.png`（课表，高数B1 酒红 / 航空航天概论 绿 / 焊接 蓝紫 /
美育 橙棕 / 英语 绿 / 思政 蓝 / 体育 橄榄 / 计算机 青绿 / 形势 青 / 职业生涯 紫，无重复）、
`artifacts/r30-widget-colors3.png`（4×2 小组件）。逐像素取样核对同色相：
形势与政策1 课表 hue 187.9 / 小组件色条 hue 188.1（同步）；计算机那一行因为「正在上」卡片底色
是 primary 蓝，色条取样会被掺色，属正常。

### 30.5 本轮验证清单

| 项 | 图 |
| --- | --- |
| 顶栏 bug 复现 | `artifacts/r29-bug-tab-before.png` |
| 顶栏 bug 已修（点小组件进来顶栏=课表） | `artifacts/r29-tab-fix.png` |
| 校园网登录 → 自助服务两行 | `artifacts/r29-campusnet-selfservice.png` |
| 工作页（网络服务已无「一键认证 / 账号设置」） | `artifacts/r29-work-common.png` |
| 关于页（致谢首行=原项目） | 见 30.3，uiautomator 文本：开源→开源地址；致谢→原项目 lanhang-lite / Miuix / Coil / OkHttp |
| 课表配色不重复 | `artifacts/r30-schedule-colors.png` |
| 小组件与课表同色 | `artifacts/r30-widget-colors3.png` |

> 强制刷新桌面小组件（调试用，`exported=true` 的自定义 action 可以 shell 直接打）：
> `adb shell am broadcast -a com.lanhang.miuix.widget.ACTION_TICK -n com.lanhang.miuix/.widget.ScheduleWidgetProvider`
> —— `APPWIDGET_UPDATE` 那种受保护广播从 shell 打不出去（SecurityException），ACTION_TICK 可以。

---

## 29. 第二十六轮（2026-10-27）：工作页功能清单本地化 + 4×2 小组件「课上完翻到明天」（未发版，versionCode 仍 52）

用户原话：

> 「将工作页面的功能区本地化，不要联网获取工作中的功能列表，如果今日课程上完，
> 那就在 4*2 小组件上显示明天的课表」

### 29.1 「工作」页功能清单本地化

- 新增 `work/WorkCatalog.kt`：把 `POST /apps/all` 的返回**固化成本地表**（2026-10-27 抓取快照，
  `id` / `home_page_url` 原样保留），结构就是 `List<Pair<String?, List<WorkApp>>>`，
  和 `WorkDirectory.sections()` 同型，`WorkPage` 一行都不用改布局。
- `WorkPage` 的 `LaunchedEffect` 不再发请求：
  ```kotlin
  sections = WorkCatalog.sections      // 本地清单
  portal = Session.workUrl             // 门户地址只读登录时缓存的配置
  loading = false
  ```
  同时把 `loading` 初值改成 `false`（否则会闪一帧转圈）。
  `/configuration/all` 那次请求也一起去掉了 —— 它同样是首屏转圈的原因之一。
- **只本地化「列表」，不本地化「功能」**：点开某个应用仍走原来的网关预检 + WebView 链路，
  需要网络就有网络请求，不需要的（校园网登录、图书馆座位预约）本来就是原生页。
- 刻意不收「最近使用」（服务端按你最近点过的动态生成，写死会永远同一批，反而误导；
  顶部「常用功能」已经承担该角色）和「系统应用」（整组只有无效的「通知服务」）。
- 分类沿用：教学办公 / 校园服务 / 网上办事 / 网络服务 / 图书馆，共 51 项。
  学校新增应用时改这一张表即可（`Order` 用数组顺序表达）。

### 29.2 4×2「今日安排」：今天的课上完就翻到明天

- `ScheduleWidgetData.wideTarget(now)` 决定「这块 4×2 该画哪天」：
  - 今天还有没上完的课 → `WideTarget(today, todaySnapshot, tomorrow = false)`（原行为，含倒计时）；
  - 今天课上完了、或今天本来没课 → `load(today.plusDays(1))` 单独取明天快照，
    `tomorrow = true`；明天也没课才退回原来的「今天的课已上完」提示。
- `renderWide(..., tomorrow = false)` → `WideRenderer`：表头在
  `widget_wide_today_format`（今天 / %s）和新增的 `widget_wide_tomorrow_format`（明天 / %s）之间切；
  `filterByClock` 天然为 false（`target != now.toLocalDate()`），所以明天不按钟点淘汰课程。
- 两处调用都走 `wideTarget()`：真实刷新（`updateWidget` 的 WIDE 分支）和选择器里的静态预览
  （`pushGeneratedPreview`），避免「预览是今天、放上去变明天」的错位。

### 29.3 验证记录（模拟器 emulator-5554，1.1.0 覆盖安装）

- **工作页断网**：`svc wifi disable` + `svc data disable` → 强杀 → 冷启动 → 工作页
  **立刻**出完整清单（常用功能 5 项 + 打开办事大厅 + 搜索 + 教学办公宫格），
  和联网时一模一样（`artifacts/r28-work-offline.png`）。这就是本地化的目的：断网也能进去点原生功能。
- **4×2 上课中（真机）**：设备时钟走到 14:00–15:40（GMT）正好是「大学计算机基础训练」，
  组件的行 1 变成浅蓝调卡 + 右侧粗蓝字「77 分钟结束」，行 2「形势与政策1」仍是纯白卡
  （`artifacts/r28-widget-after-launch.png`）—— 上一轮没等到的那张图，这一轮碰上了。
- **4×2 明天**：临时把 `leftToday > 0` 改成 `leftToday > 999` 强制走明天分支，编译安装后
  组件显示「明天 / 周三」+ 焊接技术与工程导论 08:00–09:40 `第 1-2 节 | C402 | 刘冠鹏` +
  高等数学B1 10:00–11:40 `第 3-4 节 | E302 | 舒琳馨`，无倒计时（`artifacts/r28-widget-tomorrow-test.png`）。
  **验完立刻改回并重新编译安装**（发布包不含这个测试改动，`if (leftToday > 0)` 已确认复原）。
- 设备收尾：深色主题、首页 4×2 一件、网络已恢复。**本轮没有 push、没有 release**（用户没说要推）。

### 29.4 本轮踩到的坑

1. **重装/重启后桌面留一张灰色占位卡**（app 图标居中、没有内容），logcat 是
   `AppWidgetHostView: Error inflating RemoteViews / Resources$NotFoundException: Resource ID #0x0` ——
   这是 launcher 缓存的旧 `RemoteViews` 失效，**不是渲染器出错、也不是布局被删**。
   对付它：正常启动一次 App（会把新位图重新推给所有实例）即可，不需要删掉重放组件。
2. 设备时钟是 **GMT**（比北京慢 8 小时），所以「北京 22:1x」在设备上正好是「14:1x」——
   判断某节是不是「上课中」必须按**设备时间**算，别按本地时间想当然。
3. 想要某个时间窗口的截图，**别干等**（见开头「执行纪律」第 2 条）：把条件临时改成恒真、
   截完立刻改回，是这里最省时间的做法（一轮 ~4 分钟，含两次编译）。

---

## 28. 第二十五轮（2026-10-27）：组件「上课中/上课前」两态、图片双击缩放与长按保存、断网重试、消息只提示自己的帖子、设置与关于整理（未发版，versionCode 仍 52）

用户原话（四件事）：

> 「第一张图是上课中，第二张图是上课前，继续做小组件……另外，工作圈中点击图片放大至全屏后，
> 双击放大图片，再次双击将图片缩小至原比例，长按弹出是否保存图片弹窗。第二，如果联网失败导致
> 无法加载出界面，应该显示小文字为加载失败，点击重新加载。点击后尝试重新加载界面。第三，消息栏
> 中只提示与自己帖子有关的消息……第四，整理一下设置与关于界面的选项，大小不统一，设置里面不应该
> 有跳转到关于的选项……参考 nexio 课程表的关于页面做一下你的关于页面」

### 28.1 4×2「今日安排」：上课中 / 上课前 两态（设计稿两图）

设计稿判读：**上课中** = 那一行换成浅蓝调的行卡 + 右侧粗蓝字「98分钟结束」，左侧课程色条保留原色；
**上课前** = 纯白卡片、无倒计时、无色条变化。所以「现在」这件事**只落在行卡底色 + 倒计时文字上**，
不去改课程色条（改了就等于「现在」压过「哪门课」，两行会认不出彼此）。

- `minutesLeft(course): Int?` —— 只在 `startMin <= nowMinute < endMin` 时返回
  `(endMin*60 - nowSecond + 59) / 60`（**向上取整**：20:39:59 显示「1 分钟结束」而不是「0」）。
- `activeSlotColor()` = `blendARGB(slotColor, palette.primary, if (isDark) 0.16f else 0.10f)`，
  绘行卡时只有 `minutesLeft != null` 才用它；色条仍走 `accentOf(hue, palette)`。
- 倒计时文案 `widget_wide_minutes_left`（「%1$d分钟结束」/「%1$d min left」），右对齐、`palette.primary`、
  BOLD、`(rowH*0.25f).coerceIn(dp(9f), dp(12.5f))` —— 跟设计稿一样是「比课名小一号但更亮」。
- 上课前 / 下课后：整行回到 `Palette.background`（深色是其 7% 白混色），不画倒计时。
- 颜色仍然全部来自 `Palette`（§26.2 第 1 条）；**不做「饱和填充 + 白字」**（§26.2 第 2 条）。

### 28.2 工作圈图片预览：双击缩放 + 长按保存

- `moments/ZoomImageView.kt`：**自己接管手势**（`detectTapGestures` 的 `onTap`/`onLongPress` 改成
  公开字段，由 Activity 挂），`isZoomed = drawableReady && curScale > baseScale*1.01f`。
- 双击：`toggleZoom()` 在 `baseScale` ↔ `baseScale * DOUBLE_TAP_SCALE(2.5f)` 之间切；`MAX_SCALE = 8f`。
- **单点关闭必须等 400ms**：系统双击窗口是 300ms，而 adb 每次 `input tap` 都要新起一个 JVM，
  两条命令必然超过 300ms —— 于是「双击」看起来像「两次单击 → 两次关闭」。现在 `onSingleTapUp`
  先 `cancelPendingClose()` 再判断：未放大就把关闭动作 `postDelayed(DOUBLE_TAP_WINDOW_MS)`，
  第二击会把它取消；已放大则立即关闭（放大态下单击=退出，符合微信/小红书心智）。
- 长按 → `askSaveImage(url)` → Miuix 确认弹窗（`moments_save_image_title/_summary/_confirm`）→ Coil 取
  `SuccessResult` → `toBitmap()` → MediaStore 写入 `Pictures/蓝航Lite/lanhang_<millis>.jpg`（JPEG q95）。
  写库失败/无权限分别提示 `moments_save_image_failed` / `_denied`，成功 `_done`。
- 清单加了 `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion="28"`，29+ 走 MediaStore 不需要）。

### 28.3 断网：一片空白 → 「加载失败 / 点击重新加载」

- 新组件 `ui/miuix/LoadFailedHint.kt`：两行小字（`footnote1` 「加载失败」+ `footnote2` 主色「重新加载」），
  整块可点（圆角 16dp 的水波纹），**没有图标、没有大标题** —— 用户要的是「小文字」。
- 三处接上：消息（`MessagesScreen`，error item 直接换成它）、工作圈（`MomentsScreen` 的 `loadFailed` 分支）、
  工作圈的「有表头」分支（个人主页/相册）。
- **白屏根因（本轮真 bug，已修）**：`MomentsScreen` 里「容器」的条件原来是
  `header != null || items.isNotEmpty() -> LazyColumn(...)`，而「转圈 / 空态 / 加载失败」三个 item
  全挂在这个 LazyColumn 里面。于是「没有表头 + 一条数据都没有」连容器都不组合 → 整页纯黑，
  断网冷启动正好落这一格：用户永远等不到「加载失败」。现在条件是 `else`，容器无条件存在。
- 网络层：`ApiClient` 加 `callTimeout(25s)`，`connect` 20s→8s、读/写 30s→20s。
  以前只有 connect/read，DNS 失败 + 重试能把一次请求拖到一分钟以上，界面一直「加载中」。
- `MomentsFragment.load()`：`catch (CancellationException) { throw }`（切 Tab 不算网络失败）、
  `catch (Throwable)` 兜底、`loading/refreshing` 放进 `finally`。只接 `Exception` 的话，
  任何 Error 都会让协程直接死掉，那两行复位永远不跑。
- 首次加载态补一行小字「加载中…」（`moments_loading`），只有转圈会像卡死。

### 28.4 消息栏只提示「与自己帖子有关」的互动

- 新 `moments/InteractionFilter.kt`：`/message/history` 会把**你参与过的任何动态**的互动都推给你
  （点赞/评论别人的动态，别人再评论，也会来），所以要用所有权过滤：
  - `feedsOf(raw)` → `MomentsApi.getFeeds(ids)`（新增接口）拿每条动态的 `user_id`；
  - `keepMine(raw, feeds)` 只留 `feed.user_id == Session.userId` 的互动；
  - `unreadCountMine(unread)` 角标走同一条规则（否则角标数 ≠ 列表条数，用户会以为丢消息）。
  - **拿不到动态时必须不过滤**（宁可多提示，也不要把消息吞掉）。
- 消息页同时加了「全部 / 评论 / 赞」三个筛选 chip + 右上「刷新」，列表项分「评论」和「赞」两种排版。
- 验证：列表 5 条全部是我自己那条「身份证/学生证在二食堂与逸夫楼附近」的赞与评论（`artifacts/r27-msg-skiavk.png`）。

### 28.5 设置 / 关于：统一尺寸，关于移出设置

- 设置里每行统一 `SettingRow(title, summary = null, value = null, onClick = null)`
  （`pressableRow(corner = 16.dp)`、`padding(horizontal = 16.dp, vertical = 15.dp)`、右侧统一 `ChevronForward`）。
  以前的毛病是「有箭头的一行、没箭头的值行、带副标题的一行」三种高度混排。
- **删掉「支持」整栏**（含跳转「关于」那一条）：关于现在只能在「我的」页点开，设置里不再出现。
- 「关于」按 nexio 课程表那套重做：`aboutHero`（图标 + 蓝航Lite + 版本 + tagline）→
  `aboutApp`（应用版本 / 接口标识 / 当前账号）→ `aboutDiagnostics`（诊断与日志）→
  `aboutOpenSource`（开源地址 / 原项目）→ `aboutStack`（致谢：Miuix / Coil / OkHttp）→ `footer`。
  分组标题用 `SmallTitle`，卡片 = 一张白卡包一组，行与行之间只有 0.5dp 分隔线。
- 浅色 / 深色都截图确认：`artifacts/r27-settings-light.png`、`r27-about-light.png`、
  `r27-settings-dark.png`、`r27-about-dark.png`。

### 28.6 本轮踩到的坑（务必记住）

1. **模拟器 skiaGL 下「大标题」被画成一团白拖影**（消息页、空工作圈页；同一页面 uiautomator 里文字
   完全正常）。不是代码问题：`adb shell setprop debug.hwui.renderer skiavk` 后立刻干净。
   以后遇到「某个页面标题糊成一片」先换渲染后端复验，别去改 `ShellBars`。
2. **放小部件最稳的手法**：选择器里展开分组后 `adb shell "input draganddrop <预览中心> <落点> 2000"`
   （`input swipe` 会被列表当成滚动）。落点选首页空白区（约 611,700）。
3. `adb root` 在 production build 上不可用（`adbd cannot run as root in production builds`），
   `date` 改不了系统时间 —— 想验证「上课中」那一路，只能等设备时间真的落在某一节课里。
   注意模拟器时钟是 **GMT**（比北京慢 8 小时），课表按设备时间算。
4. `Select-String -LiteralPath` 传不存在的路径会以退出码 1 结束；`-Pattern 'a|b'` 加引号会报位置参数错，
   用逗号分隔的裸模式。

### 28.7 验证记录

- 消息：断网 → 「加载失败 / 重新加载」（`r27-retry-offline.png`）；恢复网络后点它 → 列表回来
  （`r27-retry-online.png`）。
- 工作圈：断网冷启动 → 4 秒内出「加载失败 / 重新加载」（`r27-moments-off4s.png`，修白屏前是纯黑）；
  恢复网络点重试 → 正常出动态流（`r27-moments-retry-ok.png`）。
- 图片预览：双击进 2.5×（`r27-viewer-zoom.png`）、再双击回原比（`r27-zoom-out2.png`）、
  长按弹「保存图片」（`r27-save-dialog.png`）、保存后 toast（`r27-save-toast.png`）、相册里能看到
  （`r27-album2.png`）。
- 小部件：4×2 已放上首页（`r27-widget-clock.png`，两行真实数据、两种课程色）。
  **「上课中」浅蓝调 + 倒计时这一态本轮没能真机截图**（见 28.6 第 3 条，等课上再说）。
- 设备收尾：深色主题、首页 4×2 一件。**本轮没有 push、没有 release**（用户没说要推）。

---

## 27. 第二十四轮（2026-10-27）：新增 4×2「今日安排」小组件（未发版，versionCode 仍 52）

用户原话：「参考这个组件做一个4*2的小组件」+ 一张设计参考图（灰容器里两行白卡：左两行时刻、一根紫色课程条、
课名 Demibold、「第 9-10 节 | 明德楼A栋-A200 | 张中华」副信息，第二行是空槽）。

### 27.1 架构：三变体一套数据一套渲染

- 新增 `widget/WidgetVariant.kt`：`enum class WidgetVariant { WEEK, TODAY, WIDE }`。
  **原来的 `Boolean isSmall` 全部删除**——尺寸一多，布尔量必然变成一堆 if 嵌套。
  - `ScheduleWidgetProvider.variant = WEEK`（4×4）
  - `ScheduleWidgetSmallProvider.variant = TODAY`（2×2）
  - `ScheduleWidgetWideProvider.variant = WIDE`（4×2，本轮新增）
- `ScheduleWidgetUpdater`：`allVariants: List<Pair<Class<*>, WidgetVariant>>`；
  `variantOf(manager, id)` 按 `getAppWidgetInfo()?.provider?.className` 反查变体（查不到退回 WEEK）。
  `updateAll` / `pushGeneratedPreview` / `updateWidget` 全部按变体分叉，**数据加载、午夜闹钟、点击跳转、
  `TodayNavStore` 隔离逻辑一律不动**。
- 渲染入口新增 `ScheduleWidgetRenderer.renderWide(appContext, snapshot, widthDp, heightDp, targetDate, now)`，
  内部类 `WideRenderer` 与 `TodayRenderer` 同级（都建在 object 内部，才能用 private 的 `Palette` / `fitText`）。

### 27.2 视觉规格（对齐设计稿，颜色只用 Palette）

- 容器 = 灰调，每行 = 卡：
  - 浅色：容器 `Palette.header`（#F0F0F0）、行卡 `Palette.background`（#FFFFFF）；
  - 深色：容器 `Palette.background`（#242424）、行卡 = 容器混 7% 白（`blendARGB(background, white, 0.07)`）。
  - **两个主题都保持「卡片浮在容器上」的层次**；深色别照抄浅色的「容器更亮」，那样行卡会变成凹陷的洞。
- 表头：左「今天 / 周二」（`widget_wide_today_format`）、右「第 N 周」（`widget_week_format`），
  `onSurfaceVariant` + 12dp + DEFAULT 字重（「次」层级，不与课名抢层级）。
- 每行（内容安全区 9dp，行距 5dp，圆角 10dp）：
  - 左：两行时刻 `HH:mm`（上课 / 下课），右对齐，`onSurfaceVariant`；
  - 中：课程色条 3.5dp 宽、圆角、上下各留 `min(9dp, 16% 行高)`，颜色 = `accentOf(hue)`（HSL 0.55/0.44 浅、0.62/0.64 深）；
    **正在上的那节换成 `Palette.primary`**（不写倒计时文字，设计稿里没有）；
  - 右：课名 BOLD + 「第 N-M 节 | 教室 | 教师」，缺哪段就不拼哪段（`metaOf`）。
  - 行高 < 32dp 时退化成单行（时刻合成 `08:00-09:40`、副信息不画），防止用户把 4×2 压扁后糊成一团。
- 空槽照样画一张行卡（设计稿就是这样），不写「今天没有课」；真没课时才走 `drawCenterText` 空态。

### 27.3 数据层：`CourseBlock` 新增 `teacher`

- `ScheduleWidgetData.CourseBlock` 加 `val teacher: String = ""`（默认值，避免动所有构造点）；
  由 `ScheduleModels.Course.teacher` 取值；`merge()` 的合并键从「课名 + 教室 + 连续 + 不跨午休」改成
  「课名 + 教室 + **教师** + 连续 + 不跨午休」，否则同一门课换老师会被误并。
- 4×4 / 2×2 不读这个字段，行为不变（已回归验证）。

### 27.4 资源与命名

- `res/layout/widget_schedule_wide.xml`：**只有一个 ImageView**（id 仍叫 `@+id/widget_bitmap`，
  更新代码不用分叉）+ 注释说明「本变体无底栏」；4×2 是纯位图、没有翻天。
- `res/layout/widget_schedule_wide_preview.xml`：静态 `ImageView` → `@drawable-nodpi/widget_schedule_wide_preview.png`。
- `res/xml/schedule_widget_wide_info.xml`：`targetCellWidth=4` / `targetCellHeight=2`，`minWidth=250dp` /
  `minHeight=110dp`（70·n−30），`minResizeWidth=200dp` / `minResizeHeight=90dp`，`updatePeriodMillis=0`。
- 文案：`widget_schedule_wide_name` =「蓝航Lite·今日安排」（2–10 汉字、与其它组件不重名）、
  `widget_schedule_wide_description` =「两行显示今天的课程与教室」（≤22 字）、
  `widget_wide_today_format` / `widget_wide_period_one` / `widget_wide_period_range`（zh + en 双份）。
  **en 文案里不要放 `\'`**：aapt 会报 `Invalid unicode escape sequence`（踩过）。
- 清单：`.widget.ScheduleWidgetWideProvider`，`exported=true`，`APPWIDGET_UPDATE` + `meta-data`。
- 预览图生成脚本 `scratch/r26_preview_wide.py`（PIL，880×440 = 280×140dp×3.143）：
  几何常量与 `WideRenderer` 一一对应，**改渲染器必须重跑**（AGENTS §26.2 第 3 条：预览是外观复制品，不是真渲染）。

### 27.5 验证记录（模拟器 emulator-5554，1.1.0 覆盖安装）

- 深色：`今天 / 周二` + `第 9 周`；行 1 = 大学英语1 `10:00 11:40` `第 3-4 节 | B408 | 许丽萍`（条为 `primary` 蓝，
  因为当时正在上）、行 2 = 大学计算机基础训练 `14:00 15:40` `第 5-6 节 | 综合楼南310 | 黄华`（条为课程色）。
- 浅色：同数据，灰容器 + 白卡，和设计稿一致（`artifacts/r26-light-wide2.png`）。
- 选择器：蓝航Lite 组显示「3 个微件」，第三张卡片标题/尺寸/描述正确（`artifacts/r26-pick7.png`）。
- 回归：2×2 重装后正常（倒计时、胶囊、◀ ▶、三色不撞）`artifacts/r26-2x2.png`；4×4 重装后正常
  `artifacts/r26-4x4b.png`（light）/`r26-final-home.png`（dark）。
- 设备已恢复原状：深色主题 + 首页 4×4 一件。**本轮没有 push、没有 release**（用户没说要推）。

### 27.6 小部件操作备忘（本轮踩到的）

- 选择器里展组后，**点卡片下方的「+ 添加」最稳**（`uiautomator dump` 里是 `content-desc="添加“蓝航Lite·周课表”微件"`，
  中心约 (610,1902)）；`input draganddrop` 有时被列表当成滚动，只有慢速长拖才生效。
- 主题模式弹窗选项的真实坐标（1220×2656，density 2.75）：`亮色 (134,1957)`、`暗色 (134,2117)`、
  `跟随系统 (180,2277)`。**别按截图比例去估**，一律先 `uiautomator dump`。

---

## 26. 第二十三轮（2026-10-27）：桌面小组件按 HyperOS 规范重做（1.1.0 / versionCode 52）

用户原话：

> 「`https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1664` 参照这个设计规范，
> 重新设计桌面小组件，将小组件 miuix 化，push 到 github」

### 26.1 改了什么

`widget/ScheduleWidgetRenderer.kt`（两个小组件的唯一渲染层，全部重写）：

* `Palette`（background / header / today / primary / onPrimary / onSurface / onSurfaceVariant / gridLine / isDark），
  浅色 `card #FFFFFF` `header #F0F0F0` `accent #3482FF` `onSurfaceVariant #999999` `gridLine #0F000000`；
  深色 `card #242424` `header #2D2D2D` `accent #4C8DFF` `onSurfaceVariant #8E8E93` `gridLine #1AFFFFFF`。
  夜间判定：`Session.themeMode` 优先，未设置才看 `Configuration.UI_MODE_NIGHT_MASK`。
* `courseHue(name)`（hashCode × 137.508 黄金角）→ `courseTint` / `courseInk`：
  底色 = `blend(card, HSL(hue, .62, .52), 0.15 浅 / 0.26 深)`，墨色从同色相逐步压深直到
  `calculateContrast ≥ 4.5`（`MIN_TINT_CONTRAST`）。
* 新增 `fitText()`、`drawActionPill()`（空态里的「打开蓝航Lite」）、`drawEmpty()`。
* 4×4：`drawTitle()`（「第 N 周」12dp **BOLD** + 右上日期区间 10.5dp 弱化）→ `drawHeaders()`（扁平表头，
  仍只给今天一颗淡蓝胶囊）→ `drawTodayColumn()` → `drawGridLines()`（发丝线）
  → `drawTimeColumn()` → `drawCourses()`（竖排课名，最多两列）。
* 2×2：表头「今天 · 周X + 日期」，正在上的那节用 `primary + onPrimary`，其余用 `tint + ink`。

### 26.2 硬规则（下次改小组件先看这四条）

1. **颜色只能从 `Palette` 取**，不准在渲染代码里写重量级硬编码色值；要改配色只改 `Palette` 两套字典。
2. **课程块不许再出现「饱和底 + 白字」**（旧版正是这个才显得不 Miuix）；墨色必须过
   对比度检查，宁可底色更淡也不能让字看不清。
3. **小组件预览图必须是静态图**。选择器渲染 `previewLayout` 用的是**宿主自己的 Context**，
   结构稿一旦有不可解析的属性就退化成一块灰色占位块（本轮 2×2 一直显示灰块就是这个原因）。
   现在两个 `*_info.xml` 的 `previewLayout` 都是一个 `ImageView`，指向
   `drawable-nodpi/widget_schedule_preview.png` / `widget_schedule_today_preview.png`；图由
   `scratch/r24_preview.py` 按 **light palette** 生成，**改了配色与布局就要重跑这个脚本**。
4. **预览图 = 真实渲染的镜像**：竖排课名、今天列淡蓝底、今天表头胶囊、「第 N 周」胶囊 + ◀ ▶
   都要画进去，否则选择器里看到的和放下后看到的不一样。

### 26.3 命名与描述（规范硬要求）

* 名称「应用·小部件」2–10 个汉字：`widget_schedule_name` = 「蓝航Lite·周课表」，
  `widget_schedule_small_name` = 「蓝航Lite·今日课程」；描述 ≤ 22 字。
* 不放应用图标（应用名已在名称里）；无数据/未登录要有文案（`drawEmpty`）；不允许内部滚动与输入框。

### 26.4 本轮怎么验的（四个状态都截图）

| 状态 | 截图 |
|---|---|
| 4×4 深色 | `artifacts/r24-dark-week2.png` |
| 4×4 浅色 | `artifacts/r24-light-week.png` |
| 2×2 浅色 | `artifacts/r24-light-2x2.png` |
| 2×2 深色 | `artifacts/r24-dark-2x2c.png` |
| 选择器预览（两个） | `artifacts/r24-pick-new.png` / `r24-pick-2x2.png` |

* 倒计时是真数据（「剩 97 分」随时间变），今日列/今日表头都跟系统日期走（周二 10-27）。
* 深色下配色对比度看得清（淡紫底 + 淡紫墨），能力内不再出现白字压饱和色块。

### 26.5 模拟器上安装/拖拽小组件的坑

* 模拟器里同时装着参照包 `com.lanhang.lite` 与本包 `com.lanhang.miuix`，**两个小组件名、图标一模一样**。
  拖错一个就会以为「改了没生效」——只能靠小组件标题/描述文字判断归属。
* 添加必须从选择器里拖：`input swipe x y x y 1200` 快长按会报「无法添加微件。」，
  要用 `input draganddrop <预览图坐标> <落点> 2600`；拖完再 `tap` 一下固定。
* 选择器里群组需先展开（行尾箭头 x≈1104），再向下滚才能看到 2×2 预览。
* 切主题路径：我的 → 设置 → 外观 → 主题模式 → 亮色/暗色；切完小组件会跟着重绘（`updateAll`）。

### 26.6 同日补丁：2×2 撞色 / 4×4 黑线太重

用户原话：

> 「给 2*2 的小组件的不同课程加上不同的颜色，
> 4*4 的小组件课程底下的黑色线颜色不要这么重」

1. **2×2 撞色**：色相原来只由课名哈希决定，同一天两三门课哈希到相近色相就变成「一片紫」。
   现在 `distinctHues(courses)` 逐行去重：与已用色相相差 < 42° 就按 47° 顺次转开（最多 12 次）。
   4×4 不用（不同天的课被时间隔开，保持「同课同色」更重要）。
2. **4×4 黑线太重 = `paint.alpha = 255` 把半透明色顶成纯黑**。`Paint.color` 已经把 alpha 一起设好，
   再写 `paint.alpha = 255` 就把 4% 的黑线变成 100% 黑（浅色下就是用户说的「黑线」；
   深色下 10% 白线被顶成近纯白，同样扎眼）。现在 `drawGridLines()` 不碰 alpha，
   线色浅色 `0x0A000000`（4%）/ 深色 `0x16FFFFFF`（8.6%）。
3. **硬规则**：任何 `paint.color = <带 alpha 的色>` 之后**不许**再写 `paint.alpha = ...`；
   要降透明度就用 `ColorUtils.setAlphaComponent(...)` 连色带 alpha 一起给。
4. 截图：`artifacts/r25-light-2x2.png` / `r25-dark-2x2.png`（三色分明）、
   `r25-light-week.png` / `r25-dark-week.png`（分隔线明显变淡）。
5. 选择器预览图同步重生成（`r24_preview.py` 的 `GRID` 改 `0x0A`），
   保证预览与放下后一致。
6. 本次修改**未发版**（用户未说 push），按 `VERSION.md` 规则等下次要发版时
   再把 `versionCode` +1、`versionName` 进位到 `1.1.1`。

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
