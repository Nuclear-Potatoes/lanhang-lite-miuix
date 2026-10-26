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
