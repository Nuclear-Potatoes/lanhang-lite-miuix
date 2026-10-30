plugins {
    id("com.android.application") version "8.13.2" apply false
    // engine 闭源模块是 Android Library，需要单独声明（版本与 application 插件对齐）
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
