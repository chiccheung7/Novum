// FFmpeg 解码扩展的独立构建工程。
// 刻意不加入 novum 根工程的 settings.gradle.kts：FFmpeg 原生库需要 NDK 与
// FFmpeg 源码，普通用户只构建 App 时不应被迫安装这些。只有需要重建 AAR 时才
// 用 novum 根目录的 wrapper 执行：
//   ../gradlew -p ffmpeg-decoder :decoder:assembleRelease
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "NovumFfmpegDecoder"
include(":decoder")
