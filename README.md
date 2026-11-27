# Novum

[![Android checks](https://github.com/chiccheung7/Novum/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/chiccheung7/Novum/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/chiccheung7/Novum)](https://github.com/chiccheung7/Novum/releases/latest)

Novum 是一款面向 Android TV 的开源 Emby 客户端。它使用 Kotlin、Jetpack Compose for TV 和 Media3，围绕电视遥控器设计浏览、搜索和播放体验。

Novum 是客户端，不提供媒体、Emby 服务器或账号。使用前需要准备自己的 Emby 服务器；播放能力取决于服务器、片源、网络和电视的解码能力。

## 功能

- 浏览媒体库、搜索影片与剧集，并查看影片详情、季和集。
- 在首页查看继续观看和媒体库内容，并同步播放进度。
- 使用电视遥控器操作界面和播放器，包括进度跳转、选集与播放设置。
- 根据服务器和设备能力选择直连或转码，查看播放信息并切换可用音轨、字幕和媒体版本。
- 配置字幕、画面比例、音频及播放器行为；可选显示弹幕。

部分播放选项需要服务器提供相应能力。具体音视频格式支持由设备、Media3 解码器、服务端转码能力和片源共同决定。

## 下载

当前稳定版为 **0.2.5**。前往 [GitHub Releases](https://github.com/chiccheung7/Novum/releases/latest) 下载 APK；发布页同时提供 SHA-256 校验文件和许可材料。

安装后，在登录页填写自己的 Emby 服务器地址和账号。建议使用 HTTPS；连接到 HTTP 地址时，应用会在提交凭据前提示风险。

## 本地构建

需要 JDK 17、Android SDK Platform 37.1 和 Android SDK Build Tools 36.0.0。先在 Android Studio 配置 SDK，或在项目根目录的 `local.properties` 中设置 `sdk.dir`，再运行：

```sh
# 单元测试、Lint 和 Debug APK
./gradlew testDebugUnitTest :app:lintDebug :app:assembleDebug

# 干净构建 Release APK
./gradlew clean :app:assembleRelease
```

Windows 使用 `gradlew.bat`：

```powershell
.\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat clean :app:assembleRelease
```

APK 输出在 `app/build/outputs/apk/`。Release 签名通过本机 `keystore.properties` 配置；可参考 [`keystore.properties.example`](keystore.properties.example)。没有本机签名配置时，构建出的 Release APK 未签名。不要提交签名文件、密码或构建产物。

## 可选 FFmpeg 音频扩展

仓库不包含预编译 FFmpeg AAR。需要时，按 [`ffmpeg-decoder/README.md`](ffmpeg-decoder/README.md) 安装对应的 NDK 和 CMake，从源码构建扩展并放入 `app/libs/`。未加入扩展时，应用使用电视平台的音频解码器；无法直放的音频格式可由 Emby 服务器转码。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `app/` | 应用入口、导航、登录和设置页面 |
| `core/data/` | Emby 数据模型、仓储和设置持久化 |
| `core/network/` | Emby API、HTTP 客户端和网络配置 |
| `core/player/` | Media3 播放器和媒体轨道支持 |
| `core/designsystem/` | 电视界面主题、组件和布局 |
| `feature/` | 首页、详情页与播放页 |
| `tools/tcast/` | 面向兼容电视的 T-Cast 辅助脚本 |

## 隐私与安全

应用连接到用户自行配置的 Emby 服务器，不附带媒体内容或账号。请勿在公开问题、日志或截图中分享服务器凭据、访问令牌、私人媒体信息或内网地址。安全问题请按 [`SECURITY.md`](SECURITY.md) 的说明报告。

## 贡献

欢迎提交问题和改进建议。提交代码前请运行单元测试与 Lint，并阅读 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## 许可

Novum 自有代码以 [Apache License 2.0](LICENSE) 发布。字体、依赖和可选 FFmpeg 扩展各自遵循其原有许可，详见 [`NOTICE`](NOTICE)、[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 和 [`core/designsystem/INTER-OFL.txt`](core/designsystem/INTER-OFL.txt)。
