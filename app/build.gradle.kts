plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("lanhangReleaseStoreFile")
val releaseStorePassword = providers.gradleProperty("lanhangReleaseStorePassword")
val releaseKeyAlias = providers.gradleProperty("lanhangReleaseKeyAlias")
val releaseKeyPassword = providers.gradleProperty("lanhangReleaseKeyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it.isPresent }

android {
    namespace = "com.lanhang.miuix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lanhang.miuix"
        minSdk = 26
        targetSdk = 35
        versionCode = 67
        versionName = "1.1.15"
        // 私信引入的融云 SDK 每个 ABI 都带一份 ~9MB 的 libRongIMLib.so；x86（32 位模拟器）
        // 没有真实设备，去掉省 9MB。arm64 + armeabi-v7a 覆盖真机，x86_64 覆盖模拟器。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.create("configuredRelease") {
                    storeFile = file(releaseStoreFile.get())
                    storePassword = releaseStorePassword.get()
                    keyAlias = releaseKeyAlias.get()
                    keyPassword = releaseKeyPassword.get()
                }
            } else {
                // 没提供正式签名（-PlanhangReleaseStoreFile=... 等）时退回 debug keystore：
                // 这样 assembleRelease 出来的包**可以直接安装**，并且能覆盖已装的 debug 包（同签名，不丢登录态）。
                // 真要上架再传那四个 gradle property 换正式签名。
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Compose：2026-10-24 起承载 Miuix 设计体系的界面（用户指定引入 miuix）
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.activity:activity-compose:1.9.2")
    // Miuix 的 OverlayDialog 依赖 androidx.navigationevent（返回键处理）。它本来就在
    // miuix-ui 的运行时依赖里，只是不作为 api 暴露；这里仅 compileOnly 引用，
    // 不往 APK 里加任何东西，作用是给 ComposeView 补一个 LocalNavigationEventDispatcherOwner。
    compileOnly("androidx.navigationevent:navigationevent-compose:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // coil 的 Compose 扩展（与既有 coil 2.6.0 同版本，不引入新的图片加载栈）
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Miuix：用户指定的设计库（github.com/compose-miuix-ui/miuix）
    // 0.9.1 = 与项目 Kotlin 2.3.21 / Compose 1.11 对齐的版本
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.1")
    // 身份码页要用 QR + 条码：zxing core 只做纯算法（无 Android UI 依赖，约 500KB）
    implementation("com.google.zxing:core:3.5.3")
    // 私信：融云 IMLib（用户 2026-10-26 同意接入）。只用 im_lib 的收发能力，
    // 界面全部自绘 Miuix，不用 IMKit 那套 Activity；聊天室/位置/讨论组/客服/公众号
    // 这几个子模块本项目用不到，排除掉免得白占体积。
    // 版本必须是学校私有云那一代：官方客户端（蓝航 1.0.14）内置的是 IMLib **2.9.19**
    // （反编译 BuildVar.SDK_VERSION），5.21.4 连学校 navi 服务器会被判 token 无效
    // （实测 RC_CONN_TOKEN_INCORRECT）。2.9.24 是 Maven 上同一代的最后一版，AAR 自带全部 so。
    // 用本地打过补丁的 AAR（不走 maven）：
    //  ① 版本必须是学校私有云那一代 —— 官方客户端（蓝航 1.0.14）内置 IMLib **2.9.19**
    //     （反编译 BuildVar.SDK_VERSION），5.x/4.x 连学校 navi 服务器会被判 token 无效；
    //  ② 2.9 的 io.rong.common.WakeLockUtils.startNextHeartbeat 用 flags=0 建 PendingIntent，
    //     targetSdk 31+ 必抛异常，而它就挂在「连接成功」回调里 → :ipc 进程一连上就崩。
    //     补丁把该方法改成空实现（它只是 AlarmManager 心跳兜底，本项目不接推送）。
    //  ③ 走本地 AAR 还顺手避开了它 POM 里 com.android.support:support-compat:26.1.0
    //     和 androidx.core 重名（Duplicate class）的问题。
    implementation(files("libs/im_lib-2.9.24-patched.aar"))
    // 2026-10-26 第二轮：小米推送（MiPush）已放弃（用户要求改用普通通知），
    // 不再依赖 com.xiaomi.mipush.sdk:mipush，清单里也没有它的组件。
    // 现在的做法见 im/ImNotify.kt：App 在后台时用系统通知（NotificationChannel + NotificationCompat）提示新私信。
}
