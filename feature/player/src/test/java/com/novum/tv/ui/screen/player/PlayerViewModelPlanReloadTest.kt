package com.novum.tv.ui.screen.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PlaybackReporter
import com.novum.tv.data.repository.PlaybackRepository
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaPage
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.model.Season
import com.novum.tv.model.Episode
import com.novum.tv.testing.FakeCapabilityProvider
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.NoopDanmuRepository
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放页「直通切换 / 换线重试」的重新取计划逻辑测试。
 *
 * 这两条路径的共同风险是**丢掉播放位置**：直通切换会重建播放计划，如果 startOverrideMs
 * 没被透传，用户会从片头（或续播点）重看；换线重试如果不排除刚失败的线路，会一直
 * 撞同一台故障节点。两者都是"看起来能用、实际很疼"的问题，必须用单测钉住。
 */
// toRoute 解码要用 android.os.Bundle，纯 JUnit 下是空壳
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class PlayerViewModelPlanReloadTest {

    @get:Rule
    val dispatchers = MainDispatcherRule(StandardTestDispatcher())

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun serverSubtitleSelectionKeepsOldStreamUntilPlanArrivesAndCommitsOnlyAfterReady() = runTest {
        val old = serverPlan(3)
        val next = serverPlan(7).copy(url = "http://line-a/new.m3u8", playSessionId = "new-session", startPositionMs = 42_000)
        val playback = RecordingPlaybackRepository(old)
        val prefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined)
        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        playback.planGate = gate
        playback.nextPlan = next
        vm.selectServerSubtitle(7, 42_000)
        assertTrue(vm.state.value.subtitleSwitching)
        assertEquals(old, vm.state.value.data)
        assertEquals(null, prefs.get(ITEM_ID)?.serverSubtitleStreamIndex)
        vm.rememberManualTrack(false, "New audio", "en", rawLabel = "New audio", channels = 6)
        vm.selectServerSubtitle(3, 43_000) // A second click while a switch is pending is ignored.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, playback.calls.size)
        assertEquals("source-1", playback.calls.last().mediaSourceId)
        assertEquals(7, playback.calls.last().subtitleStreamIndex)
        assertEquals(42_000L, playback.calls.last().startPositionMs)
        assertTrue(playback.calls.last().forceTranscode)
        assertTrue(vm.state.value.subtitleSwitching)
        assertEquals(next, vm.state.value.data)
        vm.onSubtitleStreamReady(old) // A stale READY event must not commit the requested track.
        assertEquals(null, prefs.get(ITEM_ID)?.serverSubtitleStreamIndex)
        vm.onSubtitleStreamReady(next)
        advanceUntilIdle()
        assertFalse(vm.state.value.subtitleSwitching)
        assertEquals(7, prefs.get(ITEM_ID)?.serverSubtitleStreamIndex)
        assertEquals("source-1", prefs.get(ITEM_ID)?.serverSubtitleMediaSourceId)
        assertEquals("New audio", vm.state.value.rememberedPrefs?.audioTrack?.rawLabel)
        assertEquals(ReportCall("stopped", 42_000, PlayMethod.Transcode, "old-session"), playback.reports.single())
    }

    @Test
    fun failedServerSubtitleRequestPreservesOldPlanAndMemoryAndCanRetry() = runTest {
        val old = serverPlan(3)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        playback.planFailure = NovumError.Network()
        vm.selectServerSubtitle(7, 22_000)
        advanceUntilIdle()
        assertEquals(old, vm.state.value.data)
        assertFalse(vm.state.value.subtitleSwitching)
        assertTrue(vm.state.value.subtitleSwitchError is NovumError.Network)
        assertEquals(null, vm.state.value.rememberedPrefs?.serverSubtitleStreamIndex)
        playback.planFailure = null
        playback.nextPlan = serverPlan(7)
        vm.selectServerSubtitle(7, 23_000)
        advanceUntilIdle()
        assertTrue(vm.state.value.subtitleSwitching)
        assertEquals(7, playback.calls.last().subtitleStreamIndex)
    }

    @Test
    fun failedNewSubtitleStreamRestoresPreviousPlanWithoutSavingFailedSelection() = runTest {
        val old = serverPlan(3)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        playback.nextPlan = serverPlan(7).copy(url = "http://line-a/new.m3u8", playSessionId = "failed-session")
        vm.selectServerSubtitle(7, 64_000)
        advanceUntilIdle()
        assertFalse(vm.onSubtitleStreamFailed(old))
        assertTrue(vm.onSubtitleStreamFailed(playback.nextPlan))
        assertEquals(old.copy(startPositionMs = 64_000), vm.state.value.data)
        assertFalse(vm.state.value.subtitleSwitching)
        assertEquals(null, vm.state.value.rememberedPrefs?.serverSubtitleStreamIndex)
        assertFalse(vm.onSubtitleStreamFailed(playback.nextPlan))
        advanceUntilIdle()
        assertEquals("failed-session", playback.reports.single().playSessionId)
    }

    @Test
    fun serverSubtitleOffSurvivesRetriesAudioReloadAndNextPlayback() = runTest {
        val playback = RecordingPlaybackRepository(serverPlan(3))
        val prefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined)
        val settings = FakeSettingsRepository()
        val vm = viewModel(playback, settings, playbackPrefs = prefs)
        advanceUntilIdle()
        val off = serverPlan(-1).copy(url = "http://line-a/no-subs.m3u8")
        playback.nextPlan = off
        vm.selectServerSubtitle(-1, 30_000)
        advanceUntilIdle()
        vm.onSubtitleStreamReady(off)
        advanceUntilIdle()
        assertEquals(true, prefs.get(ITEM_ID)?.subtitleDisabled)
        assertEquals(null, prefs.get(ITEM_ID)?.subtitleTrack)
        vm.retryNextLine(31_000)
        advanceUntilIdle()
        assertEquals(-1, playback.calls.last().subtitleStreamIndex)
        vm.retryWithTranscode(32_000)
        advanceUntilIdle()
        assertEquals(-1, playback.calls.last().subtitleStreamIndex)
        vm.setAudioPassthrough(false, 33_000)
        advanceUntilIdle()
        assertEquals(-1, playback.calls.last().subtitleStreamIndex)
        viewModel(playback, settings, playbackPrefs = prefs)
        advanceUntilIdle()
        assertEquals(-1, playback.calls.last().subtitleStreamIndex)
        assertEquals("source-1", playback.calls.last().mediaSourceId)
    }

    @Test
    fun switchingToAnotherVersionDoesNotReuseServerSubtitleIndex() = runTest {
        val prefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined)
        prefs.update(ITEM_ID, mediaSourceId = "source-1", serverSubtitleStreamIndex = 7,
            serverSubtitleMediaSourceId = "source-1")
        val playback = RecordingPlaybackRepository()
        viewModel(playback, FakeSettingsRepository(), mediaSourceId = "different-source", playbackPrefs = prefs)
        advanceUntilIdle()
        assertEquals("different-source", playback.calls.single().mediaSourceId)
        assertEquals(null, playback.calls.single().subtitleStreamIndex)
    }

    @Test
    fun missingRememberedServerTrackCanRecoverWithoutDiscardingAudioOrDanmuPreferences() = runTest {
        val prefs = storedSubtitlePrefs(7)
        val original = requireNotNull(prefs.get(ITEM_ID))
        val automatic = serverPlan(3).copy(subtitleTracks = serverPlan(3).subtitleTracks.filter { it.index == 3 })
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(502)))
            planResults.add(NovumResult.Success(automatic))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertEquals(automatic, vm.state.value.data)
        assertEquals(listOf(7, null), playback.calls.map { it.subtitleStreamIndex })
        assertTrue(playback.calls.all { it.mediaSourceId == "source-1" })
        val recovered = requireNotNull(prefs.get(ITEM_ID))
        assertEquals(null, recovered.serverSubtitleStreamIndex)
        assertEquals(null, recovered.serverSubtitleMediaSourceId)
        assertEquals(null, recovered.subtitleTrack)
        assertEquals(original.audioTrack, recovered.audioTrack)
        assertEquals(original.danmuEpisodeId, recovered.danmuEpisodeId)
        assertEquals(original.danmuOffsetMs, recovered.danmuOffsetMs)
        assertEquals(recovered, vm.state.value.rememberedPrefs)
    }

    @Test
    fun missingRememberedVersionCanRecoverToTheActualReplacementVersion() = runTest {
        val prefs = storedSubtitlePrefs(7)
        val automatic = plan().copy(mediaSourceId = "replacement-source")
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.NotFound()))
            planResults.add(NovumResult.Success(automatic))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertEquals(automatic, vm.state.value.data)
        assertEquals(2, playback.calls.size)
        assertEquals("replacement-source", prefs.get(ITEM_ID)?.mediaSourceId)
        assertEquals(null, prefs.get(ITEM_ID)?.serverSubtitleStreamIndex)
        vm.retryNextLine(40_000)
        advanceUntilIdle()
        assertEquals("replacement-source", playback.calls.last().mediaSourceId)
        assertEquals(null, playback.calls.last().subtitleStreamIndex)
    }

    @Test
    fun aTranscoderRejectionDoesNotEraseAStillExistingRememberedTrack() = runTest {
        val prefs = storedSubtitlePrefs(7)
        val original = prefs.get(ITEM_ID)
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(502)))
            planResults.add(NovumResult.Success(serverPlan(3)))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertTrue(vm.state.value.plan is UiState.Error)
        assertEquals(2, playback.calls.size)
        assertEquals(original, prefs.get(ITEM_ID))
    }

    @Test
    fun aStaticFallbackWithoutSourceIdentityDoesNotProveTheRememberedTrackDisappeared() = runTest {
        val prefs = storedSubtitlePrefs(7)
        val original = prefs.get(ITEM_ID)
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(502)))
            planResults.add(NovumResult.Success(plan()))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertTrue(vm.state.value.plan is UiState.Error)
        assertEquals(2, playback.calls.size)
        assertEquals(original, prefs.get(ITEM_ID))
    }

    @Test
    fun anAlreadyAdoptedServerSubtitleIsNotSilentlyDroppedDuringALaterReload() = runTest {
        val prefs = storedSubtitlePrefs(7)
        val original = prefs.get(ITEM_ID)
        val playback = RecordingPlaybackRepository(serverPlan(7))
        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()
        playback.planResults.add(NovumResult.Failure(NovumError.Server(502)))

        vm.load(startOverrideMs = 42_000)
        advanceUntilIdle()

        assertTrue(vm.state.value.plan is UiState.Error)
        assertEquals(2, playback.calls.size)
        assertEquals(7, playback.calls.last().subtitleStreamIndex)
        assertEquals(original, prefs.get(ITEM_ID))
    }

    @Test
    fun networkUnauthorizedAndOrdinaryServerFailuresDoNotProbeOrEraseStoredSubtitles() = runTest {
        for (error in listOf(NovumError.Network(), NovumError.Unauthorized(), NovumError.Server(500))) {
            val prefs = storedSubtitlePrefs(7)
            val original = prefs.get(ITEM_ID)
            val playback = RecordingPlaybackRepository().apply {
                planResults.add(NovumResult.Failure(error))
            }
            val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
            advanceUntilIdle()
            assertEquals(1, playback.calls.size)
            assertEquals(error, (vm.state.value.plan as UiState.Error).error)
            assertEquals(original, prefs.get(ITEM_ID))
        }
    }

    @Test
    fun aFailedAutomaticProbeDoesNotEstablishThatTheStoredSubtitleDisappeared() = runTest {
        val prefs = storedSubtitlePrefs(7)
        val original = prefs.get(ITEM_ID)
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(404)))
            planResults.add(NovumResult.Failure(NovumError.Network()))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertTrue((vm.state.value.plan as UiState.Error).error is NovumError.Network)
        assertEquals(2, playback.calls.size)
        assertEquals(original, prefs.get(ITEM_ID))
    }

    @Test
    fun rememberedOffMigratesToAReplacementVersionOnlyAfterItsExplicitOffPlanSucceeds() = runTest {
        val prefs = storedSubtitlePrefs(-1)
        val original = requireNotNull(prefs.get(ITEM_ID))
        val automatic = serverPlan(3).copy(mediaSourceId = "replacement-source")
        val off = serverPlan(-1).copy(mediaSourceId = "replacement-source", url = "http://line-a/replacement-off.m3u8")
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(404)))
            planResults.add(NovumResult.Success(automatic))
            planResults.add(NovumResult.Success(off))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertEquals(off, vm.state.value.data)
        assertEquals(listOf(-1, null, -1), playback.calls.map { it.subtitleStreamIndex })
        assertEquals(listOf("source-1", "source-1", "replacement-source"), playback.calls.map { it.mediaSourceId })
        val recovered = requireNotNull(prefs.get(ITEM_ID))
        assertEquals(-1, recovered.serverSubtitleStreamIndex)
        assertEquals("replacement-source", recovered.serverSubtitleMediaSourceId)
        assertTrue(recovered.subtitleDisabled)
        assertEquals(original.audioTrack, recovered.audioTrack)
        assertEquals(original.danmuEpisodeId, recovered.danmuEpisodeId)
    }

    @Test
    fun failedOffMigrationNeverPublishesTheReplacementVersionsDefaultBurnedSubtitle() = runTest {
        val prefs = storedSubtitlePrefs(-1)
        val original = prefs.get(ITEM_ID)
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(502)))
            planResults.add(NovumResult.Success(serverPlan(3).copy(mediaSourceId = "replacement-source")))
            planResults.add(NovumResult.Failure(NovumError.Network()))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertTrue((vm.state.value.plan as UiState.Error).error is NovumError.Network)
        assertEquals(3, playback.calls.size)
        assertEquals(original, prefs.get(ITEM_ID))
    }

    @Test
    fun offMigrationRejectsAResponseThatStillSelectsTheReplacementVersionsBurnedSubtitle() = runTest {
        val prefs = storedSubtitlePrefs(-1)
        val original = prefs.get(ITEM_ID)
        val burned = serverPlan(3).copy(mediaSourceId = "replacement-source")
        val playback = RecordingPlaybackRepository().apply {
            planResults.add(NovumResult.Failure(NovumError.Server(502)))
            planResults.add(NovumResult.Success(burned))
            planResults.add(NovumResult.Success(burned))
        }

        val vm = viewModel(playback, FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        assertEquals(NovumError.Server(502), (vm.state.value.plan as UiState.Error).error)
        assertEquals(3, playback.calls.size)
        assertEquals(original, prefs.get(ITEM_ID))
    }

    @Test
    fun rememberedOffIsReissuedForAnExplicitNewVersionInsteadOfOpeningItsDefaultSubtitle() = runTest {
        val prefs = storedSubtitlePrefs(-1)
        val off = serverPlan(-1).copy(mediaSourceId = "different-source")
        val playback = RecordingPlaybackRepository(off)

        val vm = viewModel(playback, FakeSettingsRepository(), mediaSourceId = "different-source", playbackPrefs = prefs)
        advanceUntilIdle()

        assertEquals(off, vm.state.value.data)
        assertEquals("different-source", playback.calls.single().mediaSourceId)
        assertEquals(-1, playback.calls.single().subtitleStreamIndex)
        assertEquals("different-source", prefs.get(ITEM_ID)?.serverSubtitleMediaSourceId)
        assertTrue(prefs.get(ITEM_ID)?.subtitleDisabled == true)
    }

    private suspend fun storedSubtitlePrefs(index: Int): ItemPlaybackPrefsStore =
        ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined).apply {
            update(
                itemId = ITEM_ID, mediaSourceId = "source-1",
                subtitleTrack = ItemPlaybackPrefsStore.TrackRef("Old subtitle", "zh-Hant").takeIf { index >= 0 },
                subtitleDisabled = index == -1,
                audioTrack = ItemPlaybackPrefsStore.TrackRef("Surround", "en", channels = 6),
                danmuEpisodeId = "danmu-episode", danmuOffsetMs = 800L,
                serverSubtitleStreamIndex = index, serverSubtitleMediaSourceId = "source-1",
            )
        }

    @Test
    fun invalidOrAlreadySelectedServerSubtitleDoesNotReload() = runTest {
        val playback = RecordingPlaybackRepository(serverPlan(3))
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        vm.selectServerSubtitle(3, 1_000)
        vm.selectServerSubtitle(99, 1_000)
        vm.selectServerSubtitle(-2, 1_000)
        advanceUntilIdle()
        assertEquals(1, playback.calls.size)
        assertFalse(vm.state.value.subtitleSwitching)
    }

    @Test
    fun anotherRecoveryCancelsPendingSubtitleRequestWithoutLeavingTheDrawerLocked() = runTest {
        val old = serverPlan(3)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        playback.planGate = gate
        vm.selectServerSubtitle(7, 42_000)
        runCurrent()
        assertTrue(vm.state.value.subtitleSwitching)
        vm.retryNextLine(43_000)
        assertFalse(vm.state.value.subtitleSwitching)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, playback.calls.last().subtitleStreamIndex)
        assertEquals(old, vm.state.value.data)
        vm.onSubtitleStreamReady(old)
        assertEquals(null, vm.state.value.rememberedPrefs?.serverSubtitleStreamIndex)
    }

    @Test
    fun rebuildingAProgressiveStreamKeepsItsCurrentServerSubtitleAndAbsoluteTarget() = runTest {
        val old = serverPlan(3).copy(streamStartPositionMs = 60_000, startPositionMs = 60_000)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        val next = old.copy(url = "http://line-a/earlier.ts", startPositionMs = 45_000,
            streamStartPositionMs = 45_000, playSessionId = "rebuilt-session")
        val gate = CompletableDeferred<Unit>()
        playback.nextPlan = next
        playback.planGate = gate
        vm.seekServerStream(45_000, old)
        runCurrent()
        assertEquals(old, vm.state.value.data)
        assertTrue(playback.reports.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(45_000L, playback.calls.last().startPositionMs)
        assertEquals(3, playback.calls.last().subtitleStreamIndex)
        assertEquals("source-1", playback.calls.last().mediaSourceId)
        assertTrue(playback.calls.last().forceTranscode)
        assertEquals(next, vm.state.value.data)
        assertEquals(listOf(ReportCall("stopped", 45_000, PlayMethod.Transcode, "old-session")), playback.reports)
        vm.onProgress(46_000)
        vm.onStopped(47_000)
        advanceUntilIdle()
        assertEquals(setOf(
            ReportCall("stopped", 45_000, PlayMethod.Transcode, "old-session"),
            ReportCall("progress", 46_000, PlayMethod.Transcode, "rebuilt-session"),
            ReportCall("stopped", 47_000, PlayMethod.Transcode, "rebuilt-session"),
        ), playback.reports.toSet())
        assertEquals(3, playback.reports.size)
    }

    @Test
    fun unseekableDirectPlaybackRebuildRequestsTranscodingAtTheAbsoluteTarget() = runTest {
        val old = plan().copy(mediaSourceId = "chosen-version", playSessionId = "direct-session")
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        val next = old.copy(
            url = "http://line-a/rebuilt.ts", method = PlayMethod.Transcode,
            startPositionMs = 70_000, streamStartPositionMs = 70_000, playSessionId = "seek-session",
        )
        playback.nextPlan = next

        vm.seekServerStream(70_000, old)
        advanceUntilIdle()

        assertEquals(70_000L, playback.calls.last().startPositionMs)
        assertEquals("chosen-version", playback.calls.last().mediaSourceId)
        assertTrue(playback.calls.last().forceTranscode)
        assertEquals(next, vm.state.value.data)
    }

    @Test
    fun failedProgressiveRebuildDoesNotStopTheExistingSession() = runTest {
        val old = serverPlan(3).copy(streamStartPositionMs = 60_000, startPositionMs = 60_000)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        playback.planFailure = NovumError.Network()

        vm.seekServerStream(45_000, old)
        advanceUntilIdle()

        assertEquals(old, vm.state.value.data)
        assertTrue(vm.state.value.subtitleSwitchError is NovumError.Network)
        assertTrue(playback.reports.isEmpty())
    }

    @Test
    fun cancelledProgressiveRebuildCannotApplyALatePlanOrStopTheExistingSession() = runTest {
        val old = serverPlan(3).copy(streamStartPositionMs = 60_000, startPositionMs = 60_000)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        playback.nextPlan = old.copy(url = "http://line-a/stale.ts", playSessionId = "stale-session")
        playback.planGate = gate
        playback.ignorePlanCancellation = true
        vm.seekServerStream(45_000, old)
        runCurrent()

        // Simulate a repository completion already in flight when a later seek cancels it.
        playback.planGate = null
        playback.ignorePlanCancellation = false
        playback.planFailure = NovumError.Network()
        vm.seekServerStream(40_000, old)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0L, 45_000L, 40_000L), playback.calls.map { it.startPositionMs })
        assertEquals(old, vm.state.value.data)
        assertTrue(vm.state.value.subtitleSwitchError is NovumError.Network)
        assertTrue(playback.reports.isEmpty())
    }

    @Test
    fun progressiveRebuildUsingTheSameSessionDoesNotStopThatSession() = runTest {
        val old = serverPlan(3).copy(streamStartPositionMs = 60_000, startPositionMs = 60_000)
        val playback = RecordingPlaybackRepository(old)
        val vm = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        val next = old.copy(url = "http://line-a/earlier.ts", startPositionMs = 45_000,
            streamStartPositionMs = 45_000)
        playback.nextPlan = next

        vm.seekServerStream(45_000, old)
        advanceUntilIdle()

        assertEquals(next, vm.state.value.data)
        assertTrue(playback.reports.isEmpty())
    }

    @Test
    fun `切换音频直通会写设置并从当前位置重新取计划`() = runTest {
        val playback = RecordingPlaybackRepository()
        val settings = FakeSettingsRepository()
        val viewModel = viewModel(playback, settings)
        advanceUntilIdle()
        playback.calls.clear()

        viewModel.setAudioPassthrough(enabled = false, resumeAtMs = 15_000)
        advanceUntilIdle()

        val call = playback.calls.single()
        assertEquals(15_000L, call.startPositionMs)
        assertEquals("ep-1", call.itemId)
        assertFalse(settings.settings.first().audioPassthrough)
    }

    @Test
    fun `换线重试会排除当前线路并保留进度`() = runTest {
        val playback = RecordingPlaybackRepository(
            plan = plan(lineBaseUrl = "http://line-a:8096"),
        )
        val viewModel = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()
        playback.calls.clear()

        viewModel.retryNextLine(positionMs = 42_000)
        advanceUntilIdle()

        val call = playback.calls.single()
        assertEquals(42_000L, call.startPositionMs)
        // 必须排除刚失败的那条，否则会在坏节点上反复重试
        assertEquals("http://line-a:8096", call.excludeLine)
    }

    @Test
    fun `换线与强转码重取计划沿用选定的版本`() = runTest {
        val playback = RecordingPlaybackRepository(
            plan = plan(lineBaseUrl = "http://line-a:8096"),
        )
        val viewModel = viewModel(playback, FakeSettingsRepository(), mediaSourceId = "src-4k")
        advanceUntilIdle()
        assertEquals("起播按路由指定版本", "src-4k", playback.calls.single().mediaSourceId)

        playback.calls.clear()
        viewModel.retryNextLine(positionMs = 1_000)
        advanceUntilIdle()
        assertEquals("换线只换域名，不得丢版本", "src-4k", playback.calls.single().mediaSourceId)

        playback.calls.clear()
        viewModel.retryWithTranscode(positionMs = 2_000)
        advanceUntilIdle()
        assertEquals("强转码是对同一版本转码，不得丢版本", "src-4k", playback.calls.single().mediaSourceId)
    }

    @Test
    fun `重取计划不会把倍速回写成快照里的旧档`() = runTest {
        // 设置快照停在 1.0（模拟「刚改的倍速还没落盘」）：重取计划时若照抄快照，
        // 用户在播放页调的 1.5x 会被打回 1.0，播放器按旧速度重建
        val playback = RecordingPlaybackRepository()
        val viewModel = viewModel(playback, StaleSettingsRepository(playbackSpeed = 1f))
        advanceUntilIdle()
        assertEquals(1f, viewModel.state.value.playbackSpeed)

        viewModel.setPlaybackSpeed(1.5f)
        advanceUntilIdle()
        assertEquals(1.5f, viewModel.state.value.playbackSpeed)

        // 直通切换走的就是 load(startOverrideMs)
        viewModel.load(startOverrideMs = 20_000)
        advanceUntilIdle()

        assertEquals("重取计划必须沿用内存里的当前倍速", 1.5f, viewModel.state.value.playbackSpeed)
    }

    @Test
    fun subtitleWeightLoadsUpdatesImmediatelyAndPersistsAcrossPlaybackReloads() = runTest {
        val settings = FakeSettingsRepository(AppSettings(subtitleWeight = 2))
        val viewModel = viewModel(RecordingPlaybackRepository(), settings)
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.subtitleWeight)

        viewModel.setSubtitleWeight(0)
        assertEquals(0, viewModel.state.value.subtitleWeight)
        advanceUntilIdle()
        assertEquals(0, settings.settings.first().subtitleWeight)

        viewModel.load(startOverrideMs = 20_000)
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.subtitleWeight)
    }

    @Test
    fun subtitleWeightDoesNotRevertWhenReloadReadsAStaleSettingsSnapshot() = runTest {
        val viewModel = viewModel(RecordingPlaybackRepository(), StaleSettingsRepository(playbackSpeed = 1f))
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.subtitleWeight)

        viewModel.setSubtitleWeight(99)
        assertEquals(2, viewModel.state.value.subtitleWeight)
        viewModel.load(startOverrideMs = 20_000)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.subtitleWeight)
    }

    @Test
    fun `手动选择和关闭字幕立即更新恢复策略而不等待落盘`() = runTest {
        val prefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined)
        val oldAudio = ItemPlaybackPrefsStore.TrackRef("原音轨", "zh", rawLabel = "原音轨")
        prefs.update(
            itemId = ITEM_ID,
            mediaSourceId = "source-1",
            subtitleTrack = ItemPlaybackPrefsStore.TrackRef("旧字幕", "zh", rawLabel = "旧字幕"),
            audioTrack = oldAudio,
        )
        val viewModel = viewModel(RecordingPlaybackRepository(), FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        viewModel.rememberManualTrack(true, "新字幕", "en", rawLabel = "新字幕")

        val selected = viewModel.state.value.rememberedPrefs
        assertEquals("新字幕", selected?.subtitleTrack?.rawLabel)
        assertFalse(selected?.subtitleDisabled ?: true)
        assertEquals(oldAudio, selected?.audioTrack)
        assertEquals("source-1", selected?.mediaSourceId)

        viewModel.rememberManualTrack(true, "", null, disabled = true)
        assertEquals(true, viewModel.state.value.rememberedPrefs?.subtitleDisabled)
        assertEquals(null, viewModel.state.value.rememberedPrefs?.subtitleTrack)
        advanceUntilIdle()
    }

    @Test
    fun `手动选择音轨立即替换起播记忆且保留字幕设置`() = runTest {
        val prefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined)
        prefs.update(
            itemId = ITEM_ID,
            audioTrack = ItemPlaybackPrefsStore.TrackRef("旧音轨", "zh", rawLabel = "旧音轨"),
            subtitleDisabled = true,
        )
        val viewModel = viewModel(RecordingPlaybackRepository(), FakeSettingsRepository(), playbackPrefs = prefs)
        advanceUntilIdle()

        viewModel.rememberManualTrack(false, "新音轨", "en", rawLabel = "新音轨", channels = 6)

        val selected = viewModel.state.value.rememberedPrefs
        assertEquals("新音轨", selected?.audioTrack?.rawLabel)
        assertEquals(6, selected?.audioTrack?.channels)
        assertEquals(true, selected?.subtitleDisabled)
        advanceUntilIdle()
    }

    @Test
    fun `排队的上报保留事件发生时的播放方式和会话`() = runTest {
        val playback = RecordingPlaybackRepository(
            plan = plan().copy(playSessionId = "session-a"),
        )
        // 停止上报走独立 IO 队列，故意等主线程新计划生效后再执行。
        val reportScheduler = TestCoroutineScheduler()
        val viewModel = viewModel(
            playback,
            FakeSettingsRepository(),
            appScope = CoroutineScope(StandardTestDispatcher(reportScheduler)),
        )
        advanceUntilIdle()

        viewModel.onStarted(1_000)
        viewModel.onProgress(2_000)
        viewModel.onStopped(3_000)
        viewModel.onMethodChanged(PlayMethod.DirectStream)
        playback.nextPlan = plan().copy(method = PlayMethod.Transcode, playSessionId = "session-b")
        viewModel.retryWithTranscode(4_000)
        advanceUntilIdle()
        assertEquals("session-b", viewModel.state.value.data?.playSessionId)
        reportScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                ReportCall("started", 1_000, PlayMethod.DirectPlay, "session-a"),
                ReportCall("progress", 2_000, PlayMethod.DirectPlay, "session-a"),
                ReportCall("stopped", 3_000, PlayMethod.DirectPlay, "session-a"),
            ),
            playback.reports,
        )
    }

    @Test
    fun `播放页始终使用构造时绑定的上报器`() = runTest {
        val originalReporter = RecordingPlaybackRepository()
        val otherReporter = RecordingPlaybackRepository()
        val playback = RecordingPlaybackRepository().apply { sessionReporterOverride = originalReporter }
        val viewModel = viewModel(playback, FakeSettingsRepository())
        advanceUntilIdle()

        // 模拟用户切换服务器/账号，之后读取仓储的当前会话会拿到另一个上报器。
        playback.sessionReporterOverride = otherReporter
        viewModel.onStarted(1_000)
        viewModel.onProgress(2_000)
        viewModel.onStopped(3_000)
        advanceUntilIdle()

        // appScope 与 Main 队列独立；此处约束归属与完整事件，不约束完成顺序。
        assertEquals(3, originalReporter.reports.size)
        assertEquals(
            setOf(
                ReportCall("started", 1_000, PlayMethod.DirectPlay, null),
                ReportCall("progress", 2_000, PlayMethod.DirectPlay, null),
                ReportCall("stopped", 3_000, PlayMethod.DirectPlay, null),
            ),
            originalReporter.reports.toSet(),
        )
        assertEquals(emptyList<ReportCall>(), otherReporter.reports)
        assertEquals(emptyList<ReportCall>(), playback.reports)
    }

    private fun viewModel(
        playback: PlaybackRepository,
        settings: SettingsRepository,
        mediaSourceId: String? = null,
        playbackPrefs: ItemPlaybackPrefsStore? = null,
        appScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = PlayerViewModel(
        playback = playback,
        media = EmptyMediaRepository,
        settings = settings,
        danmuRepository = NoopDanmuRepository,
        capability = FakeCapabilityProvider(),
        playbackPrefs = playbackPrefs ?: ItemPlaybackPrefsStore(
            File(ApplicationProvider.getApplicationContext<Context>().filesDir, "test_player_prefs.json"),
            // 真实 IO 线程会越过用例边界回补 Main 续体（UncaughtExceptionsBeforeTest
            // 偶红根因）：注入 Unconfined 让文件 IO 内联在测试协程上执行
            ioDispatcher = Dispatchers.Unconfined,
        ),
        sessionHolder = SessionHolder(),
        appContext = ApplicationProvider.getApplicationContext(),
        logger = NoopNovumLogger,
        // 收尾上报用的是进程级作用域：测试里用 Unconfined 让它内联执行，避免用例
        // 跑完后还有游离协程越过用例边界
        appScope = appScope,
        savedStateHandle = SavedStateHandle(
            buildMap {
                put("itemId", ITEM_ID)
                mediaSourceId?.let { put("mediaSourceId", it) }
            },
        ),
    )

    /** 记录每次 plan 调用的参数。 */
    private data class PlanCall(
        val itemId: String,
        val startPositionMs: Long,
        val excludeLine: String?,
        val mediaSourceId: String?,
        val subtitleStreamIndex: Int?,
        val forceTranscode: Boolean,
    )

    private data class ReportCall(
        val event: String,
        val positionMs: Long,
        val method: PlayMethod,
        val playSessionId: String?,
    )

    private class RecordingPlaybackRepository(
        plan: PlaybackPlan = plan(),
    ) : PlaybackRepository {

        val calls = mutableListOf<PlanCall>()
        val reports = mutableListOf<ReportCall>()
        var nextPlan = plan
        var sessionReporterOverride: PlaybackReporter? = null
        var planGate: CompletableDeferred<Unit>? = null
        var ignorePlanCancellation = false
        var planFailure: NovumError? = null
        val planResults = ArrayDeque<NovumResult<PlaybackPlan>>()

        override fun sessionReporter(): PlaybackReporter = sessionReporterOverride ?: this

        override suspend fun plan(
            itemId: String,
            startPositionMs: Long,
            forceTranscode: Boolean,
            excludeLine: String?,
            mediaSourceId: String?,
            subtitleStreamIndex: Int?,
        ): NovumResult<PlaybackPlan> {
            calls += PlanCall(itemId, startPositionMs, excludeLine, mediaSourceId, subtitleStreamIndex, forceTranscode)
            val result = if (planResults.isNotEmpty()) planResults.removeFirst()
                else planFailure?.let { NovumResult.Failure(it) } ?: NovumResult.Success(nextPlan)
            val gate = planGate
            if (gate != null) {
                if (ignorePlanCancellation) withContext(NonCancellable) { gate.await() }
                else gate.await()
            }
            return result
        }

        override suspend fun reportStarted(
            itemId: String,
            positionMs: Long,
            method: PlayMethod,
            playSessionId: String?,
        ) {
            reports += ReportCall("started", positionMs, method, playSessionId)
        }

        override suspend fun reportProgress(
            itemId: String,
            positionMs: Long,
            method: PlayMethod,
            playSessionId: String?,
        ) {
            reports += ReportCall("progress", positionMs, method, playSessionId)
        }

        override suspend fun reportStopped(
            itemId: String,
            positionMs: Long,
            method: PlayMethod,
            playSessionId: String?,
        ) {
            reports += ReportCall("stopped", positionMs, method, playSessionId)
        }
    }

    /**
     * 快照永远不变、写入被丢弃的设置假实现。
     *
     * 模拟「刚改的倍速还没落盘」：用来验证重取计划时不会照抄旧快照。
     */
    private class StaleSettingsRepository(playbackSpeed: Float) : SettingsRepository {
        private val snapshot = AppSettings(playbackSpeed = playbackSpeed)
        override val settings: Flow<AppSettings> = flowOf(snapshot)
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    /**
     * 详情固定失败：本测试只关心"计划重取"的参数，标题与续播位置不参与断言，
     * 失败会让 ViewModel 走 title 为空的正常分支。
     */
    private object EmptyMediaRepository : MediaRepository {
        override suspend fun libraries(): NovumResult<List<MediaLibrary>> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun items(
            parentId: String?,
            startIndex: Int,
            limit: Int,
            sortBy: String,
            ascending: Boolean,
            filters: String?,
            searchTerm: String?,
            includeItemTypes: String?,
        ): NovumResult<MediaPage> = NovumResult.Failure(NovumError.Network())

        override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun seasons(seriesId: String): NovumResult<List<Season>> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> =
            NovumResult.Failure(NovumError.Network())

        override suspend fun feed(): NovumResult<HomeFeed> = NovumResult.Failure(NovumError.Network())
    }

    private companion object {
        const val ITEM_ID = "ep-1"

        fun serverPlan(index: Int) = plan().copy(
            url = "http://line-a/old.m3u8", method = PlayMethod.Transcode,
            mediaSourceId = "source-1", playSessionId = "old-session",
            subtitleTracks = listOf(
                com.novum.tv.model.TrackOption(3, "简体", "zh-Hans"),
                com.novum.tv.model.TrackOption(7, "繁体", "zh-Hant"),
            ),
            burnedInSubtitleLabel = if (index >= 0) "字幕$index" else null,
            burnedInSubtitleIndex = index.takeIf { it >= 0 },
            serverSubtitleSelection = true, serverSubtitleStreamIndex = index,
        )

        fun plan(lineBaseUrl: String? = null): PlaybackPlan = PlaybackPlan(
            url = "http://line-a:8096/Videos/1/stream",
            method = PlayMethod.DirectPlay,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            subtitles = emptyList(),
            durationMs = 3_600_000L,
            startPositionMs = 0L,
            lineBaseUrl = lineBaseUrl,
        )
    }
}
