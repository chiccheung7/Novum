package com.novum.tv.data.remote.emby

import com.novum.tv.core.capability.DeviceCapabilities
import com.novum.tv.testing.FakeCapabilityProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备能力声明测试。
 *
 * 关注点不是「字符串长什么样」，而是**声明必须跟随真实能力**：
 * 声明过头会导致选中杜比音轨却没声，声明不足会白白多一次转码。
 */
class DeviceProfileTest {

    private val tv = FakeCapabilityProvider()

    @Test
    fun declaresDolbyAndDtsWhenPassthroughEnabled() {
        val codecs = tv.audioCodecDeclaration(passthroughEnabled = true)

        assertTrue("开启直通应声明 ac3", codecs.contains("ac3"))
        assertTrue("开启直通应声明 eac3", codecs.contains("eac3"))
        assertTrue("开启直通应声明 dts", codecs.contains("dts"))
    }

    @Test
    fun omitsDolbyAndDtsWhenPassthroughDisabled() {
        val codecs = tv.audioCodecDeclaration(passthroughEnabled = false)

        assertFalse("关闭直通不应声明 ac3", codecs.contains("ac3"))
        assertFalse("关闭直通不应声明 eac3", codecs.contains("eac3"))
        assertFalse("关闭直通不应声明 dts", codecs.contains("dts"))
        assertTrue("基础编码仍要保留", codecs.contains("aac"))
    }

    @Test
    fun onlyDeclaresProbedPassthroughCodecs() {
        // 一台只支持 AC3 的老电视：不能因为「是电视」就把 DTS 也声明上去
        val oldTv = FakeCapabilityProvider(
            DeviceCapabilities(audioPassthrough = setOf("ac3"), videoCodecs = setOf("h264")),
        )

        val codecs = oldTv.audioCodecDeclaration(passthroughEnabled = true)

        assertTrue(codecs.contains("ac3"))
        assertFalse(codecs.contains("eac3"))
        assertFalse(codecs.contains("dts"))
    }

    @Test
    fun declaresProbedVideoCodecs() {
        assertEquals("h264,hevc,av1", tv.videoCodecDeclaration())
    }

    @Test
    fun fallsBackToH264WhenNoDecoderDetected() {
        val unknown = FakeCapabilityProvider(
            DeviceCapabilities(videoCodecs = emptySet()),
        )

        assertEquals("h264", unknown.videoCodecDeclaration())
    }

    @Test
    fun omitsDirectPlayProfilesWhenForcedTranscode() {
        val profile = DeviceProfileDto.forCapabilities(
            audioCodecs = "aac",
            videoCodecs = "h264",
            allowDirectPlay = false,
        )

        // 声明为空时服务端只能返回转码地址，这是「强制转码」的实现基础
        assertTrue(profile.DirectPlayProfiles.isEmpty())
    }

    @Test
    fun buildsDirectPlayProfileFromDeclarations() {
        val profile = DeviceProfileDto.forCapabilities(
            audioCodecs = "aac,ac3",
            videoCodecs = "h264,hevc",
        )

        val direct = profile.DirectPlayProfiles.single()
        assertEquals("aac,ac3", direct.AudioCodec)
        assertEquals("h264,hevc", direct.VideoCodec)
    }
}
