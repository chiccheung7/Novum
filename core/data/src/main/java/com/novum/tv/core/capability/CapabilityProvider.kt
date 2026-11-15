package com.novum.tv.core.capability

/**
 * 设备能力快照。
 *
 * TV 端的解码与音频输出能力差异极大（同一颗 SoC 配不同面板 / 功放，可直通的编码就不同），
 * 因此所有「这台机器能不能播」的判断都集中到这里，播放器与设备能力声明只消费快照，
 * 不在业务代码里硬编码机型或编码列表。
 *
 * @param audioPassthrough 输出链路（HDMI / 功放）真正支持直通的音频编码，如 `ac3`、`eac3`、`dts`。
 * @param supportsAudioOffload 是否支持音频 offload（压缩音频交给 DSP，省 CPU 与功耗）。
 * @param videoCodecs 设备具备硬件解码器的视频编码，如 `h264`、`hevc`、`av1`。
 * @param supportsHdr 显示端是否支持 HDR（HDR10 / HLG / Dolby Vision 任一）。
 * @param supportsDolbyVision 显示端是否支持 Dolby Vision（双层 DV 还需解码器支持，见规划逻辑）。
 * @param isLowRam 系统是否判定为低内存设备，用于下调图片缓存与预加载。
 */
data class DeviceCapabilities(
    val audioPassthrough: Set<String> = emptySet(),
    val supportsAudioOffload: Boolean = false,
    val videoCodecs: Set<String> = setOf("h264"),
    val supportsHdr: Boolean = false,
    val supportsDolbyVision: Boolean = false,
    val isLowRam: Boolean = false,
)

/**
 * 设备能力提供者。
 *
 * 抽象成接口的目的：
 * - 单元测试可以注入固定能力，验证「能力 → 声明 / 播放策略」的映射；
 * - 未来开源时换设备只需换实现，不必改播放器与数据层。
 */
interface CapabilityProvider {

    /** 当前设备能力快照（实现应缓存，探测本身有 IO 成本）。 */
    val capabilities: DeviceCapabilities

    /**
     * 指定音频编码是否可直通。
     *
     * @param codec 小写编码名，如 `eac3`。
     */
    fun supportsPassthrough(codec: String): Boolean =
        codec.lowercase() in capabilities.audioPassthrough

    /**
     * 生成声明给服务端的音频编码列表。
     *
     * 关闭直通时只声明能软解的基础编码，服务端会把杜比 / DTS 转成 AAC；
     * 开启时也只声明**本机探测到**的编码，避免声明了却解不出导致无声。
     *
     * @param passthroughEnabled 用户设置里的「音频直通」开关。
     */
    fun audioCodecDeclaration(passthroughEnabled: Boolean): String {
        val base = BASE_AUDIO_CODECS.toMutableSet()
        if (passthroughEnabled) base += capabilities.audioPassthrough
        return base.joinToString(",")
    }

    /**
     * 生成声明给服务端的视频编码列表。
     *
     * 探测不到任何硬件解码器时退回 `h264`（所有设备都能软解）。
     */
    fun videoCodecDeclaration(failedCodecs: Set<String> = emptySet()): String =
        capabilities.videoCodecs
            .filter { it !in failedCodecs }
            .ifEmpty { setOf("h264") }
            .joinToString(",")

    companion object {
        /** 任何设备都能软解的基础音频编码。 */
        val BASE_AUDIO_CODECS = setOf("aac", "mp3", "flac", "opus")
    }
}
