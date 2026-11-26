package com.novum.tv.ui.screen.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.player.audioLabel
import com.novum.tv.core.player.subtitleLabel
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerGroupedTracksTest {
    @Test
    fun audioOptionsUseEachFormatsMetadataAndKeepTheRuntimeGroupIndex() {
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val tracks = Tracks(listOf(group("video", listOf(video)), group("audio", audioFormats, selected = 1)))

        val options = options(tracks, C.TRACK_TYPE_AUDIO)

        assertEquals(listOf("1:0", "1:1"), options.map { it.id })
        assertEquals(listOf("Stereo", "Surround"), options.map { it.rawLabel })
        assertEquals(listOf(2, 6), options.map { it.channels })
        assertEquals(listOf("MP4A-LATM", "AC-3"), options.map { it.codec })
        assertEquals(listOf(false, true), options.map { it.selected })
        assertEquals("Stereo", options[0].label)
        assertEquals("Surround · 6 channels", options[1].label)
    }

    @Test
    fun subtitleOptionsUseTheActualTrackFormat() {
        val tracks = Tracks(listOf(group("text", subtitleFormats, selected = 1)))

        val options = options(tracks, C.TRACK_TYPE_TEXT)

        assertEquals(listOf("普通字幕", "中英双语"), options.map { it.label })
        assertEquals(listOf("普通字幕", "中英双语"), options.map { it.rawLabel })
        assertEquals(listOf(false, true), options.map { it.selected })
        assertEquals(listOf(null, null), options.map { it.channels })
        assertEquals(listOf(null, null), options.map { it.codec })
    }

    @Test
    fun rememberingTheSecondAudioOptionRestoresItsActualIndex() {
        val audio = group("audio", audioFormats)
        val tracks = Tracks(listOf(audio))
        val option = options(tracks, C.TRACK_TYPE_AUDIO)[1]
        val player = FakePlayer(tracks)

        applyRememberedAudio(
            player.instance,
            ItemPlaybackPrefsStore.ItemPrefs(itemId = "episode", audioTrack = option.trackRef()),
        )

        assertEquals(listOf(1), player.parameters.overrides[audio.mediaTrackGroup]?.trackIndices)
    }

    @Test
    fun rememberingTheSecondSubtitleRestoresItAndEnablesText() {
        val text = group("text", subtitleFormats)
        val tracks = Tracks(listOf(text))
        val option = options(tracks, C.TRACK_TYPE_TEXT)[1]
        val player = FakePlayer(tracks).apply {
            parameters = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }

        val restored = applyRememberedSubtitle(
            player.instance,
            ItemPlaybackPrefsStore.ItemPrefs(itemId = "episode", subtitleTrack = option.trackRef()),
        )

        assertTrue(restored)
        assertEquals(listOf(1), player.parameters.overrides[text.mediaTrackGroup]?.trackIndices)
        assertFalse(C.TRACK_TYPE_TEXT in player.parameters.disabledTrackTypes)
    }

    @Test
    fun dualSubtitlePreferenceCanFindASecondTrackInTheGroup() {
        val text = group("text", subtitleFormats)
        val player = FakePlayer(Tracks(listOf(text)))

        applyDualSubtitlePreference(player.instance)

        assertEquals(listOf(1), player.parameters.overrides[text.mediaTrackGroup]?.trackIndices)
    }

    @Test
    fun alreadySelectedSecondTrackDoesNotCreateAnOverrideLoop() {
        val audio = group("audio", audioFormats, selected = 1)
        val tracks = Tracks(listOf(audio))
        val player = FakePlayer(tracks)
        val option = options(tracks, C.TRACK_TYPE_AUDIO)[1]

        applyRememberedAudio(
            player.instance,
            ItemPlaybackPrefsStore.ItemPrefs(itemId = "episode", audioTrack = option.trackRef()),
        )

        assertEquals(0, player.parameterWrites)
    }

    @Test
    fun staleTrackIndicesAndChangedGroupTypesAreIgnored() {
        val audio = group("audio", audioFormats)
        val text = group("text", subtitleFormats)
        val player = FakePlayer(Tracks(listOf(audio, text)))

        // fallback/换版本后轨道组可能缩短或重排，旧按钮不得构造越界 override 或钉错类型。
        listOf("0:2", "0:-1", "2:0", "1:0").forEach { id ->
            selectTrack(player.instance, TrackOption(id, "old option", selected = false), isText = false)
        }

        assertEquals(0, player.parameterWrites)
        selectTrack(player.instance, TrackOption("0:1", "Surround", selected = false), isText = false)
        assertEquals(listOf(1), player.parameters.overrides[audio.mediaTrackGroup]?.trackIndices)
    }

    @Test
    fun currentLabelsUseTheSelectedIndexInsideEachGroup() {
        val player = FakePlayer(
            Tracks(
                listOf(
                    group("audio", audioFormats, selected = 1),
                    group("text", subtitleFormats, selected = 1),
                ),
            ),
        )

        assertEquals("Surround", player.instance.audioLabel())
        assertEquals("中英双语", player.instance.subtitleLabel())
    }

    private fun options(tracks: Tracks, type: Int) = buildTrackOptions(tracks, type, "Audio", "Text", "%d channels")

    private fun TrackOption.trackRef() = ItemPlaybackPrefsStore.TrackRef(
        label = label,
        language = language,
        rawLabel = rawLabel,
        channels = channels,
    )

    private fun group(id: String, formats: List<Format>, selected: Int = 0) = Tracks.Group(
        TrackGroup(id, *formats.toTypedArray()),
        false,
        IntArray(formats.size) { C.FORMAT_HANDLED },
        BooleanArray(formats.size) { it == selected },
    )

    private val audioFormats = listOf(
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("en").setLabel("Stereo").setChannelCount(2).build(),
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setLanguage("en").setLabel("Surround").setChannelCount(6).build(),
    )

    private val subtitleFormats = listOf(
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en").setLabel("普通字幕").build(),
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en").setLabel("中英双语").build(),
    )

    /** 使用真实 Tracks/参数对象，只替身 Player 访问，不连接媒体地址或启动解码器。 */
    private class FakePlayer(tracks: Tracks) {
        var parameters = TrackSelectionParameters.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        var parameterWrites = 0
        val instance: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getCurrentTracks" -> tracks
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> {
                    parameterWrites++
                    parameters = arguments!![0] as TrackSelectionParameters
                    null
                }

                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player
    }
}
