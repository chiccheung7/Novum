plugins {
    alias(libs.plugins.android.test)
}

// 帧时序测量模块（流畅度规范 11.2）：Macrobenchmark FrameTimingMetric。
// connectedBenchmarkAndroidTest 跑 StartupBenchmark.dpadScrollFrameTiming 产出
// P50/P90/P99 报告。profile 生成管线待 androidx.baselineprofile 适配 AGP 9
// 后接入（android.baselineprofile 插件 + generateReleaseBaselineProfile 任务）。
android {
    namespace = "com.novum.tv.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    // profile 生成走 release（R8 规则由生成结果自动重写，规范 E4）
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
