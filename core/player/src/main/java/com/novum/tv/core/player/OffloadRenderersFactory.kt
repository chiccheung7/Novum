package com.novum.tv.core.player

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 音频 offload 适配的渲染器工厂。
 *
 * 背景：Media3 1.11 的音频 offload 没有播放器级一键开关——默认路径按格式自动
 * 协商（`DefaultAudioSink` 收到 `AudioOffloadSupportProvider` 的探测结果后决定
 * 走不走 DSP）。要**显式**控制只能覆写 [DefaultRenderersFactory.buildAudioSink]，
 * 在自定义 sink 上设 [AudioSink.setOffloadMode]。
 *
 * 策略（能力门控 + 失败回退）：
 * - 能力位 [offloadCapable]（= 压缩音频直通探测非空，见 CapabilityProvider）
 *   为 true → `OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED`：允许 DSP 处理
 *   压缩音频（省 CPU；gapless 非必需——单片播放场景）；
 * - 否则 → 默认构造（offload 关闭，走 PCM/直通既有路径）；
 * - 运行时回退由框架兜底：offload 初始化失败时 `DefaultAudioSink` 自动回落
 *   PCM 输出，播放不中断（试验性适配「失败即回退不留痕」的既定口径）。
 *
 * 电视通常持续供电，本适配主要用于提高个别编码直通的稳定性；
 * 将 [offloadCapable] 设为 false 即完全回退。
 *
 * @param context 应用上下文。
 * @param offloadCapable 设备是否具备压缩音频 DSP 处理能力（直通探测非空）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OffloadRenderersFactory(
    context: Context,
    private val offloadCapable: Boolean,
    /** 音频直通开关（false 时 AudioCapabilities 只声明 PCM，强制解码）。 */
    private val passthrough: Boolean = true,
    /** 音频延迟毫秒（音画同步微调，运行时变更经 configure 生效）。 */
    private val audioDelayMs: () -> Long = { 0L },
) : DefaultRenderersFactory(context) {

    init {
        // FFmpeg 扩展（app/libs AAR）：平台解不了的音频（TrueHD 等）软解兜底；
        // 平台解码器仍优先——扩展只做兜底，不抢硬解
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
        // 直通能力声明：开 = 平台探测的直通编码集；关 = 仅 PCM 强制解码。
        // DefaultAudioSink 没有显式 caps 时自行探测，这里用我们的探测结果覆盖，
        // 保证 DeviceProfile 协商声明与实际输出链路一致
        builder.setAudioCapabilities(
            if (passthrough) {
                AudioCapabilities.getCapabilities(context)
            } else {
                AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT), 2)
            },
        )
        // 音画同步微调：PCM 路径的延迟处理器（直通 bitstream 不经处理器）
        builder.setAudioProcessors(arrayOf(DelayAudioProcessor(audioDelayMs)))
        val sink = builder.build()
        if (sink != null && offloadCapable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // GAPLESS_NOT_REQUIRED：offload 在无 gapless 需求时最宽进入条件；
            // 单片播放、无拼接连播场景，禁用 gapless 约束换取更多格式走 DSP
            sink.setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED)
        }
        return requireNotNull(sink)
    }
}
