# Novum

面向 Android TV 的 Emby 媒体客户端，使用 Kotlin、Jetpack Compose for TV 和 Media3 构建，通过遥控器浏览和播放自己的媒体库。

当前源码排除了 FFmpeg 预编译 AAR，需要的可以自己编译。

## 功能

- 登录自建 Emby 服务，浏览首页、媒体库、收藏和搜索结果。
- 查看影片、剧集、选集和演职员资料，管理收藏及观看状态。
- 视频播放、音轨与字幕选择、续播、章节与自动连播。
- 遥控器方向键导航，针对电视调整的深色界面。

客户端不提供影视资源、服务器或账号。播放能力取决于服务端权限、片源和电视解码能力。

## 构建

需要 JDK 17、Android SDK Platform 37.0（`platforms;android-37.0`）和 Build Tools 36.0.0。Gradle 9.6.0 通过仓库中的 Wrapper 固定，依赖版本见 [版本目录](gradle/libs.versions.toml)。

在 Android Studio 导入项目并配置 SDK，或通过 `ANDROID_HOME` / 本机 `local.properties` 指定 SDK 路径。Android TV 最低版本为 Android 7.0（API 24）。

```sh
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。Debug APK 位于 `app/build/outputs/apk/debug/`。

```sh
./gradlew :app:assembleRelease
```

未配置签名时生成未签名 Release；如需签名，将 [签名配置示例](keystore.properties.example) 复制为 `keystore.properties` 并填写自己的密钥。密钥、密码、本机配置和 APK 均不应提交到 Git。项目没有公开发布签名密钥。

### 音频解码

公开源码不附带 `media3-ffmpeg-decoder.aar`。Media3 使用电视平台解码器；电视不支持的音频格式可能无法播放，或需要服务端转码。完整私有基线中的 FFmpeg 音频软解兜底不属于这份公开源码的功能保证。详情见 [第三方资产说明](THIRD_PARTY_NOTICES.md)。

[Release](https://github.com/chiccheung7/Novum/releases/tag/v0.1.0-ad5fe33) 提供从原始 `ad5fe33` 构建的完整 APK，其中保留历史 FFmpeg 扩展。因此 Release APK 与这份不含 AAR 的源码构建产物在音频软解能力上存在差异；该扩展的完整构建来源仍未核实，不能将整个 APK 都视为 Apache-2.0 授权。各文件 SHA-256 和第三方说明随 Release 提供。

## 项目结构

| 模块 | 用途 |
| --- | --- |
| `app` | 应用入口、导航、登录和浏览页面 |
| `core/common` | 状态、错误、日志和公共工具 |
| `core/network` | Emby API、网络与会话连接 |
| `core/data` | 数据仓库、模型和偏好存储 |
| `core/designsystem` | TV 组件、主题、字体和焦点滚动 |
| `core/player` | 播放能力探测、音频与 Media3 支持 |
| `feature/home` | 首页 |
| `feature/detail` | 详情与选集 |
| `feature/player` | 播放界面与控制 |
| `core/testing` | 测试共用实现 |
| `benchmark` | 性能测试 |

## 参与与隐私

提交变更前请阅读 [贡献指南](CONTRIBUTING.md)。日志或截图中请删除服务器地址、账号、访问令牌和私人媒体信息；漏洞反馈见 [安全说明](SECURITY.md)。登录与媒体请求会发送到你自行填写的服务器；HTTP 连接可能以明文传输凭证，优先使用 HTTPS。

设备推装工具不参与 APK，也不是构建步骤；不要把真实设备地址或凭证放入源码或 Issue。

## 许可证

Novum 自有代码采用 [Apache License 2.0](LICENSE)。第三方字体、库和本地二进制保留各自许可，不被项目许可证重新授权；见 [NOTICE](NOTICE) 与 [第三方资产说明](THIRD_PARTY_NOTICES.md)。
