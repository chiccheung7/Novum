package com.novum.tv.ui.screen.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PlaybackRepository
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
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
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
    val dispatchers = MainDispatcherRule()

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

    private fun viewModel(
        playback: PlaybackRepository,
        settings: SettingsRepository,
    ) = PlayerViewModel(
        playback = playback,
        media = EmptyMediaRepository,
        settings = settings,
        capability = FakeCapabilityProvider(),
        playbackPrefs = ItemPlaybackPrefsStore(
            File(ApplicationProvider.getApplicationContext<Context>().filesDir, "test_player_prefs.json"),
            // 真实 IO 线程会越过用例边界回补 Main 续体（UncaughtExceptionsBeforeTest
            // 偶红根因）：注入 Unconfined 让文件 IO 内联在测试协程上执行
            ioDispatcher = Dispatchers.Unconfined,
        ),
        appContext = ApplicationProvider.getApplicationContext(),
        logger = NoopNovumLogger,
        // 收尾上报用的是进程级作用域：测试里用 Unconfined 让它内联执行，避免用例
        // 跑完后还有游离协程越过用例边界
        appScope = CoroutineScope(Dispatchers.Unconfined),
        savedStateHandle = SavedStateHandle(mapOf("itemId" to ITEM_ID)),
    )

    /** 记录每次 plan 调用的参数。 */
    private data class PlanCall(
        val itemId: String,
        val startPositionMs: Long,
        val excludeLine: String?,
    )

    private class RecordingPlaybackRepository(
        private val plan: PlaybackPlan = plan(),
    ) : PlaybackRepository {

        val calls = mutableListOf<PlanCall>()

        override suspend fun plan(
            itemId: String,
            startPositionMs: Long,
            forceTranscode: Boolean,
            excludeLine: String?,
            mediaSourceId: String?,
        ): NovumResult<PlaybackPlan> {
            calls += PlanCall(itemId, startPositionMs, excludeLine)
            return NovumResult.Success(plan)
        }

        override suspend fun reportStarted(
            itemId: String,
            positionMs: Long,
            method: PlayMethod,
            playSessionId: String?,
        ) = Unit

        override suspend fun reportProgress(
            itemId: String,
            positionMs: Long,
            method: PlayMethod,
            playSessionId: String?,
        ) = Unit

        override suspend fun reportStopped(
            itemId: String,
            positionMs: Long,
            method: PlayMethod,
            playSessionId: String?,
        ) = Unit
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
