pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 融云 IM 的官方 Maven（cn.rongcloud.sdk:im_lib / im_kit），只有这一个仓库有
        maven { url = uri("https://maven.rongcloud.cn/repository/maven-releases/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "LanhangMiuix"
include(":app")
// 闭源协议层：网络传输 / 加密 / 会话 / 接口清单都在这里，构建出 engine-release.aar
// 后放进 app/libs 以二进制形式引用（app 模块的源码树里看不到接口面）。
//
// **条件 include**：engine 的 Kotlin 源码只留在本地（见 .gitignore），公开仓库里
// 只有 app/libs/engine.aar。CI 上这个目录不存在，于是跳过该模块、直接用已有的 aar；
// 本地开发时有源码，改完执行 `gradle :engine:assembleRelease` 覆盖 aar 即可。
if (file("engine").exists()) {
    include(":engine")
}
