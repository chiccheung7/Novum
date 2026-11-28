package com.novum.tv

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证宿主 APK 实际打包并能加载当前设备 ABI 的 FFmpeg JNI。 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FfmpegPackagingTest {
    @Test
    fun packagedNativeLibraryLoadsAndRegistersEnabledAudioDecoders() {
        // 公开源码允许不包含本地可选 AAR；用反射避免测试源码也强制依赖它。
        val library = try {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        } catch (_: ClassNotFoundException) {
            assumeTrue("未启用可选 FFmpeg 扩展", false)
            return
        }
        assertTrue("FFmpeg JNI 加载失败", library.getMethod("isAvailable").invoke(null) as Boolean)
        val version = library.getMethod("getVersion").invoke(null) as String?
        assertTrue("未返回 FFmpeg 版本", !version.isNullOrBlank())
        val padding = library.getMethod("getInputBufferPaddingSize").invoke(null) as Int
        assertTrue("无效的输入缓冲 padding", padding > 0)
        listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_TRUEHD,
        ).forEach { mime ->
            val supported = library.getMethod("supportsFormat", String::class.java).invoke(null, mime) as Boolean
            assertTrue("缺少音频解码器：$mime", supported)
        }
    }
}
