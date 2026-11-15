package com.novum.tv.testing

import com.novum.tv.core.capability.CapabilityProvider
import com.novum.tv.core.capability.DeviceCapabilities

/**
 * 固定能力的测试替身。
 *
 * 默认值模拟一台主流 TV：支持杜比 / DTS 直通、h264 + hevc 硬解、有 HDR。
 *
 * @param capabilities 需要覆盖的能力快照。
 */
class FakeCapabilityProvider(
    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        audioPassthrough = setOf("ac3", "eac3", "dts"),
        supportsAudioOffload = true,
        videoCodecs = setOf("h264", "hevc", "av1"),
        supportsHdr = true,
    ),
) : CapabilityProvider
