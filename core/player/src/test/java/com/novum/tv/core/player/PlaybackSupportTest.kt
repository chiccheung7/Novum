package com.novum.tv.core.player

import androidx.media3.common.C
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.model.SubtitleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PlaybackSupport 媒体组装分支测试。
 *
 * 只覆盖不依赖播放器实例的纯函数：`buildMediaItem` 的「地址/字幕装配 +
 * 首条默认旗标」两处分支。轨道读写（audioLabel/subtitleLabel/cycle*）需要
 * 真实 Player 实例不在此测试。
 *
 * Robolectric 是必需的而不是可选的：`MediaItem.Builder.setUri(String)` 内部
 * 走 `android.net.Uri.parse`——纯 JVM 单测会抛 "not mocked"（项目未开
 * `returnDefaultValues`，这是刻意的：Android API 误用要炸出来）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackSupportTest {

    private fun plan(subtitles: List<SubtitleSource>) = PlaybackPlan(
        url = "http://host:8096/Videos/1/stream",
        method = PlayMethod.DirectPlay,
        audioTracks = emptyList(),
        subtitleTracks = emptyList(),
        subtitles = subtitles,
        durationMs = 60_000L,
        startPositionMs = 0L,
    )

    private fun subtitle(id: String) = SubtitleSource(
        id = id,
        url = "http://host:8096/Videos/1/Subtitles/$id/Stream.srt",
        language = "zh",
        label = "简体中文",
        mimeType = "application/x-subrip",
    )

    @Test
    fun mediaItemCarriesUrlAsUriAndMediaId() {
        val item = buildMediaItem(plan(emptyList()), subtitlesEnabled = true)
        assertEquals("http://host:8096/Videos/1/stream", item.mediaId)
        assertEquals("http://host:8096/Videos/1/stream", item.localConfiguration?.uri?.toString())
    }

    @Test
    fun noSubtitlesAttachNoConfigurations() {
        val item = buildMediaItem(plan(emptyList()), subtitlesEnabled = true)
        assertTrue(item.localConfiguration?.subtitleConfigurations.isNullOrEmpty())
    }

    @Test
    fun subtitlesCarryIdLanguageLabelAndMimeType() {
        val item = buildMediaItem(plan(listOf(subtitle("s1"))), subtitlesEnabled = false)
        val configs = item.localConfiguration?.subtitleConfigurations.orEmpty()
        assertEquals(1, configs.size)
        assertEquals("s1", configs[0].id)
        assertEquals("zh", configs[0].language)
        assertEquals("简体中文", configs[0].label)
        assertEquals("application/x-subrip", configs[0].mimeType)
    }

    @Test
    fun audioOutputPassthroughOffIsAlwaysPcm() {
        // 直通关闭：无论音轨/设备能力，一律 PCM（延迟处理器生效）
        assertEquals(
            "pcm",
            audioOutputLabel("audio/ac3", false, true, "pcm", "bitstream", "fallback"),
        )
    }

    @Test
    fun audioOutputCompressedWithDeviceSupportIsBitstream() {
        assertEquals(
            "bitstream",
            audioOutputLabel("audio/truehd", true, true, "pcm", "bitstream", "fallback"),
        )
    }

    @Test
    fun audioOutputCompressedWithoutDeviceSupportFallsBackToPcm() {
        assertEquals(
            "fallback",
            audioOutputLabel("audio/eac3", true, false, "pcm", "bitstream", "fallback"),
        )
    }

    @Test
    fun audioOutputPcmTrackStaysPcmEvenWhenPassthroughOn() {
        assertEquals(
            "pcm",
            audioOutputLabel("audio/mp4a-latm", true, true, "pcm", "bitstream", "fallback"),
        )
    }

    @Test
    fun onlyFirstSubtitleIsMarkedDefaultWhenEnabled() {
        val on = buildMediaItem(plan(listOf(subtitle("s1"), subtitle("s2"))), subtitlesEnabled = true)
            .localConfiguration?.subtitleConfigurations.orEmpty()
        assertEquals(2, on.size)
        assertEquals(C.SELECTION_FLAG_DEFAULT, on[0].selectionFlags)
        assertEquals(0, on[1].selectionFlags)

        val off = buildMediaItem(plan(listOf(subtitle("s1"), subtitle("s2"))), subtitlesEnabled = false)
            .localConfiguration?.subtitleConfigurations.orEmpty()
        assertEquals(2, off.size)
        assertEquals(0, off[0].selectionFlags)
        assertEquals(0, off[1].selectionFlags)
    }
}
