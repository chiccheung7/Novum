plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.novum.tv.testing"
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
}

kotlin {
    jvmToolchain(17)
}

/**
 * 测试支撑模块。
 *
 * 承载跨模块复用的测试替身与规则：MainDispatcherRule / RecordingToastSink /
 * FakeCapabilityProvider / NoopNovumLogger / FakeMediaStrings / NoopAuthRepository +
 * NoopSessionStore / FakeSettingsRepository / EmbyApiStub / RepoPaths。
 *
 * 依赖用 `api` 暴露：消费方（各模块的 `testImplementation(project(":core:testing"))`）
 * 不为重复声明 junit / coroutines-test / robolectric / androidx.test.core 而买单。
 * 本模块不参与打包（只被 testImplementation 引用），fixtures 放在 main 源集
 * 是为了避免 `testFixtures` 插件给每个消费方带来的额外配置。
 */
dependencies {
    // 被测类型所在模块：CapabilityProvider/MediaStrings/AuthRepository（core:data）、
    // ToastSink/NovumLogger（core:common）、EmbyApi（core:network）
    api(project(":core:common"))
    api(project(":core:network"))
    api(project(":core:data"))
    // EmbyApiStub 的 serverDomains 返回 JsonElement
    implementation(libs.kotlinx.serialization.json)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.test.core)
    api(libs.robolectric)
}
