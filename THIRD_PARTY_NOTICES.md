# 第三方资产

项目根目录的 Apache-2.0 许可证只用于 Novum 自有代码，第三方内容继续适用原许可。

## Inter 字体

文件：`core/designsystem/src/main/res/font/inter_regular.otf`、`inter_medium.otf`、`inter_semibold.otf`。

三份字体的 name 表均标明 Inter 3.019（`git-0a5106e0b`），Copyright 2020 The Inter Project Authors，SIL Open Font License 1.1。完整许可证见 [INTER-OFL.txt](core/designsystem/INTER-OFL.txt)，上游为 [rsms/inter v3.19](https://github.com/rsms/inter/tree/v3.19)。字体不适用 Apache-2.0。

私有仓库原有的 `HARMONYOS-FONT-LICENSE.txt` 是历史字体资源遗留；公开源码快照已排除该无关文件，现有 Inter 使用上面的 OFL 许可。

## FFmpeg 音频解码扩展：可重建源码与历史 Release 的差异

当前源码在 `ffmpeg-decoder/` 收录了 Media3 FFmpeg 扩展的可重建工程：Media3 1.11.1 的 Java/JNI 包装层源码、FFmpeg 6.0 静态库编译脚本和一键构建脚本。构建产物 `app/libs/media3-ffmpeg-decoder.aar` 不入库；放入该路径后由 App 的 `OffloadRenderersFactory` 自动启用。未执行构建时，公开版只依赖电视平台解码器。构建脚本、启用的解码器和许可边界见 [扩展说明](ffmpeg-decoder/README.md)。

v0.2.6 的发布 APK 使用从源码重建的扩展，FFmpeg 源码快照标识为 6.0.2（`release/6.0`），启用 `ac3`、`eac3`、`truehd`、`dca` 解码器；构建使用 NDK `26.3.11579264`、CMake `3.22.1`，四个 ABI 的 JNI 均显式采用 16 KB ELF 段对齐。对应 FFmpeg 源码快照随该版本 Release 以 `Novum-0.2.6-ffmpeg-source.tar.gz` 提供，包装层及完整构建脚本位于同一 Release 标签的 `ffmpeg-decoder/`；发布文件校验值见 Release 的 `SHA256SUMS`。

历史私有基线曾包含 `app/libs/media3-ffmpeg-decoder.aar`，但当时无法定位其完整源码、构建配置和许可材料；公开源码快照未包含该文件，也不包含带有它的旧 Git 历史。维护者同时发布了原始 `ad5fe33` 的完整 APK，其中保留该历史扩展。历史 AAR 的 SHA-256 为 `1E094BEB7DFB709C262F5B7CC5B1F8A2EFFD1C6415446B1204F32866AF9E216E`；该哈希仅对应旧产物（Java 包 `androidx.media3.decoder.ffmpeg`，四种 ABI 含 `libffmpegJNI.so`），不代表按当前脚本重建出的 AAR。

Java/JNI 包装层来自 AndroidX Media3，适用 Apache-2.0；按当前脚本编译的 FFmpeg 静态库未启用 `--enable-gpl` 或 `--enable-nonfree`，适用 LGPL 2.1+，条款见 [FFmpeg 许可文本](ffmpeg-decoder/LICENSE_FFMPEG_LGPLv2.1.txt)。不能把包装层的 Apache-2.0 许可直接套用于 FFmpeg 二进制或整个 AAR；分发含该 AAR 的 APK 时须遵守 LGPL 的源码提供与许可声明要求。历史 Release APK 中 FFmpeg 的实际构建配置仍未核实，不能依据当前脚本反推其许可证，也不能把整个 APK 视为 Apache-2.0 授权。参考 [Media3 FFmpeg 扩展文档](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md) 与 [FFmpeg 许可说明](https://ffmpeg.org/legal.html)。

引入或升级此类扩展时，应同步包装层与 App 的 Media3 版本，核对 FFmpeg 版本、补丁、NDK/编译配置和许可材料，并实测音频回退能力。

## Maven 依赖

AndroidX、Kotlin、Media3、Hilt、Retrofit、OkHttp、Coil 和其他通过 Gradle 获取的依赖均保持原有许可。精确版本见 [版本目录](gradle/libs.versions.toml)，完整依赖树可用以下命令生成：

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

发布 APK 时还需要保留这些依赖要求的版权及许可材料；本文件不是依赖许可证的替代品，也不代表 FFmpeg AAR 的分发核验已完成。
