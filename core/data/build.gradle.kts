plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.novum.tv.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
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
    // DefaultAuthRepositoryTest 造 HttpException/Response
    testImplementation(libs.okhttp)
    testImplementation(libs.retrofit)
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(libs.androidx.datastore.preferences)
    // 线路探测直接消费服务器返回的 JSON（结构未知，用 JsonElement 容错解析）
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.media3.exoplayer)
}
