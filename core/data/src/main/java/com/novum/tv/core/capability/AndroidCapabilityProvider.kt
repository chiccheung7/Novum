package com.novum.tv.core.capability

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.novum.tv.core.data.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于系统 API 的能力探测实现。
 *
 * 探测策略（全部走公开 API，不依赖机型白名单）：
 * - **音频直通**：API 29+ 用 `AudioTrack.isDirectPlaybackSupported` 逐个编码问输出链路；
 *   低版本无法可靠判断，一律视为不支持（宁可让服务端转 AAC，也不要出现「选中杜比却没声」）。
 * - **音频 offload**：Media3 1.11 起没有显式开关，改为按格式自动协商；
 *   这里用「压缩音频能否直通」作为能力标记，供界面展示与排查。
 * - **视频解码**：枚举 `MediaCodecList` 的硬件解码器，映射成 Emby 认识的编码名。
 * - **HDR**：读默认显示器的 `HdrCapabilities`。
 *
 * 结果在首次访问时计算并缓存：探测会枚举编解码器，属于几十毫秒级开销，
 * 不能放在每次播放的路径上。
 *
 * @param context 应用上下文。
 */
@Singleton
class AndroidCapabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CapabilityProvider {

    override val capabilities: DeviceCapabilities by lazy {
        detect().also { caps ->
            // 能力直接决定「直连还是转码」，出问题时第一现场就是这条日志
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "NovumCapability",
                    "audioPassthrough=${caps.audioPassthrough} video=${caps.videoCodecs} " +
                        "hdr=${caps.supportsHdr} dv=${caps.supportsDolbyVision} lowRam=${caps.isLowRam}",
                )
            }
        }
    }

    /** 一次性探测全部能力。 */
    private fun detect(): DeviceCapabilities {
        val passthrough = detectPassthrough()
        return DeviceCapabilities(
            audioPassthrough = passthrough,
            supportsAudioOffload = passthrough.isNotEmpty(),
            videoCodecs = detectVideoCodecs(),
            supportsHdr = detectHdr(),
            supportsDolbyVision = detectDolbyVision(),
            isLowRam = runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
            }.getOrDefault(false),
        )
    }

    /**
     * 探测可直通的音频编码。
     *
     * 用 5.1 / 48kHz 的格式去问，是因为立体声探测会漏掉只支持多声道直通的设备。
     */
    @Suppress("DEPRECATION")
    private fun detectPassthrough(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val supported = linkedSetOf<String>()
        PASSTHROUGH_ENCODINGS.forEach { (name, encoding) ->
            val ok = runCatching {
                AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(PROBE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .build(),
                    DIRECT_PLAYBACK_ATTRIBUTES,
                )
            }.getOrDefault(false)
            if (ok) supported += name
        }
        return supported
    }

    /** 枚举硬件解码器，映射为 Emby 使用的编码名。 */
    private fun detectVideoCodecs(): Set<String> {
        val codecs = linkedSetOf<String>()
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder) return@forEach
                if (!info.isHardwareDecoder()) return@forEach
                info.supportedTypes.forEach { type ->
                    VIDEO_MIME_TO_CODEC[type.lowercase()]?.let(codecs::add)
                }
            }
        }
        return codecs.ifEmpty { setOf("h264") }
    }

    /** 读显示器 HDR 能力；设备没有 HDR 时返回 false。 */
    @Suppress("DEPRECATION")
    private fun detectHdr(): Boolean = runCatching {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return@runCatching false
        display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }.getOrDefault(false)

    /** 显示端是否支持 Dolby Vision；不支持时 DV 片源一律转码。 */
    @Suppress("DEPRECATION")
    private fun detectDolbyVision(): Boolean = runCatching {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return@runCatching false
        display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
    }.getOrDefault(false)

    private companion object {
        /** 探测用的采样率：电视输出链路几乎都支持 48kHz。 */
        const val PROBE_SAMPLE_RATE = 48_000

        /** 直通探测使用的音频属性：媒体播放、电影内容。 */
        val DIRECT_PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        /**
         * 需要探测的直通编码。
         *
         * 只探测压缩格式：PCM 类编码走解码器即可，不涉及「功放解不解得开」的问题。
         * `dtshd` 用 DTS 的名义声明，服务端与功放都按 DTS 家族处理。
         */
        val PASSTHROUGH_ENCODINGS: List<Pair<String, Int>> = listOf(
            "ac3" to AudioFormat.ENCODING_AC3,
            "eac3" to AudioFormat.ENCODING_E_AC3,
            // minSdk 24 起 ENCODING_DTS 恒定可用，无需再判版本
            "dts" to AudioFormat.ENCODING_DTS,
        )

        /** MIME → Emby 编码名。 */
        val VIDEO_MIME_TO_CODEC = mapOf(
            "video/avc" to "h264",
            "video/hevc" to "hevc",
            "video/x-vnd.on2.vp9" to "vp9",
            "video/av01" to "av1",
            "video/mp4v-es" to "mpeg4",
        )
    }
}

/**
 * 是否为硬件解码器。
 *
 * API 29+ 有官方标记；更低版本只能按名称排除软件实现（Google 的软解名字里带 google / sw）。
 */
private fun MediaCodecInfo.isHardwareDecoder(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        val lower = name.lowercase()
        !lower.contains("google") && !lower.contains("software") && !lower.contains("sw.")
    }

/** 设备是否声明了电视界面（用于区分 TV 与手机，目前仅日志与调试用）。 */
internal fun Context.hasTvUi(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature("android.hardware.type.television")
