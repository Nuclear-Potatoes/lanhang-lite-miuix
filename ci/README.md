# ci/ 目录

## `debug.keystore`

**这不是正式发布密钥**，就是 Android SDK 用的标准 debug 签名（别名 `androiddebugkey`，
口令 `android`），被特意放进仓库里，唯一目的是让 GitHub Actions 产出的 APK 和本地构建
的 APK **签名一致**：

* 指纹（SHA-256）：`B8:CE:04:04:02:30:52:90:9B:31:1D:B8:46:C3:54:A2:AF:A2:7D:F0:78:AE:CA:0F:7E:84:B8:59:21:06:A7:EB`
* 有了它，从 Releases 下载的新包可以直接**覆盖安装**在旧包上，不需要卸载、不丢登录态。

上游 `app/build.gradle.kts` 已经支持「没配正式签名就回落到 debug 签名」，
CI 里通过 `-PlanhangReleaseStoreFile=...` 等四个属性指向本文件即可。

> 想换成自己的正式签名：把 `ci/debug.keystore` 换掉，或改用仓库 Secrets 注入，
> 二者只要保持一致，历史安装包就还能升级。
