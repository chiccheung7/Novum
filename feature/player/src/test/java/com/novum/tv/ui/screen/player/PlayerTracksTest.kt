package com.novum.tv.ui.screen.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轨道记忆定位：按容器原始标题（rawLabel）+ 语言 + 声道数比对。
 *
 * 回归背景：内嵌 MKV 音轨/字幕轨通常没有 `Format.label`，旧实现拿「展示名 == Format.label」
 * 比对，这类轨的记忆永远恢复不了（展示名是语言本地名或带「· 5.1」后缀）。
 *
 * Robolectric 必需：`Format` 构造器会走 `TextUtils`（纯 JVM 未 mock，见 PlaybackSupportTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerTracksTest {

    @Test
    fun labellessEmbeddedAudioTrackMatchesByLanguageAndChannels() {
        // language 用 Media3 规范化后的值：TrackOption.language 取自 format.language（已规范化）
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("en")
            .setChannelCount(6)
            .build()
        val ref = ItemPlaybackPrefsStore.TrackRef(
            label = "English · 5.1",
            language = "en",
            rawLabel = "",
            channels = 6,
        )

        assertTrue("无标题内嵌音轨按语言 + 声道命中", trackMatchesRef(format, ref))
        assertFalse(
            "声道数不同（立体声 vs 5.1）不能误认成同一条轨",
            trackMatchesRef(format, ref.copy(channels = 2)),
        )
        assertFalse("语言不同不能命中", trackMatchesRef(format, ref.copy(language = "zh")))
    }

    @Test
    fun externalSubtitleWithLabelMatchesBothRecordShapes() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("zh")
            .setLabel("简体中文 (ASS)")
            .build()

        // 旧版记录只有展示名（rawLabel 为 null）：展示名 == Format.label 仍命中
        assertTrue(
            trackMatchesRef(
                format,
                ItemPlaybackPrefsStore.TrackRef(label = "简体中文 (ASS)", language = "zh"),
            ),
        )
        // 新版记录比对 rawLabel
        assertTrue(
            trackMatchesRef(
                format,
                ItemPlaybackPrefsStore.TrackRef(
                    label = "简体中文 (ASS)",
                    language = "zh",
                    rawLabel = "简体中文 (ASS)",
                ),
            ),
        )
        assertFalse(
            "标题不同不能命中",
            trackMatchesRef(
                format,
                ItemPlaybackPrefsStore.TrackRef(label = "x", language = "zh", rawLabel = "繁體中文"),
            ),
        )
    }

    @Test
    fun legacyDisplayNameRecordStillMissesLabellessTrack() {
        // 旧版记录（只有语言本地名）对无标题轨不命中——与此前行为一致，回落语言偏好
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setLanguage("eng").build()
        assertFalse(
            trackMatchesRef(format, ItemPlaybackPrefsStore.TrackRef(label = "英语", language = "eng")),
        )
    }
}
