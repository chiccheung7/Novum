package com.novum.tv.ui.screen.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.model.PlayMethod
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerTimelineTest {
    @Test
    fun progressiveTranscodeUsesOneOffsetForResumeProgressBufferAndDuration() {
        val plan = plan(offset = 60_000)
        val player = player(plan, position = 2_000, buffered = 12_000, localDuration = 3_540_000)
        assertEquals(0L, localPlaybackPosition(plan, 60_000))
        assertEquals(2_000L, localPlaybackPosition(plan, 62_000))
        assertEquals(62_000L, player.mediaPositionMs())
        assertEquals(72_000L, player.mediaBufferedPositionMs())
        assertEquals(3_600_000L, player.mediaDurationMs())
    }

    @Test
    fun hlsAndDirectPlayKeepTheirFullTimeline() {
        val plan = plan(offset = 0)
        val player = player(plan, position = 62_000, buffered = 72_000, localDuration = 3_600_000)
        assertEquals(62_000L, localPlaybackPosition(plan, 62_000))
        assertEquals(62_000L, player.mediaPositionMs())
        assertEquals(72_000L, player.mediaBufferedPositionMs())
    }

    @Test
    fun seekBeforeProgressiveOriginRebuildsWhileInRangeSeekConvertsToLocalTime() {
        var seek: Long? = null
        var rebuild: Long? = null
        val player = player(plan(60_000), onSeek = { seek = it })
        player.seekToMediaPosition(45_000) { rebuild = it }
        assertEquals(45_000L, rebuild)
        assertNull(seek)
        rebuild = null
        player.seekToMediaPosition(65_000) { rebuild = it }
        assertEquals(5_000L, seek)
        assertNull(rebuild)
    }

    @Test
    fun unknownDurationDoesNotInventACompleteRuntimeAndMissingRuntimeUsesOffset() {
        assertEquals(0L, player(plan(60_000).copy(durationMs = null), localDuration = C.TIME_UNSET).mediaDurationMs())
        assertEquals(90_000L, player(plan(60_000).copy(durationMs = null), localDuration = 30_000).mediaDurationMs())
    }

    private fun plan(offset: Long) = PlaybackPlan(
        "https://example.test/stream", PlayMethod.Transcode, emptyList(), emptyList(), emptyList(),
        3_600_000, 60_000, streamStartPositionMs = offset,
    )

    private fun player(
        plan: PlaybackPlan, position: Long = 0, buffered: Long = 0, localDuration: Long = 0,
        onSeek: (Long) -> Unit = {},
    ): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getCurrentMediaItem" -> MediaItem.Builder().setUri(plan.url).setTag(plan).build()
            "getCurrentPosition" -> position
            "getBufferedPosition" -> buffered
            "getDuration" -> localDuration
            "seekTo" -> { onSeek(args!![0] as Long); null }
            else -> error("Unexpected Player call: ${method.name}")
        }
    } as Player
}
