plugins {
    id("com.android.library")
}

val media3Version = rootProject.extra["media3Version"] as String

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.txt")
        externalNativeBuild {
            cmake {
                abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libffmpegJNI.so"
        }
    }
}

dependencies {
    // 编译期符号解析用；AAR 不打包这些依赖，运行时由 novum 自身的
    // media3 1.11.1 依赖提供，避免宿主重复类与版本冲突。
    compileOnly("androidx.media3:media3-common:$media3Version")
    compileOnly("androidx.media3:media3-decoder:$media3Version")
    compileOnly("androidx.media3:media3-exoplayer:$media3Version")
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("com.google.guava:guava:33.4.8-android")
    // Java 源码里的 @MonotonicNonNull 注解需要它（官方 ExoPlayer 用 checker-qual）
    compileOnly("org.checkerframework:checker-qual:3.43.0")
}
