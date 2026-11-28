package com.novum.tv.ui.screen.player

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.player.buildMediaItem
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class PlayerSlowStartupTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun elapsedTimeWithoutAnActualPlayerErrorNeverRefreshesOrTranscodesTheStream() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        val plan = PlaybackPlan(
            url = "https://media.example/slow", method = PlayMethod.DirectPlay,
            audioTracks = emptyList(), subtitleTracks = emptyList(), subtitles = emptyList(),
            durationMs = 240_000, startPositionMs = 120_000,
        )
        val mounted = mutableStateOf(true)
        var refreshes = 0
        var transcodes = 0
        // Keep the initial non-READY state without starting any real network I/O.
        // The removed 8-second watchdog used to interrupt this state on time alone.
        player.setMediaItem(buildMediaItem(plan, subtitlesEnabled = true), plan.startPositionMs)
        player.playWhenReady = true
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                NovumTheme {
                    if (mounted.value) PlayerStage(
                        player = player,
                        audioDelayMs = 0,
                        onAudioDelayChanged = {},
                        subtitleDelayMs = 0,
                        onSubtitleDelayChanged = {},
                        onRememberTrack = { _, _ -> },
                        plan = plan,
                        videoAspectRatio = null,
                        title = "Slow startup",
                        logoUrl = null,
                        subtitle = "",
                        subtitleSize = 2,
                        subtitlesEnabled = true,
                        chapters = emptyList(),
                        nextEpisodeId = null,
                        onOpenEpisode = { _, _, _ -> },
                        onExit = {},
                        onMethodChanged = {},
                        onRequestTranscode = { transcodes++ },
                        onRetryPlayback = { _, _ -> refreshes++ },
                        audioPassthrough = false,
                        onSetPassthrough = {},
                        onRetryNextLine = {},
                    )
                }
            }
            composeRule.mainClock.advanceTimeBy(32_000)
            composeRule.runOnIdle {
                assertEquals(0, refreshes)
                assertEquals(0, transcodes)
                assertNotNull(player.currentMediaItem)
                mounted.value = false
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        } finally {
            player.release()
        }
    }
}
