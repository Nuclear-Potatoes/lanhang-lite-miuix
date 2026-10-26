# app/libs — 本地依赖

## `im_lib-2.9.24-patched.aar`

融云 **IMLib 2.9.24**（`cn.rongcloud.sdk:im_lib:2.9.24`，原生 Apache-2.0 版 AAR，
下载自 `https://maven.rongcloud.cn/repository/maven-releases/`），
按下面两条做了**最小改动**后放进仓库，用 `implementation(files("libs/im_lib-2.9.24-patched.aar"))` 引用。

### 为什么不用 maven 上的原版

1. **版本必须留在 2.9 这一代**：官方客户端（蓝航 1.0.14）内置的是 IMLib **2.9.19**
   （反编译 `io/rong/imlib/common/BuildVar.SDK_VERSION`）。学校私有云（`https://nginx-1.app.nchu.edu.cn:1444`）
   下发的 token 是 92 字符的 RCE 加密串，5.21.4 / 4.2.1 连上去直接 `RC_CONN_TOKEN_INCORRECT`；
   换 2.9.24 后同一份 token 立刻 `connect ok`（logcat 实测）。
2. **2.9 的 AAR 在 targetSdk 31+ 会崩**：两处 `PendingIntent` 的 flags 传 0/只有 `FLAG_UPDATE_CURRENT`，
   Android 12 起会抛 `IllegalArgumentException: Targeting S+ ... requires FLAG_IMMUTABLE or FLAG_MUTABLE`。
   其中 `WakeLockUtils.startNextHeartbeat` 就挂在「连接成功」回调里 → `:ipc` 进程一连上就崩、无限重启；
   `PushConnectivityManager.startPushTimer` 让 `io.rong.push` 进程同样崩。原版在 targetSdk ≤30 的官方客户端里不会暴露。

### 改了哪两处（都只动 flags，其余字节原样）

| 位置 | 原值 | 改后 |
|---|---|---|
| `classes.jar` → `io/rong/common/WakeLockUtils.class` | `startNextHeartbeat`/`cancelHeartbeat` 里 `PendingIntent.getBroadcast(..., 0)` | **重编译版**（`scratch/rong/patchsrc/io/rong/common/WakeLockUtils.java`）：补 `FLAG_IMMUTABLE`，`setExact` 换成不需要 `SCHEDULE_EXACT_ALARM` 的 `set`，读数/下限/异常兜底语义与原来一致 |
| `libs/tools_push_lib-2.9.24.jar` → `io/rong/push/rongpush/PushConnectivityManager.class`、`io/rong/push/notification/RongNotificationInterface.class` | 常量池 `CONSTANT_Integer 134217728`（`FLAG_UPDATE_CURRENT`） | `201326592`（`FLAG_UPDATE_CURRENT｜FLAG_IMMUTABLE`），**只改常量池取值，指令长度不变**，不影响分支偏移与 stack map |

生成脚本（可复跑）：`scratch/patch-history/tmp_rong_repack2.py`
（WakeLockUtils 的重编译源码在 `scratch/rong/patchsrc/`，先 `javac -source 8 -target 8`
对着 `android.jar` + 原 `classes.jar` 编出 class）。

### 副作用

- 走本地 AAR 后没有 POM，顺手避开了 2.9 的 POM 里
  `com.android.support:support-compat:26.1.0` 与 `androidx.core` 的 `Duplicate class` 冲突。
- 心跳唤醒从 `setExact` 降级为不精确 `set`：它只是推送兜底（本项目不接推送），
  私信在页面里使用时由 native 层的 TCP 心跳维持，不受影响。
- 升级/替换这个 AAR 时**必须重新过一遍**上面两条补丁，否则私信一连上就崩。
