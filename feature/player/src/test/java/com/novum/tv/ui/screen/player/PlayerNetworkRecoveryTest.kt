package com.novum.tv.ui.screen.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.novum.tv.core.error.NovumError
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerNetworkRecoveryTest {
    @Test
    fun aFirstGatewayFailureWhileResumingRefreshesPlaybackInsteadOfRequestingTranscode() {
        val failure = gatewayFailure(502)
        val error = playbackNetworkError(failure)
        assertEquals(NovumError.Server(502), error)
        assertEquals(PlaybackRecoveryAction.RetryPlayback, resolvePlaybackRecovery(
            plan(), fallbackUsed = false, transcodeRequested = false, networkError = error,
        ))
    }

    @Test
    fun repeatedGatewayFailuresStopEvenIfRefreshingReturnedAnotherSignedUrl() {
        for (method in PlayMethod.entries) {
            for (code in listOf(500, 502, 503, 504)) {
                val refreshed = plan().copy(url = "https://media.example/refreshed", method = method,
                    fallbackUrl = "https://media.example/transcode", fallbackMethod = PlayMethod.Transcode)
                assertEquals(PlaybackRecoveryAction.ShowFailure, resolvePlaybackRecovery(
                    refreshed, fallbackUsed = false, transcodeRequested = false,
                    networkError = playbackNetworkError(gatewayFailure(code)), networkRetryUsed = true,
                ))
            }
        }
    }

    @Test
    fun aTranscodedStreamStillGetsOnlyOneNetworkRefreshWithoutChangingTheRecoveryMethod() {
        val transcode = plan().copy(method = PlayMethod.Transcode)
        val error = NovumError.Network(SocketTimeoutException())
        assertEquals(PlaybackRecoveryAction.RetryPlayback, resolvePlaybackRecovery(
            transcode, fallbackUsed = false, transcodeRequested = true, networkError = error,
        ))
        assertEquals(PlaybackRecoveryAction.ShowFailure, resolvePlaybackRecovery(
            transcode, fallbackUsed = false, transcodeRequested = true, networkError = error, networkRetryUsed = true,
        ))
    }

    @Test
    fun timeoutsAreNetworkFailuresWhileDecoderFailuresKeepTheExistingTranscodeChain() {
        val timeout = PlaybackException("timeout", SocketTimeoutException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertTrue(playbackNetworkError(timeout) is NovumError.Network)
        val decoder = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertNull(playbackNetworkError(decoder))
        assertEquals(PlaybackRecoveryAction.RequestTranscode, resolvePlaybackRecovery(
            plan(), fallbackUsed = false, transcodeRequested = false, networkError = playbackNetworkError(decoder),
        ))
    }

    @Test
    fun unauthorizedAndNotFoundHttpResponsesDoNotTryToFixTheDecoder() {
        for (code in listOf(401, 403, 404)) {
            assertEquals(PlaybackRecoveryAction.ShowFailure, resolvePlaybackRecovery(
                plan(), fallbackUsed = false, transcodeRequested = false, networkError = playbackNetworkError(gatewayFailure(code)),
            ))
        }
    }

    @Test
    fun refreshingAnItemPreservesPauseIntentAndTheNonzeroResumePosition() {
        for (playing in listOf(false, true)) {
            val fake = FakePlayer(playing)
            fake.player.preparePlayback(plan(), subtitlesEnabled = true)
            assertEquals(playing, fake.playWhenReady)
            assertEquals(120_000L, fake.positionMs)
            assertEquals(plan(), fake.mediaItem?.localConfiguration?.tag)
            assertEquals(listOf("setMediaItem", "prepare"), fake.operations)
        }
    }

    @Test
    fun showingAnErrorStopsAndClearsTheOldBufferedStream() {
        val fake = FakePlayer(true)
        fake.player.preparePlayback(plan(), true)
        fake.operations.clear()

        fake.player.stopFailedPlayback()

        assertFalse(fake.isPlaying)
        assertNull(fake.mediaItem)
        assertEquals(listOf("stop", "clearMediaItems"), fake.operations)
    }

    @Test
    fun clearingAFailedProgressiveTranscodeRetainsAbsoluteProgressAndReportsStoppedOnlyOnce() {
        val transcode = plan().copy(method = PlayMethod.Transcode, streamStartPositionMs = 120_000,
            serverSubtitleSelection = true, serverSubtitleStreamIndex = -1, playSessionId = "session-1")
        val fake = FakePlayer(false)
        fake.player.preparePlayback(transcode, subtitlesEnabled = false)
        fake.positionMs = 11_000L
        val checkpoints = PlaybackCheckpoints()
        val snapshot = requireNotNull(checkpoints.capture(fake.player))

        fake.player.stopFailedPlayback()

        assertEquals(0L, fake.positionMs)
        assertEquals(131_000L, checkpoints.capture(fake.player)?.positionMs)
        assertEquals(transcode, checkpoints.latest?.plan)
        assertEquals(240_000L, checkpoints.latest?.durationMs)
        val stopped = mutableListOf<Long>()
        checkpoints.reportStopped(report = stopped::add)
        checkpoints.reportStopped(report = stopped::add)
        assertEquals("Back followed by disposal closes the session only once", listOf(131_000L), stopped)

        fake.player.preparePlayback(snapshot.plan.copy(startPositionMs = snapshot.positionMs), subtitlesEnabled = false)
        assertFalse(fake.playWhenReady)
        assertEquals("Retry subtracts the original transcode offset exactly once", 11_000L, fake.positionMs)
        assertEquals(-1, (fake.mediaItem?.localConfiguration?.tag as PlaybackPlan).serverSubtitleStreamIndex)
    }

    @Test
    fun checkpointsAcceptARealRewindToZeroButAnEmptyPlayerCannotOverwriteTheLastPosition() {
        val fake = FakePlayer(true)
        fake.player.preparePlayback(plan(), true)
        val checkpoints = PlaybackCheckpoints()
        checkpoints.capture(fake.player)
        assertEquals(120_000L, checkpoints.latest?.positionMs)
        fake.positionMs = 0L
        checkpoints.capture(fake.player)
        assertEquals(0L, checkpoints.latest?.positionMs)
        fake.positionMs = 42_000L
        checkpoints.capture(fake.player)
        fake.player.stopFailedPlayback()
        checkpoints.capture(fake.player)
        assertEquals(42_000L, checkpoints.latest?.positionMs)
    }

    @Test
    fun anInitialPlanFailureDoesNotReportAZeroPositionForAnUnstartedStream() {
        val checkpoints = PlaybackCheckpoints()
        checkpoints.capture(FakePlayer(true).player)
        val stopped = mutableListOf<Long>()
        checkpoints.reportStopped(report = stopped::add)
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun checkpointsPreserveTheExistingFinishedAndNearEndRules() {
        val checkpoints = PlaybackCheckpoints()
        checkpoints.save(PlaybackCheckpoint(plan(), 200_000L, 240_000L))
        val stopped = mutableListOf<Long>()
        checkpoints.reportStopped(finished = true, report = stopped::add)
        assertEquals(listOf(240_000L), stopped)
        assertEquals(240_000L, PlaybackCheckpoint(plan(), 229_000L, 240_000L).stopPosition(false))
        assertEquals(200_000L, PlaybackCheckpoint(plan(), 200_000L, 240_000L).stopPosition(false))
    }

    @Test
    fun bothDisposalOrdersKeepTheAbsoluteCheckpointWithoutReadingAReleasedPlayer() {
        for (reportBeforeRelease in listOf(false, true)) {
            val fake = FakePlayer(false)
            fake.player.preparePlayback(plan().copy(method = PlayMethod.Transcode, streamStartPositionMs = 120_000), true)
            fake.positionMs = 11_000L
            val checkpoints = PlaybackCheckpoints()
            checkpoints.attach(fake.player)
            val stopped = mutableListOf<Long>()
            if (reportBeforeRelease) {
                checkpoints.captureActive()
                checkpoints.reportStopped(report = stopped::add)
            }

            checkpoints.detach(fake.player)
            fake.player.release()
            // FakePlayer throws if any timeline getter is called after release.
            assertEquals(131_000L, checkpoints.captureActive()?.positionMs)
            checkpoints.reportStopped(report = stopped::add)

            assertEquals(listOf(131_000L), stopped)
            assertEquals(131_000L, checkpoints.latest?.positionMs)
        }
    }

    private fun gatewayFailure(status: Int) = PlaybackException("HTTP request failed",
        HttpDataSource.InvalidResponseCodeException(status, "gateway", null, emptyMap(),
            DataSpec.Builder().setUri("https://media.example/stream").build(), byteArrayOf()),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

    private fun plan() = PlaybackPlan(
        url = "https://media.example/stream", method = PlayMethod.DirectPlay,
        audioTracks = emptyList(), subtitleTracks = emptyList(), subtitles = emptyList(),
        durationMs = 240_000, startPositionMs = 120_000, mediaSourceId = "actual-source",
    )

    private class FakePlayer(initialIntent: Boolean) {
        var playWhenReady = initialIntent
        var isPlaying = initialIntent
        var mediaItem: MediaItem? = null
        var positionMs = 0L
        var released = false
        val operations = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            check(!released || method.name !in setOf("getCurrentMediaItem", "getCurrentPosition", "getDuration")) {
                "Timeline getter invoked after Player.release(): ${method.name}"
            }
            when (method.name) {
                "getPlayWhenReady" -> playWhenReady
                "getCurrentMediaItem" -> mediaItem
                "getCurrentPosition" -> positionMs
                "getDuration" -> 240_000L
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "setMediaItem" -> {
                    operations += "setMediaItem"
                    mediaItem = args!![0] as MediaItem
                    positionMs = args[1] as Long
                    null
                }
                "prepare" -> { operations += "prepare"; playWhenReady = true; null }
                "stop" -> { operations += "stop"; isPlaying = false; null }
                "clearMediaItems" -> { operations += "clearMediaItems"; mediaItem = null; positionMs = 0L; null }
                "release" -> { released = true; positionMs = 0L; operations += "release"; null }
                "equals" -> false
                "hashCode" -> 1
                "toString" -> "FakePlayer"
                else -> null
            }
        } as Player
    }
}
