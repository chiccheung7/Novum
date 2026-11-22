# FFmpeg 音频解码扩展（可选构建组件）

本目录保存 Media3 FFmpeg 解码扩展的**完整可复现构建工程**，用于生成
`app/libs/media3-ffmpeg-decoder.aar`。App 主工程平时不依赖本目录；只有需要
重建 AAR 时才用到。

## 它解决什么

电视平台的硬解通常不支持 AC3 / E-AC3 / DTS / DTS-HD / TrueHD。本扩展为
Media3 提供 `FfmpegAudioRenderer` 软解兜底（平台解码器优先，解不了才用它）。
App 侧的 `OffloadRenderersFactory` 已通过 `EXTENSION_RENDERER_MODE_ON` 启用，
AAR 放进 `app/libs/` 即自动生效，无需改一行 App 代码。

## 组成

| 路径 | 来源 | 说明 |
| --- | --- | --- |
| `decoder/src/main/java/` | AndroidX Media3 `libraries/decoder_ffmpeg` 1.11.1 | Java 包装层，Apache-2.0 |
| `decoder/src/main/jni/ffmpeg_jni.cc`、`CMakeLists.txt`、`build_ffmpeg.sh` | 同上 | JNI 包装层，Apache-2.0 |
| `build.sh` | 本项目 | 一键构建脚本（下载 FFmpeg → 编静态库 → 编 AAR → 复制到 app/libs） |
| `LICENSE_FFMPEG_LGPLv2.1.txt` | FFmpeg 项目 | FFmpeg 二进制适用的 LGPL 2.1+ 许可 |

## 一键重建

前置条件：JDK 17、Android SDK、NDK r26b（`26.3.11579264`）、CMake 3.22.1，
并设置 `ANDROID_HOME`。在**仓库根目录**执行：

```sh
bash ffmpeg-decoder/build.sh
```

脚本做四件事：

1. 下载 FFmpeg `release/6.0` 源码到 `decoder/src/main/jni/ffmpeg/`（已存在则跳过）
2. 用 NDK 编译 `ac3 eac3 truehd dca` 四个解码器的静态库到四个 ABI（已存在则跳过）
3. 复用根工程的 Gradle wrapper 编译出 `decoder-release.aar`
4. 复制到 `app/libs/media3-ffmpeg-decoder.aar` 并打印 SHA-256

想换解码器：改 `build.sh` 里的 `ENABLED_DECODERS`，删除
`decoder/src/main/jni/ffmpeg/android-libs/` 后重跑。ABI 下限 `ANDROID_ABI` 必须
不大于 App 的 `minSdk`（24）。

单独只编译 AAR（FFmpeg 已就绪时）：

```sh
./gradlew -p ffmpeg-decoder :decoder:assembleRelease
```

## 许可边界（重要）

- Java / JNI 包装层来自 AndroidX Media3，Apache-2.0。
- FFmpeg 及其静态库按 **LGPL 2.1 或更高**授权（本次构建未启用 `--enable-gpl`
  或 `--enable-nonfree`），完整条款见 `LICENSE_FFMPEG_LGPLv2.1.txt`。
- 本目录的构建脚本不改变 FFmpeg 的许可；分发含本 AAR 的 APK 时须遵守 LGPL
  的再分发要求（提供对应源码/许可声明）。这与 `THIRD_PARTY_NOTICES.md` 的
  口径一致。
- AAR 二进制本身不入库（`.gitignore` 已排除 `*.aar` 与 FFmpeg 源码），只入库
  源码与脚本，任何人可据此复现。

## 与 App 版本对齐

`build.gradle.kts` 中的 `media3Version` 必须与 `gradle/libs.versions.toml`
的 `media3` 版本一致（当前 `1.11.1`）；`compileSdk`/`minSdk` 与根工程一致
（37 / 24）。升级 Media3 时请同步这两处。
