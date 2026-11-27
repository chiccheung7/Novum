plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.novum.tv.core.network"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        // 库模块没有 versionName 字段，显式注入；与 :app 的 versionName 同源
        // （gradle/libs.versions.toml 的 novum）：它写进 X-Emby-Authorization 的 Version 字段，少数网关会校验。
        buildConfigField("String", "VERSION_NAME", "\"${libs.versions.novum.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(project(":core:testing"))
    implementation(project(":core:common"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
