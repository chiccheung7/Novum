#!/usr/bin/env bash
#
# 从源码编译 Media3 FFmpeg 解码扩展 AAR，并复制到 ../app/libs/。
#
# 这是本项目 Apache-2.0 自有代码之外的可选第三方组件：FFmpeg 及其静态库
# 遵循 FFmpeg 自身许可（见 LICENSE_FFMPEG），本脚本只负责可复现构建，不改变
# 其授权。启用解码器见下方 ENABLED_DECODERS，与 THIRD_PARTY_NOTICES.md 对齐。
#
# 前置条件：
#   - JDK 17（与 novum 根工程一致）
#   - Android SDK + NDK r26d（脚本默认 26.3.11579264）+ CMake 3.22.1
#   - ANDROID_HOME / ANDROID_SDK_ROOT 指向 SDK 根目录
#
# 用法（在仓库根目录执行）：
#   bash ffmpeg-decoder/build.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JNI="$HERE/decoder/src/main/jni"
FFMPEG_DIR="$JNI/ffmpeg"

# ---- 可配置项 ---------------------------------------------------------------
FFMPEG_VERSION="6.0"                       # 官方推荐版本
FFMPEG_TAG="release/${FFMPEG_VERSION}"
NDK_VERSION="${NDK_VERSION:-26.3.11579264}"
if [ -z "${HOST_PLATFORM:-}" ]; then
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST_PLATFORM="windows-x86_64" ;;
    Darwin*) HOST_PLATFORM="darwin-x86_64" ;;
    *) HOST_PLATFORM="linux-x86_64" ;;
  esac
fi
ANDROID_ABI="${ANDROID_ABI:-24}"           # 必须 <= 宿主 app 的 minSdk(24)
ENABLED_DECODERS=(ac3 eac3 truehd dca)     # AC3/E-AC3/TrueHD/DTS/DTS-HD
# -----------------------------------------------------------------------------

echo "==> 0/4 环境检查"
command -v java >/dev/null || { echo "缺少 JDK"; exit 1; }
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME" ] || { echo "请设置 ANDROID_HOME/ANDROID_SDK_ROOT"; exit 1; }
NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
[ -d "$NDK_PATH" ] || {
  echo "缺少 NDK $NDK_VERSION，请先安装："
  echo "  \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \"ndk;$NDK_VERSION\" \"cmake;3.22.1\""
  exit 1
}
echo "    ANDROID_HOME=$ANDROID_HOME"
echo "    NDK_PATH=$NDK_PATH"

echo "==> 1/4 获取 FFmpeg $FFMPEG_VERSION 源码"
if [ ! -f "$FFMPEG_DIR/configure" ]; then
  rm -rf "$FFMPEG_DIR"
  mkdir -p "$FFMPEG_DIR"
  curl -sSL "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/heads/$FFMPEG_TAG" \
    | tar -xz -C "$FFMPEG_DIR" --strip-components=1
else
  echo "    已存在，跳过下载"
fi

echo "==> 2/4 编译 FFmpeg 静态库（${ENABLED_DECODERS[*]} × 4 ABI）"
if [ ! -f "$FFMPEG_DIR/android-libs/x86_64/libavcodec.a" ]; then
  ( cd "$JNI" && ./build_ffmpeg.sh \
      "$JNI/.." "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_ABI" \
      "${ENABLED_DECODERS[@]}" )
else
  echo "    静态库已存在，跳过（如需重编请先删除 $FFMPEG_DIR/android-libs）"
fi

echo "==> 3/4 编译 AAR（复用 novum 根工程的 Gradle wrapper）"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
( cd "$HERE/.." && ./gradlew -p ffmpeg-decoder :decoder:assembleRelease \
    -PnovumNdkVersion="$NDK_VERSION" --no-daemon )

echo "==> 4/4 复制 AAR 到 app/libs"
AAR="$HERE/decoder/build/outputs/aar/decoder-release.aar"
[ -f "$AAR" ] || { echo "找不到 AAR：$AAR"; exit 1; }
mkdir -p "$HERE/../app/libs"
cp "$AAR" "$HERE/../app/libs/media3-ffmpeg-decoder.aar"
sha256sum "$HERE/../app/libs/media3-ffmpeg-decoder.aar"
echo "==> 完成。现在回到仓库根目录执行：./gradlew :app:assembleDebug"
