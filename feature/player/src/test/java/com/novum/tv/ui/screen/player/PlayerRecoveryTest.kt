package com.novum.tv.ui.screen.player

import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRecoveryTest {
    @Test
    fun directPlaybackWithoutFallbackRequestsTranscodeOnce() {
        listOf(PlayMethod.DirectPlay, PlayMethod.DirectStream).forEach { method ->
            val plan = plan(method)
            assertEquals(
                PlaybackRecoveryAction.RequestTranscode,
                resolvePlaybackRecovery(plan, fallbackUsed = false, transcodeRequested = false),
            )
            assertEquals(
                PlaybackRecoveryAction.ShowFailure,
                resolvePlaybackRecovery(plan, fallbackUsed = false, transcodeRequested = true),
            )
        }
    }

    @Test
    fun failedTranscodePlanShowsFailureEvenAfterUrlResetsAttemptFlags() {
        // 强转码返回新 URL，Stage 的 remember(plan.url) 标志会回到 false。
        // 该流已经是恢复链终点，不能因 URL 不同而再次请求同一种转码。
        val plan = plan(PlayMethod.Transcode).copy(url = "http://media/new-transcode-session")
        assertEquals(
            PlaybackRecoveryAction.ShowFailure,
            resolvePlaybackRecovery(plan, fallbackUsed = false, transcodeRequested = false),
        )
    }

    @Test
    fun providedTranscodeFallbackIsTriedBeforeFailureWithoutRequestingItAgain() {
        val plan = plan(PlayMethod.DirectPlay).copy(
            fallbackUrl = "http://media/fallback",
            fallbackMethod = PlayMethod.Transcode,
        )
        assertEquals(
            PlaybackRecoveryAction.UseFallback,
            resolvePlaybackRecovery(plan, fallbackUsed = false, transcodeRequested = false),
        )
        assertEquals(
            PlaybackRecoveryAction.ShowFailure,
            resolvePlaybackRecovery(plan, fallbackUsed = true, transcodeRequested = false),
        )
    }

    @Test
    fun fallbackWithoutMethodUsesTheSameTranscodeDefaultAsPlayer() {
        val plan = plan(PlayMethod.DirectPlay).copy(fallbackUrl = "http://media/fallback")
        assertEquals(
            PlaybackRecoveryAction.ShowFailure,
            resolvePlaybackRecovery(plan, fallbackUsed = true, transcodeRequested = false),
        )
    }

    @Test
    fun remuxFallbackCanStillAdvanceToTranscode() {
        val plan = plan(PlayMethod.DirectPlay).copy(
            fallbackUrl = "http://media/remux",
            fallbackMethod = PlayMethod.DirectStream,
        )
        assertEquals(
            PlaybackRecoveryAction.RequestTranscode,
            resolvePlaybackRecovery(plan, fallbackUsed = true, transcodeRequested = false),
        )
    }

    private fun plan(method: PlayMethod) = PlaybackPlan(
        url = "http://media/playback",
        method = method,
        audioTracks = emptyList(),
        subtitleTracks = emptyList(),
        subtitles = emptyList(),
        durationMs = 60_000,
        startPositionMs = 0,
    )
}
