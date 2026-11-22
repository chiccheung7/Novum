// 与 novum 根工程保持一致的构建工具链版本。
// 本工程只负编译 FFmpeg 解码 AAR，不参与 App 的日常构建。
plugins {
    id("com.android.library") version "9.5.0" apply false
}

// 必须与 novum/gradle/libs.versions.toml 的 media3 版本一致。
extra["media3Version"] = "1.11.1"
