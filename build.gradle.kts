// Novum 顶层构建脚本。
// AGP 9 起 Kotlin 编译能力内置于 Android Gradle Plugin，不再应用 org.jetbrains.kotlin.android。
buildscript {
    dependencies {
        // AGP 9.5.0 默认携带 KGP 2.2.10；此处显式升级到 libs.versions.toml 中声明的 Kotlin 稳定版，
        // 与 Compose 编译器插件版本严格保持一致（官方升级方式，见 Migrate to built-in Kotlin）。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // :benchmark 帧时序测量模块（baselineprofile 生成插件与 AGP 9.5 不兼容，
    // 未接入——见 app/build.gradle.kts plugins 注记）
    alias(libs.plugins.android.test) apply false
}
