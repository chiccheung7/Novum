# 第三方资产

项目根目录的 Apache-2.0 许可证只用于 Novum 自有代码，第三方内容继续适用原许可。

## Inter 字体

文件：`core/designsystem/src/main/res/font/inter_regular.otf`、`inter_medium.otf`、`inter_semibold.otf`。

三份字体的 name 表均标明 Inter 3.019（`git-0a5106e0b`），Copyright 2020 The Inter Project Authors，SIL Open Font License 1.1。完整许可证见 [INTER-OFL.txt](core/designsystem/INTER-OFL.txt)，上游为 [rsms/inter v3.19](https://github.com/rsms/inter/tree/v3.19)。字体不适用 Apache-2.0。

私有仓库原有的 `HARMONYOS-FONT-LICENSE.txt` 是历史字体资源遗留；公开源码快照已排除该无关文件，现有 Inter 使用上面的 OFL 许可。

## FFmpeg 音频解码扩展：源码快照与 Release 的差异

私有基线包含 `app/libs/media3-ffmpeg-decoder.aar`，但无法定位它的完整源码、构建配置和许可材料。公开源码快照不包含该文件，也不包含带有它的旧 Git 历史。维护者选择同时发布原始 `ad5fe33` 的完整 APK；该 Release APK 保留此扩展，不能用本项目 Apache-2.0 许可证重新授权其中的 FFmpeg 二进制。

AAR SHA-256：`1E094BEB7DFB709C262F5B7CC5B1F8A2EFFD1C6415446B1204F32866AF9E216E`。本地 AAR 的 Java 包为 `androidx.media3.decoder.ffmpeg`，四种 ABI 含 `libffmpegJNI.so`。提供的 FFmpegKit 总仓库尚未与此产物建立具体版本或构建关系；目前没有声称完整源码和构建材料已经提供。

不能把 AndroidX Media3 Java/JNI 包装层的 Apache-2.0 许可直接套用于 FFmpeg 及整个 AAR。FFmpeg 的适用许可取决于实际源码和构建配置；当前缺少依据，不能确认该产物的完整许可与再分发条件。参考 [Media3 FFmpeg 扩展文档](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md) 与 [FFmpeg 许可说明](https://ffmpeg.org/legal.html)。

以后引入此类扩展时，需要提供源码版本、补丁、NDK/编译配置、可复现构建脚本和适用许可材料，再测试音频回退能力。公开版目前依赖平台解码器，不能保证私有基线中由扩展提供的音频软解能力。

## Maven 依赖

AndroidX、Kotlin、Media3、Hilt、Retrofit、OkHttp、Coil 和其他通过 Gradle 获取的依赖均保持原有许可。精确版本见 [版本目录](gradle/libs.versions.toml)，完整依赖树可用以下命令生成：

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

发布 APK 时还需要保留这些依赖要求的版权及许可材料；本文件不是依赖许可证的替代品，也不代表 FFmpeg AAR 的分发核验已完成。
