# Novum

Novum 是面向 Android TV 的 Emby 客户端。使用 Kotlin、Jetpack Compose for TV 和 Media3，供用户连接自己的服务器，在电视上浏览、搜索和播放媒体。

客户端不提供影片、服务器或账号。播放能力取决于 Emby 服务端、片源和设备解码能力。

## 获取与构建

当前版本 **0.2.4**：[GitHub Release 与 APK](https://github.com/chiccheung7/Novum/releases/tag/v0.2.4)。Release 说明列出本版功能、校验和与许可材料。

构建需要 JDK 17、Android SDK Platform 37.1 和 Build Tools 36.0.0。Gradle Wrapper 与依赖版本由仓库固定；Android TV 最低支持 Android 7.0（API 24）。在 Android Studio 配置 SDK，或在本机 `local.properties` 中设置 SDK 路径，然后执行：

```sh
./gradlew testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

Windows 使用 `gradlew.bat`。未配置本机签名时，Release APK 未签名。私有密钥和密码只存放在本机的 `keystore.properties`，不要提交密钥或构建生成的 APK。

## 可选 FFmpeg 音频扩展

源码不附带预编译 AAR。需要额外安装 NDK 26.3 与 CMake 3.22.1，按照 [FFmpeg 扩展说明](ffmpeg-decoder/README.md) 从源码构建后，将 AAR 放入 `app/libs/`。没有该扩展时，客户端使用设备平台解码器；不支持的音频格式由服务端转码。

## 项目与许可

应用入口位于 `app/`；共享数据、网络、播放器和设计组件位于 `core/`；主页面功能位于 `feature/`。自有代码采用 [Apache License 2.0](LICENSE)。字体与可选 FFmpeg 资产遵循其各自许可，见 [NOTICE](NOTICE) 与 [第三方资产说明](THIRD_PARTY_NOTICES.md)。

问题反馈前请阅读 [贡献指南](CONTRIBUTING.md)；安全问题见 [安全说明](SECURITY.md)。连接到用户填写的服务器时优先使用 HTTPS，日志中请移除账号、地址、令牌和私人媒体信息。
