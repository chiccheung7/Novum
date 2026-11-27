plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.novum.tv.feature.detail"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
}

// 显式传入 -PnovumComposeMetrics 时生成 Compose Compiler 指标与报告。
// 报告可用于分析可组合函数重组情况，并配合 Perfetto 查看焦点移动期间的帧表现。
composeCompiler {
    if (project.hasProperty("novumComposeMetrics")) {
        metricsDestination = layout.buildDirectory.dir("compose-metrics").get()
        reportsDestination = layout.buildDirectory.dir("compose-reports").get()
    }
}
