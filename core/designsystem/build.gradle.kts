plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.novum.tv.core.designsystem"
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
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
}

// 仅显式传入 -PnovumComposeMetrics 时生成指标，可用于分析重组并配合 Perfetto 检查帧表现。
composeCompiler {
    if (project.hasProperty("novumComposeMetrics")) {
        metricsDestination = layout.buildDirectory.dir("compose-metrics").get()
        reportsDestination = layout.buildDirectory.dir("compose-reports").get()
    }
}
