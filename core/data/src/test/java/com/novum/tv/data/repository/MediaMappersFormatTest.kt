package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.testing.FakeMediaStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 格式徽章口径：动态范围标签必须与流的真实规格一致。
 *
 * 回归背景：HLG 是 HDR 的一种，旧实现落到 else 分支把它标成「SDR」，
 * Hero/详情/版本选项上出现「4K · HLG 片源 · SDR 徽章」的自相矛盾。
 */
class MediaMappersFormatTest {

    private fun entry(streams: List<MediaStreamDto>) =
        ItemDto(Id = "m-1", Name = "片名", Type = "Movie", MediaStreams = streams)
            .toHomeEntry("https://smart.uhdnow.com", FakeMediaStrings)!!

    @Test
    fun hlgStreamIsLabeledHlg() {
        val e = entry(listOf(MediaStreamDto(Type = "Video", Codec = "hevc", VideoRangeType = "HLG", Height = 2160)))
        assertEquals(listOf("HLG"), e.videoFormats)
    }

    @Test
    fun legacyVideoRangeHlgIsLabeledHlg() {
        val e = entry(listOf(MediaStreamDto(Type = "Video", Codec = "hevc", VideoRange = "HLG")))
        assertEquals(listOf("HLG"), e.videoFormats)
    }

    @Test
    fun dolbyVisionAndHdr10Unchanged() {
        val dv = entry(listOf(MediaStreamDto(Type = "Video", VideoRangeType = "DOVIWithHDR10")))
        assertEquals(listOf("Dolby Vision"), dv.videoFormats)
        val hdr = entry(listOf(MediaStreamDto(Type = "Video", VideoRange = "HDR")))
        assertEquals(listOf("HDR10"), hdr.videoFormats)
    }

    @Test
    fun plainStreamIsSdrAndNoStreamProducesNoBadge() {
        val sdr = entry(listOf(MediaStreamDto(Type = "Video", VideoRange = "SDR")))
        assertEquals(listOf("SDR"), sdr.videoFormats)
        val none = entry(emptyList())
        assertEquals(emptyList<String>(), none.videoFormats)
    }
}
