# 蓝航Lite · Miuix 改版（lanhang-lite-miuix）

南昌航空大学「蓝航」App 的第三方轻量客户端，**Miuix（HyperOS 风格）视觉重构版**。
包名 `com.lanhang.miuix`，当前版本 **1.0.0（versionCode 51）**。

> 本项目与南昌航空大学及官方「蓝航」App 无任何关系；接口协议来自公开抓包与静态分析，
> 仅供学习交流，请勿用于任何商业用途。

---

## 下载 / 安装

最新安装包在 **[Releases](https://github.com/Nuclear-Potatoes/lanhang-lite-miuix/releases/latest)** 页，
文件名 `lanhang-miuix-<版本号>.apk`，附 `SHA256SUMS.txt`。

- 与本项目历史版本**同一签名**（debug key），可直接覆盖安装，不用卸载、登录态保留；
- Release 由 GitHub Actions 自动构建（`.github/workflows/release.yml`），见 [发布](#发布github-actions)。

## 与上游的关系

- 上游项目：[`Micheal-Reber/lanhang-lite`](https://github.com/Micheal-Reber/lanhang-lite)
- 本仓库是**独立分叉**，与原项目已彻底分离：不向上游提交 PR，上游代码变动也不会自动合入。
- 本分叉只做两件事：**① 用 Miuix 重做整套界面**（外壳、顶栏、底栏、成绩、设置、工作圈、我的、通讯录、私信）；
  **② 统一并加固所有转场/收放动效**（顶栏收缩扩张、二级页推入返回、Tab 切换只有一份实现）。
  数据层（登录、课表、成绩、工作圈、IM 协议）保持上游语义，除必要的新增外不改。

## 功能

- 学号密码登录（密码 RSA 加密上送）
- 课表：周视图 / 网格视图（自绘，非 Compose），按周缓存、下拉刷新、导出 .ics/.csv
- 成绩：学期切换、加权平均 / 总学分 / 课程数统计
- 工作：办事大厅目录 + 常用功能（数据来自学校 `/apps/all`），功能页走内置 WebView
- 工作圈：融云圈动态浏览、点赞、评论、图片预览、发表
- 私信：基于融云 IMLib 2.9（文本/图片/语音/位置/文件），界面自绘
- 桌面小组件：2×2「今日课程」（翻天导航 + 倒计时）与 4×4 周视图
- 主题：亮色 / 暗色 / 跟随系统，Miuix 配色体系

## 构建

环境要求（本仓库验证过的组合）：

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.13+（AGP 8.13.2 要求） |
| AGP | 8.13.2 |
| Kotlin | 2.3.21 |
| compileSdk / targetSdk / minSdk | 37 / 35 / 26 |

```bash
# 仓库自带 Gradle Wrapper（8.14.3），不需要本机装 Gradle
./gradlew assembleDebug          # Windows：gradlew.bat assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease        # 未配正式签名时自动回落到 ci/debug.keystore 同款 debug 签名
# 输出：app/build/outputs/apk/release/app-release.apk（可直接安装）
```

说明：

- `local.properties` 里的 `sdk.dir` 需要指向自己的 Android SDK（该文件已被 `.gitignore` 排除）。
- `app/libs/im_lib-2.9.24-patched.aar` 是为本项目打的融云 IMLib 补丁包（清单缺 `android:exported` 等
  targetSdk 35 兼容修正），**必须随仓库一起保留**，否则私信编译不过。
- `compileSdk 37` 需要 `gradle.properties` 里的 `android.suppressUnsupportedCompileSdk=37`（Miuix 的 AAR 要求）。

### Release 签名（可选）

在 `~/.gradle/gradle.properties`（**不要写进仓库**）配置后，release 构建会自动使用指定 keystore；
未配置时回落到 debug 签名（便于本地安装验证）：

```properties
lanhangReleaseStoreFile=/path/to/release.jks
lanhangReleaseStorePassword=***
lanhangReleaseKeyAlias=***
lanhangReleaseKeyPassword=***
```

## 发布（GitHub Actions）

`.github/workflows/release.yml`：**推 tag 即出包发版**。

```bash
# 例：把 versionCode / versionName 改好之后
git tag v1.0.0
git push origin v1.0.0        # 触发 Actions → 构建 → 建 Release → 上传 APK
```

也可以在 Actions 页面手动 `Run workflow`（可指定标签，留空则用 `v + versionName`）。
工作流做的事：JDK 17 → `./gradlew assembleRelease`（用 `ci/debug.keystore` 签名）→
上传 `lanhang-miuix-<版本>.apk` + `SHA256SUMS.txt` → 发行说明自动摘 `VERSION.md` 里对应版本那一行。
发布已存在时是 `--clobber` 覆盖资产 + 改写说明，重复跑不会炸。

## 项目结构（`app/src/main/java/com/lanhang/miuix/`）

```
App.kt            启动初始化（网络/会话/主题/仓库/崩溃日志）
net/              主 API、miniapp 握手、融云圈 API、RSA 加密
session/          本地会话与语言
schedule/         课表（唯一仍是 View 自绘的模块）+ 导出 + 背景
work/             办事大厅目录
grades/           成绩
moments/          工作圈动态流与发表
im/               私信（IMLib 封装 + 自绘气泡）
mine/             我的 / 成绩 / 通讯录 / 消息 / 身份码 / 个人资料
settings/         设置与诊断
ui/shell/         外壳：顶栏（ShellBars.kt，收放动画唯一实现）、底栏、外壳状态
ui/miuix/         主题、转场（NavPush.kt）、页面栈滚动状态
widget/           桌面小组件
```

动效只有两份实现，不要在页面里另写：
- `ui/miuix/NavPush.kt` 的 `MiuiNavStack` + `object NavMotion` —— 所有推入/返回/Tab 横移；
- `ui/shell/ShellBars.kt` 的 `rememberBarCollapse` + `object BarAnimation` —— 所有顶栏收缩/扩张。

## 数据来源

| 端点 | 用途 |
|---|---|
| `https://nginx-1.app.nchu.edu.cn/api` | 主 API（登录、配置、应用令牌、人员信息） |
| `http://miniapp.app.nchu.edu.cn` | 课表 / 成绩 miniapp（需经 `go.nchu.edu.cn` 网关握手换会话） |
| `https://nginx-1.app.nchu.edu.cn/moments/api` | 工作圈（融云圈） |

登录会话、课表与成绩数据**只保存在本机**（`filesDir` 缓存 + `SharedPreferences`），不上传任何第三方服务器。

## 致谢

- 上游：[lanhang-lite](https://github.com/Micheal-Reber/lanhang-lite)
- 课表 UI 设计参考 [sleepy](https://github.com/lingion/sleepy)（GPL-3.0，仅参考风格，未抄代码）
- 界面组件：[Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)（HyperOS 风格 Compose 组件库）

## 许可证

[GPL-3.0](LICENSE)
