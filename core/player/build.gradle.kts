plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.novum.tv.core.player"
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
    implementation(project(":core:data"))
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.core.ktx)
}
