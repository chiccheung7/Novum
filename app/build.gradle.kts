import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Baseline Profile：src/main/baseline-prof.txt 由 AGP 内建打包。
    // androidx.baselineprofile 1.4.1 与 AGP 9.5 不兼容，不在此应用插件。
}

// 签名信息放在仓库外的 keystore.properties：不提交密钥，CI 缺失时降级为未签名包
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = !keystoreProperties.isEmpty

android {
    namespace = "com.novum.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.novum.tv"
        // navigation-compose 2.10.x 的 AAR 声明 minSdk 24，故下限锁 24（Android TV 7.0）
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        // 与 :core:network 的 BuildConfig.VERSION_NAME 同源（gradle/libs.versions.toml 的 novum）
        versionName = libs.versions.novum.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // FFmpeg 音频扩展 AAR（app/libs）的 DEX 引用了脱糖 API，必须开启
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Compose / DataStore 依赖的预编译 .so 无对应符号表，显式保留以避免打包期 strip 告警
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        // 忽略 vendored FFmpeg AAR 的 16KB 页对齐告警，以及 API 24–25 所需的 legacy launcher PNG 告警。
        disable += "Aligned16KB"
        disable += "IconLauncherShape"
    }

    testOptions {
        unitTests {
            // Robolectric 需要访问 Android 资源与清单
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // FFmpeg 音频扩展 AAR：支持 AC3/E-AC3/DTS/DTS-HD/TrueHD 软解。
    // 四 ABI 的 libffmpegJNI.so 共 2.9MB；渲染器见 PassthroughAudioRenderersFactory。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    coreLibraryDesugaring(libs.tools.desugar.jdk.libs)
    // 模块依赖：core 三层 + 三个 feature
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:player"))
    implementation(project(":feature:home"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:player"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // 基础 AndroidX
    implementation(libs.androidx.core.ktx)
    // Baseline Profile 安装器：启动时把预编译规则交给 ART，冷启动更快
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)

    // 生命周期与 ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 导航
    implementation(libs.androidx.navigation.compose)

    // Compose for TV（Material3 for TV + TV 基础组件）
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // Compose 基础
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // JankStats（debug-only）：逐帧采样时长，用于区分解码与布局开销。
    debugImplementation(libs.androidx.metrics.performance)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 依赖注入（Hilt + KSP）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // 设置持久化与序列化
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // 播放
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // 单元测试
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 仪器化测试 / Compose UI 测试
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Compose Compiler Metrics
// 开关：`./gradlew assembleDebug -PnovumComposeMetrics`，仅显式启用时生成报告。
// 输出位于 build/compose-metrics/*-composables.txt，可配合 Perfetto 分析重组与合成开销。
composeCompiler {
    if (project.hasProperty("novumComposeMetrics")) {
        metricsDestination = layout.buildDirectory.dir("compose-metrics").get()
        reportsDestination = layout.buildDirectory.dir("compose-reports").get()
    }
}
