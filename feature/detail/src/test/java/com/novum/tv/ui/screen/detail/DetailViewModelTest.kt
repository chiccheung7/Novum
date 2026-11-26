package com.novum.tv.ui.screen.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.model.CastMember
import com.novum.tv.model.Episode
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.RecordingToastSink
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.FakeSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.robolectric.annotation.Config

/**
 * 详情页 ViewModel 单元测试。
 *
 * 覆盖：电影/剧集加载、默认季选择（未看集优先 → 第一常规季 → 第一个）
 * 标为已看的乐观切换（成功校正 + 失败回滚 + 内联标记）、收藏状态
 * 当前集选中（进场直达/焦点切当前集/显式清选中/切季清空）
 * 与优先视频质量策略读取。
 *
 * state 是 StateFlow：空转测试调度器后读 `.value` 即终态（
 * toRoute 解码要用 Bundle，本类挂 Robolectric）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadsMovieWithoutSeasons() = runTest {
        val repository = FakeDetailRepository(detail = movieDetail())
        val (viewModel, _) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()

        val state = viewModel.state.value
        val data = (state.detail as? UiState.Success)?.data
        check(data != null) { "detail 应处于 Success，实际 ${state.detail}" }
        assertEquals("m1", data.id)
        assertTrue("电影不应有季", state.seasons.isEmpty())
        assertEquals("电影直接播自身", "m1", state.playTargetId)
    }

    @Test
    fun returningToMovieRefreshesResumeWithoutLoadingOrUndoingFavorite() = runTest {
        val fresh = CompletableDeferred<NovumResult<MediaDetail>>()
        var calls = 0
        val repository = object : FakeDetailRepository(detail = movieDetail()) {
            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                if (++calls == 1) NovumResult.Success(movieDetail()) else fresh.await()
        }
        val (vm, _) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals("首入复用 init 请求", 1, calls)

        vm.onResume()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue("静默刷新期间不显示整页骨架", vm.state.value.detail is UiState.Success)
        vm.toggleFavorite()
        advanceUntilIdle()
        fresh.complete(NovumResult.Success(movieDetail().copy(resumePositionMs = 90_000L)))
        advanceUntilIdle()

        assertEquals(90_000L, detailData(vm).resumePositionMs)
        assertTrue("旧详情快照不能撤销刚完成的收藏", detailData(vm).isFavorite)
    }

    @Test
    fun returningToSeriesUpdatesProgressAndKeepsChosenSeasonAndEpisode() = runTest {
        var refreshed = false
        val initialSeasons = listOf(season("s1", 1, 8), season("s2", 2, 8))
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = initialSeasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
        ) {
            override suspend fun detail(itemId: String) = NovumResult.Success(
                seriesDetail().copy(unplayedCount = if (refreshed) 14 else 16),
            )
            override suspend fun seasons(seriesId: String) = NovumResult.Success(
                if (refreshed) initialSeasons.map { it.copy(unplayedCount = 7) } else initialSeasons,
            )
            override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
                if (seasonId == "s2") {
                    NovumResult.Success(
                        listOf(
                            episode("e2", seasonIndex = 2).copy(played = refreshed),
                            episode("e3", seasonIndex = 2).copy(progressMs = if (refreshed) 120_000L else 0L),
                        ),
                    )
                } else {
                    super.episodes(seriesId, seasonId)
                }
        }
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceUntilIdle()
        vm.selectEpisode("e3")
        advanceUntilIdle()
        val before = vm.state.value

        refreshed = true
        vm.refreshWatchState()
        advanceUntilIdle()

        val after = vm.state.value
        assertEquals("s2", after.selectedSeasonId)
        assertEquals("e3", after.episodeContextId)
        assertEquals(before.currentEpisodeId, after.currentEpisodeId)
        assertEquals(before.episodeContextFromEntry, after.episodeContextFromEntry)
        assertTrue(after.episodes.first().played)
        assertEquals(120_000L, after.currentEpisode?.progressMs)
        assertEquals(2, after.watchedEpisodeCount)
        assertEquals(14, after.data?.unplayedCount)
    }

    @Test
    fun failedWatchRefreshKeepsExistingSeriesContentAndSelection() = runTest {
        var fail = false
        val failure = NovumResult.Failure(NovumError.NotFound())
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, 8)),
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
            nextUpHint = episode("e1"),
        ) {
            override suspend fun detail(itemId: String) = if (fail) failure else super.detail(itemId)
            override suspend fun seasons(seriesId: String) = if (fail) failure else super.seasons(seriesId)
            override suspend fun episodes(seriesId: String, seasonId: String?) =
                if (fail) failure else super.episodes(seriesId, seasonId)
        }
        val (vm, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()
        val before = vm.state.value

        fail = true
        vm.refreshWatchState()
        advanceUntilIdle()

        assertEquals("失败时不得清空内容或重置进场语境", before, vm.state.value)
    }

    @Test
    fun successfulEpisodeWatchRefreshClearsPreviousFailureButFailedRefreshKeepsIt() = runTest {
        var failEpisodes = true
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, 8), season("s2", 2, 8)),
        ) {
            override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
                if (failEpisodes) NovumResult.Failure(NovumError.NotFound())
                else NovumResult.Success(listOf(episode("e1")))
        }
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        assertTrue(vm.state.value.episodesFailed)

        failEpisodes = false
        vm.refreshWatchState()
        advanceUntilIdle()
        assertEquals(listOf("e1"), vm.state.value.episodes.map { it.id })
        assertFalse("成功刷新分集后不应继续显示错误重试提示", vm.state.value.episodesFailed)

        failEpisodes = true
        vm.selectSeason("s2")
        advanceUntilIdle()
        vm.refreshWatchState()
        advanceUntilIdle()
        assertTrue("失败的刷新不能移除当前季的重试入口", vm.state.value.episodesFailed)
    }

    @Test
    fun newerFullLoadCannotBeOverwrittenByAnOlderCancelledRequest() = runTest {
        val stale = CompletableDeferred<NovumResult<MediaDetail>>()
        var calls = 0
        val repository = object : FakeDetailRepository(detail = movieDetail()) {
            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                if (++calls == 1) {
                    withContext(NonCancellable) { stale.await() }
                } else {
                    NovumResult.Success(movieDetail().copy(resumePositionMs = 80_000L))
                }
        }
        val (vm, _) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()
        vm.load()
        advanceUntilIdle()
        assertEquals(80_000L, detailData(vm).resumePositionMs)

        stale.complete(NovumResult.Success(movieDetail()))
        advanceUntilIdle()
        assertEquals("迟到的旧 load 不能覆盖新 load", 80_000L, detailData(vm).resumePositionMs)
    }

    @Test
    fun fullReloadCancelsThePreviousSeasonSelectionRequest() = runTest {
        val stale = CompletableDeferred<NovumResult<List<Season>>>()
        var calls = 0
        val repository = object : FakeDetailRepository(detail = seriesDetail()) {
            override suspend fun seasons(seriesId: String): NovumResult<List<Season>> =
                if (++calls == 1) {
                    withContext(NonCancellable) { stale.await() }
                } else {
                    NovumResult.Success(listOf(season("s2", 2, 8)))
                }
        }
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        vm.load()
        advanceUntilIdle()
        assertEquals("s2", vm.state.value.selectedSeasonId)

        stale.complete(NovumResult.Success(listOf(season("s1", 1, 8))))
        advanceUntilIdle()
        assertEquals("旧季列表到达不能重置默认季", "s2", vm.state.value.selectedSeasonId)
    }

    @Test
    fun cancelledEpisodeAndCastRequestsCannotOverwriteReturningToTheSameSeason() = runTest {
        val staleEpisodes = CompletableDeferred<NovumResult<List<Episode>>>()
        val staleCast = CompletableDeferred<NovumResult<List<CastMember>>>()
        var episodeCalls = 0
        var castCalls = 0
        val freshCast = CastMember("新演员", null, null)
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, 8), season("s2", 2, 8)),
        ) {
            override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
                if (seasonId == "s1" && ++episodeCalls == 1) {
                    withContext(NonCancellable) { staleEpisodes.await() }
                } else {
                    NovumResult.Success(listOf(episode("fresh-$seasonId")))
                }
            override suspend fun seasonCast(seriesId: String, seasonId: String): NovumResult<List<CastMember>> =
                if (seasonId == "s1" && ++castCalls == 1) {
                    withContext(NonCancellable) { staleCast.await() }
                } else {
                    NovumResult.Success(listOf(freshCast))
                }
        }
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceUntilIdle()
        vm.selectSeason("s1")
        advanceUntilIdle()

        staleEpisodes.complete(NovumResult.Success(listOf(episode("stale"))))
        staleCast.complete(NovumResult.Success(listOf(CastMember("旧演员", null, null))))
        advanceUntilIdle()
        assertEquals(listOf("fresh-s1"), vm.state.value.episodes.map { it.id })
        assertEquals(listOf(freshCast), vm.state.value.seasonCast)
    }

    @Test
    fun lateWatchRefreshCannotOverwriteEpisodesReloadedBySwitchingSeason() = runTest {
        val stale = CompletableDeferred<NovumResult<List<Episode>>>()
        var s1Calls = 0
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, 8), season("s2", 2, 8)),
        ) {
            override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
                if (seasonId == "s1" && ++s1Calls == 2) {
                    stale.await()
                } else {
                    NovumResult.Success(listOf(episode("fresh-$seasonId")))
                }
        }
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        vm.refreshWatchState()
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceUntilIdle()
        vm.selectSeason("s1")
        advanceUntilIdle()

        stale.complete(NovumResult.Success(listOf(episode("stale"))))
        advanceUntilIdle()
        assertEquals(listOf("fresh-s1"), vm.state.value.episodes.map { it.id })
    }

    @Test
    fun cancelledWatchRefreshCannotUndoManualPlayedMark() = runTest {
        val stale = CompletableDeferred<NovumResult<MediaDetail>>()
        var calls = 0
        val repository = object : FakeDetailRepository(detail = movieDetail()) {
            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                if (++calls == 1) NovumResult.Success(movieDetail()) else {
                    withContext(NonCancellable) { stale.await() }
                }
        }
        val (vm, _) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()
        vm.refreshWatchState()
        advanceUntilIdle()
        vm.togglePlayed()
        advanceUntilIdle()
        assertTrue(detailData(vm).played)

        stale.complete(NovumResult.Success(movieDetail()))
        advanceUntilIdle()
        assertTrue("刷新旧观看状态不能覆盖用户刚完成的标记", detailData(vm).played)
    }

    @Test
    fun settingsStreamFailureKeepsPageFunctional() = runTest {
        // 原 runCatching 包 collect 改为 Flow catch——错误路径必须
        // ①不崩（异常不得逃逸到 viewModelScope）②详情照常加载且画质策略保持默认档
        val repository = FakeDetailRepository(detail = movieDetail())
        val vm = DetailViewModel(
            repository = repository,
            toast = RecordingToastSink(),
            settings = ThrowingSettingsRepository(),
            playbackPrefs = newPrefsStore(),
            logger = NoopNovumLogger,
            appContext = ApplicationProvider.getApplicationContext<Context>(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to "m1")),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("设置流异常不应影响详情加载", state.detail is UiState.Success)
        assertEquals("画质策略保持默认档", "", state.versionStrategy)
    }

    @Test
    fun defaultSeasonPicksSeasonWithUnplayedEpisodes() = runTest {
        // 服务器给了季的未看数：S1 已看完、S2 还有 3 集未看 → 默认进 S2
        val seasons = listOf(
            season("s1", index = 1, unplayed = 0),
            season("s2", index = 2, unplayed = 3),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s2" to listOf(episode("e1", played = false))),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("默认季应选还有未看集的季", "s2", state.selectedSeasonId)
        assertEquals("应加载默认季的分集", listOf("e1"), state.episodes.map { it.id })
    }

    @Test
    fun defaultSeasonFallsBackToFirstRegularSeasonWithoutData() = runTest {
        // 未看数缺失（网关不给 UserData）：特别篇 index 0 不当默认，取第一常规季
        val seasons = listOf(
            season("special", index = 0, unplayed = null),
            season("s1", index = 1, unplayed = null),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        assertEquals("默认季应跳过特别篇取第一常规季", "s1", viewModel.state.value.selectedSeasonId)
    }

    @Test
    fun nextUpHintAnchorsDefaultSeasonAndCurrentEpisode() = runTest {
        // NextUp 提示集在 S2 → 默认季跟随提示季（优先于「有未看的季」
        // 口径），并记为当前集——页面据此显示该集信息、滚动选集行
        val seasons = listOf(
            season("s1", index = 1, unplayed = 4),
            season("s2", index = 2, unplayed = null),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf(
                "s2" to listOf(episode("e20", seasonIndex = 2)),
            ),
            nextUpHint = episode("e20", seasonIndex = 2),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("默认季应跟随 NextUp 集所在季", "s2", state.selectedSeasonId)
        assertEquals("当前集应记为提示集", "e20", state.currentEpisodeId)
    }

    @Test
    fun nextUpHintMatchesSeasonByIdWhenSeasonIndexMissing() = runTest {
        // 网关不下发分集 ParentIndexNumber 时，映射会兜底 seasonIndex=1；
        // 若只按序号匹配就会错误打开第一季。季 id 才是可靠锚点。
        val seasons = listOf(
            season("s1", index = 1, unplayed = 4),
            season("s2", index = 2, unplayed = 2),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s2" to listOf(episode("e20", seasonIndex = 2, seasonId = "s2"))),
            nextUpHint = episode("e20", seasonIndex = 1, seasonId = "s2"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("季 id 命中时应跟随提示季，而不是序号兜底的第一季", "s2", state.selectedSeasonId)
        assertEquals("当前集仍应锚定提示集", "e20", state.currentEpisodeId)
    }

    @Test
    fun seasonCastFollowsSelectedSeasonAndFallsBackWhenEmpty() = runTest {
        val seasons = listOf(
            season("s1", index = 1, unplayed = 2),
            season("s2", index = 2, unplayed = 0),
            season("s3", index = 3, unplayed = 0),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf(
                "s1" to listOf(episode("e1")),
                "s2" to listOf(episode("e2", seasonIndex = 2)),
                "s3" to listOf(episode("e3", seasonIndex = 3)),
            ),
            castFor = mapOf(
                "s1" to listOf(CastMember(name = "S1 演员", role = "主角", imageUrl = null, id = "p1")),
                "s2" to listOf(CastMember(name = "S2 演员", role = "主角", imageUrl = null, id = "p2")),
            ),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        assertEquals("默认季演员应已加载", listOf("S1 演员"), viewModel.state.value.seasonCast?.map { it.name })

        viewModel.selectSeason("s2")
        advanceUntilIdle()
        assertEquals("切季后应换成新季演员", listOf("S2 演员"), viewModel.state.value.seasonCast?.map { it.name })

        viewModel.selectSeason("s3")
        advanceUntilIdle()
        assertNull("该季无演员数据时应回落剧集级", viewModel.state.value.seasonCast)
    }

    @Test
    fun nextUpHintAnchorsFirstEpisodeWhenNothingWatched() = runTest {
        // 没看过 → NextUp 返回 S01E01，进场直接定位第一集
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
            nextUpHint = episode("e1", seasonIndex = 1),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("s1", state.selectedSeasonId)
        assertEquals("无观看进度应定位第一集", "e1", state.currentEpisodeId)
    }

    @Test
    fun nextUpHintMissingEpisodeDoesNotAnchor() = runTest {
        // NextUp 陈旧（提示集不在返回的分集里，如跨季竞态）→ 不设当前集，
        // 页面回落剧集级信息
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
            nextUpHint = episode("e99", seasonIndex = 1),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        assertNull("提示集不在本季分集里不应锚定", viewModel.state.value.currentEpisodeId)
    }

    @Test
    fun selectSeasonClearsCurrentEpisodeAnchor() = runTest {
        // 手动切季清空进场定位——它只服务进场，旧季集 id 不应跨季存活
        val seasons = listOf(
            season("s1", index = 1, unplayed = 0),
            season("s2", index = 2, unplayed = 5),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf(
                "s2" to listOf(episode("e20", seasonIndex = 2)),
                "s1" to listOf(episode("e1")),
            ),
            nextUpHint = episode("e20", seasonIndex = 2),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        assertEquals("e20", viewModel.state.value.currentEpisodeId)

        viewModel.selectSeason("s1")
        advanceUntilIdle()

        assertNull("切季后应清空当前集定位", viewModel.state.value.currentEpisodeId)
        assertEquals("切季后应加载新季分集", listOf("e1"), viewModel.state.value.episodes.map { it.id })
    }

    @Test
    fun noNextUpHintKeepsLegacyDefaultSeason() = runTest {
        // NextUp 失败或为空时回落到未看季，且不设置当前集。
        val seasons = listOf(
            season("s1", index = 1, unplayed = 0),
            season("s2", index = 2, unplayed = 3),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s2" to listOf(episode("e1"))),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("s2", state.selectedSeasonId)
        assertNull(state.currentEpisodeId)
    }

    @Test
    fun togglePlayedOptimisticallyUpdatesAndConfirms() = runTest {
        val repository = FakeDetailRepository(
            detail = movieDetail(),
            playedResult = { _, played -> NovumResult.Success(played) },
        )
        val (viewModel, toast) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()

        assertFalse("预置未看完", detailData(viewModel).played)
        viewModel.togglePlayed()
        advanceUntilIdle()

        assertTrue("乐观更新后应为已看", detailData(viewModel).played)
        assertEquals("成功应弹全局 Toast", listOf("已标为已看"), toast.texts)
        assertFalse(viewModel.state.value.playedFailed)
        assertEquals("应请求服务端标为已看", listOf("m1" to true), repository.playedCalls)
    }

    @Test
    fun togglePlayedRollsBackOnFailure() = runTest {
        val repository = FakeDetailRepository(
            detail = movieDetail(),
            playedResult = { _, _ -> NovumResult.Failure(NovumError.Network()) },
        )
        val (viewModel, toast) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()

        viewModel.togglePlayed()
        advanceUntilIdle()

        assertFalse("失败后应回滚到未看", detailData(viewModel).played)
        assertTrue("失败应置内联标记", viewModel.state.value.playedFailed)
        assertEquals("失败不应弹 Toast", emptyList<String>(), toast.texts)

        viewModel.consumePlayedFailure()
        assertFalse("消费后标记应复位", viewModel.state.value.playedFailed)
    }

    @Test
    fun toggleFavoriteStillWorks() = runTest {
        val repository = FakeDetailRepository(
            detail = movieDetail(),
            favoriteResult = { _, favorite -> NovumResult.Success(favorite) },
        )
        val (viewModel, toast) = viewModel(repository, itemId = "m1")
        advanceUntilIdle()

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertTrue(detailData(viewModel).isFavorite)
        assertEquals(listOf("已加入收藏"), toast.texts)
    }

    @Test
    fun selectSeasonReloadsEpisodes() = runTest {
        val seasons = listOf(season("s1", 1, unplayed = null), season("s2", 2, unplayed = null))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf(
                "s1" to listOf(episode("e1", seasonIndex = 1)),
                "s2" to listOf(episode("e2", seasonIndex = 2)),
            ),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        assertEquals("s1", viewModel.state.value.selectedSeasonId)

        viewModel.selectSeason("s2")
        advanceUntilIdle()

        assertEquals("s2", viewModel.state.value.selectedSeasonId)
        assertEquals(listOf("e2"), viewModel.state.value.episodes.map { it.id })
    }

    // 集级语境入口。

    @Test
    fun entryFocusFlagLandsInEpisodeContext() = runTest {
        // 继续观看入口直达：路由 focusCurrentEpisode=true + NextUp 锚点 →
        // 进场直接落集级语境，fromEntry 标记让页面把首焦交给播放按钮
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
            nextUpHint = episode("e2"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("进场应直达集级语境", "e2", state.episodeContextId)
        assertTrue("进场直达应带 fromEntry 标记", state.episodeContextFromEntry)
    }

    @Test
    fun entryFocusFlagWithoutHintStaysSeriesContext() = runTest {
        // 追平且无上新（NextUp 空）+ 直达标记 → 静默回落剧级态
        val seasons = listOf(season("s1", index = 1, unplayed = 0))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1", played = true))),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()

        assertNull("无锚点不应落集级语境", viewModel.state.value.episodeContextId)
    }

    @Test
    fun withoutEntryFlagSelectsNothing() = runTest {
        // 普通入口（海报/网格/搜索）不传标记：有锚点也只做 滚动定位
        // 不进集级语境
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
            nextUpHint = episode("e2"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        assertEquals("普通入口只滚动定位，不进语境", "e2", viewModel.state.value.currentEpisodeId)
        assertNull(viewModel.state.value.episodeContextId)
    }

    @Test
    fun selectEpisodeSwitchesContextAndClearsEntryFlag() = runTest {
        // 分集卡 OK 选中：切到该集语境且 fromEntry 置 false（页面「留卡」不抢焦点）
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
            nextUpHint = episode("e2"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()

        viewModel.selectEpisode("e1")
        assertEquals("选中应切语境", "e1", viewModel.state.value.episodeContextId)
        assertFalse("选中应清进场标记", viewModel.state.value.episodeContextFromEntry)
    }

    @Test
    fun clearEpisodeContextReturnsToSeriesState() = runTest {
        // 集级态返回键：回剧级态
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
            nextUpHint = episode("e2"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()

        viewModel.clearEpisodeContext()
        assertNull("返回应回剧级态", viewModel.state.value.episodeContextId)
    }

    @Test
    fun selectSeasonClearsEpisodeContext() = runTest {
        // 切季（剧级态操作）：语境指向旧季分集，一并清空
        val seasons = listOf(
            season("s1", index = 1, unplayed = 8),
            season("s2", index = 2, unplayed = 3),
        )
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf(
                "s1" to listOf(episode("e1"), episode("e2")),
                "s2" to listOf(episode("e21", seasonIndex = 2)),
            ),
            nextUpHint = episode("e2"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()
        assertEquals("e2", viewModel.state.value.episodeContextId)

        viewModel.clearEpisodeContext()
        viewModel.selectEpisode("e1")
        advanceUntilIdle()
        viewModel.selectSeason("s2")
        advanceUntilIdle()

        assertNull("切季应清空集级语境", viewModel.state.value.episodeContextId)
    }

    @Test
    fun currentEpisodeFollowsSelectionAndFallsBackToDefault() = runTest {
        // 点卡只切当前集：currentEpisode = 选中集
        // 显式清选中回落默认播放目标集——页面返回直接退出，不再触发此操作
        val seasons = listOf(season("s1", index = 1, unplayed = 8))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1", played = true), episode("e2"))),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        assertEquals("默认目标 = 第一个未看完的集", "e2", viewModel.state.value.currentEpisode?.id)

        viewModel.selectEpisode("e1")
        assertEquals("选中后当前集跟随选中集", "e1", viewModel.state.value.currentEpisode?.id)

        viewModel.clearEpisodeContext()
        assertEquals("清选中回落默认目标集", "e2", viewModel.state.value.currentEpisode?.id)
    }

    @Test
    fun versionStrategyIsReadFromSettings() = runTest {
        // 优先视频质量 = 每次询问：详情页据 state.versionStrategy 决定点播放时
        // 是否先弹版本层
        val repository = FakeDetailRepository(detail = movieDetail())
        val (viewModel, _) = viewModel(repository, itemId = "m1", versionStrategy = "ask")
        advanceUntilIdle()

        assertEquals("设置里的画质策略应进页面状态", "ask", viewModel.state.value.versionStrategy)
    }

    @Test
    fun markSeasonPlayedOnlyMarksCurrentSeasonEpisodes() = runTest {
        // 只标记本季分集，不标记 series；剧集级状态以重拉详情的服务端聚合结果为准。
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", index = 1, unplayed = 2)),
            episodesFor = mapOf(
                "s1" to listOf(episode("e1"), episode("e2")),
            ),
        )
        val (viewModel, toast) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        viewModel.markSeasonPlayed(played = true)
        advanceUntilIdle()

        // 并发 6 路标记（Semaphore）：调用顺序不保证，按 id 排序后断言
        assertEquals(
            "只打本季分集，不打 series",
            listOf("e1" to true, "e2" to true),
            repository.playedCalls.sortedBy { it.first },
        )
        assertFalse("series 本身不得被打标记", repository.playedCalls.any { it.first == "se1" })
        assertTrue("本季分集应全部置已看", viewModel.state.value.episodes.all { it.played })
        assertEquals("成功提示应为整季已看", 1, toast.texts.size)
    }

    @Test
    fun markSeasonWatchedClearsProgressEvenForEpisodesAlreadyMarkedWatched() = runTest {
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, unplayed = 1)),
            episodesFor = mapOf("s1" to listOf(
                episode("e1").copy(progressMs = 60_000L),
                episode("e2", played = true).copy(progressMs = 120_000L),
                episode("e3", played = true),
            )),
        )
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        vm.markSeasonPlayed(true)
        advanceUntilIdle()

        assertEquals(listOf("e1" to true, "e2" to true), repository.playedCalls.sortedBy { it.first })
        assertTrue(vm.state.value.episodes.all { it.played && it.progressMs == 0L })
        assertEquals(0, vm.state.value.seasons.single().unplayedCount)
    }

    @Test
    fun markSeasonUnwatchedAlsoResetsPartlyWatchedEpisodeProgress() = runTest {
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, unplayed = 1)),
            episodesFor = mapOf("s1" to listOf(
                episode("e1").copy(progressMs = 60_000L),
                episode("e2", played = true),
            )),
        )
        val (vm, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        vm.markSeasonPlayed(false)
        advanceUntilIdle()

        assertEquals(listOf("e1" to false, "e2" to false), repository.playedCalls.sortedBy { it.first })
        assertTrue(vm.state.value.episodes.all { !it.played && it.progressMs == 0L })
        assertEquals(2, vm.state.value.seasons.single().unplayedCount)
    }

    @Test
    fun seasonPlayedRefreshDoesNotUndoFavoriteChangedWhileItWasLoading() = runTest {
        val refresh = CompletableDeferred<NovumResult<MediaDetail>>()
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", index = 1, unplayed = 1)),
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
        ) {
            private var calls = 0

            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                if (++calls == 1) super.detail(itemId) else refresh.await()
        }
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        viewModel.markSeasonPlayed(played = true)
        advanceUntilIdle()
        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertTrue("收藏请求应已成功", detailData(viewModel).isFavorite)

        // 观看聚合请求先发出，返回的收藏字段仍是旧值。
        refresh.complete(NovumResult.Success(seriesDetail().copy(played = true)))
        advanceUntilIdle()

        assertTrue("观看聚合刷新不得撤销期间完成的收藏", detailData(viewModel).isFavorite)
        assertTrue("观看字段仍应取服务端聚合值", detailData(viewModel).played)
    }

    @Test
    fun togglePlayedOnSeriesWithoutEpisodeDoesNothing() = runTest {
        // 剧集没有「整个条目」级标记：选集未加载完（episodeId 为 null）时对
        // series 打 PlayedItems 同样会级联全部季——防御性不动作
        val repository = FakeDetailRepository(detail = seriesDetail())
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        viewModel.togglePlayed(episodeId = null)
        advanceUntilIdle()

        assertTrue("剧集无单集目标时不得发任何标记请求", repository.playedCalls.isEmpty())
    }

    // 已看/未看 bug 修复回归。

    @Test
    fun singleEpisodeMarkedPlayedSyncsSeasonUnplayedAndAggregate() = runTest {
        // 剧集单集标已看：所在季的未看计数 -1，全剧"已看 x/y"随之更新（核心 bug）。
        val seasons = listOf(season("s1", index = 1, unplayed = 3))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        assertEquals("初始已看 5/8", 5 to 8,
            viewModel.state.value.watchedEpisodeCount to viewModel.state.value.totalEpisodeCount)

        viewModel.togglePlayed(episodeId = "e1")
        advanceUntilIdle()

        assertEquals("本季未看应从 3 减到 2", 2, viewModel.state.value.seasons.first().unplayedCount)
        assertEquals("全剧已看应变成 6/8", 6 to 8,
            viewModel.state.value.watchedEpisodeCount to viewModel.state.value.totalEpisodeCount)
    }

    @Test
    fun aggregateFallsBackToSeriesUnplayedWhenSeasonsLackCounts() = runTest {
        // 季列表不下发未看计数（Emby 常见）：用剧集条目 UserData.UnplayedItemCount 兜底，
        // 并保证「已看 x/y 集」这一行能显示真实进度，而不是整行消失。
        val seasons = listOf(season("s1", index = 1, unplayed = null))
        val detail = seriesDetail().copy(childCount = 20, unplayedCount = 6)
        val repository = FakeDetailRepository(detail = detail, seasons = seasons)
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        assertEquals("总集数回落条目 childCount", 20, viewModel.state.value.totalEpisodeCount)
        assertEquals("已看 = 总 − 条目未看", 14, viewModel.state.value.watchedEpisodeCount)
    }

    @Test
    fun singleEpisodePlayedRefreshesSeriesUnplayedFallback() = runTest {
        val initial = seriesDetail().copy(childCount = 20, unplayedCount = 6)
        val repository = object : FakeDetailRepository(
            detail = initial,
            seasons = listOf(season("s1", index = 1, unplayed = null)),
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
        ) {
            private var calls = 0

            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                NovumResult.Success(if (++calls == 1) initial else initial.copy(unplayedCount = 5))
        }
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        assertEquals(14, viewModel.state.value.watchedEpisodeCount)

        viewModel.togglePlayed("e1")
        advanceUntilIdle()

        assertEquals("季缺计数时，单集标记后应同步全剧未看数", 15, viewModel.state.value.watchedEpisodeCount)
        assertEquals(20, viewModel.state.value.totalEpisodeCount)
    }

    @Test
    fun aggregateUsesOnlySeasonsWithCompleteCounts() = runTest {
        // 部分季有完整计数、部分季缺字段：只按有数据的季聚合，不因个别季缺字段整行消失。
        val seasons = listOf(
            season("s1", index = 1, unplayed = 2),   // 8 − 2 = 6 已看
            season("s2", index = 2, unplayed = null), // 缺未看，不计入
        )
        val repository = FakeDetailRepository(detail = seriesDetail(), seasons = seasons)
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        assertEquals("只统计有数据的季：分母 8", 8, viewModel.state.value.totalEpisodeCount)
        assertEquals("只统计有数据的季：已看 6", 6, viewModel.state.value.watchedEpisodeCount)
    }

    @Test
    fun singleEpisodeMarkedPlayedRefreshesSeriesPlayedFlag() = runTest {
        // 剧集单集标已看后必须重拉详情，拿服务端聚合的 series.Played（最后一集看完翻 true）。
        val seasons = listOf(season("s1", index = 1, unplayed = 1))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
            seriesPlayedOnRefresh = true,
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()
        val callsBefore = repository.detailCallCount

        viewModel.togglePlayed(episodeId = "e1")
        advanceUntilIdle()

        assertTrue("标记后应重拉详情", repository.detailCallCount > callsBefore)
        assertTrue("剧集级 Played 应取服务端聚合值", detailData(viewModel).played)
    }

    @Test
    fun singleEpisodePlayedStaysBusyUntilSeriesAggregationCompletes() = runTest {
        val refresh = CompletableDeferred<NovumResult<MediaDetail>>()
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", index = 1, unplayed = 1)),
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
        ) {
            private var calls = 0

            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                if (++calls == 2) refresh.await() else super.detail(itemId)
        }
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        viewModel.togglePlayed("e1")
        advanceUntilIdle()
        assertTrue("服务端聚合仍在请求中时，应保持忙碌", viewModel.state.value.playedBusy)

        viewModel.togglePlayed("e1")
        viewModel.markSeasonPlayed(played = false)
        advanceUntilIdle()
        assertEquals("聚合完成前，单集与整季标记都应被拦截", listOf("e1" to true), repository.playedCalls)

        refresh.complete(NovumResult.Success(seriesDetail().copy(played = true)))
        advanceUntilIdle()
        assertFalse("聚合完成后应释放忙碌状态", viewModel.state.value.playedBusy)
        assertTrue(detailData(viewModel).played)

        viewModel.togglePlayed("e1")
        advanceUntilIdle()
        assertEquals("聚合完成后应允许新的标记", listOf("e1" to true, "e1" to false), repository.playedCalls)
        assertFalse(viewModel.state.value.playedBusy)
        assertFalse("最终聚合状态应对应后一次标记", detailData(viewModel).played)
    }

    @Test
    fun singleEpisodePlayedReleasesBusyWhenSeriesAggregationFails() = runTest {
        val repository = object : FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", index = 1, unplayed = 1)),
            episodesFor = mapOf("s1" to listOf(episode("e1"))),
        ) {
            private var calls = 0

            override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
                if (++calls == 1) super.detail(itemId) else NovumResult.Failure(NovumError.Network())
        }
        val (viewModel, _) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        viewModel.togglePlayed("e1")
        advanceUntilIdle()

        assertTrue("聚合失败不应撤销成功的单集标记", viewModel.state.value.episodes.single().played)
        assertFalse("聚合失败也应允许后续标记", viewModel.state.value.playedBusy)
    }

    @Test
    fun currentEpisodeStaysStableWhenMarkedPlayed() = runTest {
        // 剧级态把当前集标已看后，"当前集"不得实时跳到下一未看集（跳集 bug）。
        val seasons = listOf(season("s1", index = 1, unplayed = 2))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
            nextUpHint = episode("e1"),
        )
        val (viewModel, _) = viewModel(repository, itemId = "se1", focusCurrentEpisode = true)
        advanceUntilIdle()
        assertEquals("进场锚点当前集 = e1", "e1", viewModel.state.value.currentEpisode?.id)

        viewModel.togglePlayed(episodeId = "e1")
        advanceUntilIdle()

        assertEquals("标已看后当前集应稳定在 e1", "e1", viewModel.state.value.currentEpisode?.id)
    }

    @Test
    fun markSeasonPartialFailureDoesNotLocallyMarkFailedEpisodes() = runTest {
        // 整季标记部分失败：失败的集绝不能被本地标成已看（与服务端不一致的 bug）。
        val seasons = listOf(season("s1", index = 1, unplayed = 2))
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = seasons,
            episodesFor = mapOf("s1" to listOf(episode("e1"), episode("e2"))),
            playedResult = { id, played ->
                if (id == "e2") NovumResult.Failure(NovumError.Network())
                else NovumResult.Success(played)
            },
        )
        val (viewModel, toast) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        viewModel.markSeasonPlayed(played = true)
        advanceUntilIdle()

        val eps = viewModel.state.value.episodes.associateBy { it.id }
        assertTrue("成功的 e1 应为已看", eps.getValue("e1").played)
        assertFalse("失败的 e2 必须保持未看", eps.getValue("e2").played)
        assertTrue("部分失败应提示", toast.texts.any { it.contains("部分") })
    }

    @Test
    fun seasonUnwatchedCountIncludesAlreadyUnwatchedEpisodesWhoseProgressResetFailed() = runTest {
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", 1, unplayed = 2)),
            episodesFor = mapOf("s1" to listOf(
                episode("e1").copy(progressMs = 60_000L),
                episode("e2", played = true),
                episode("e3", played = true).copy(progressMs = 120_000L),
                episode("e4"),
            )),
            playedResult = { id, played ->
                if (id == "e1" || id == "e3") NovumResult.Failure(NovumError.Network())
                else NovumResult.Success(played)
            },
        )
        val (vm, toast) = viewModel(repository, itemId = "se1")
        advanceUntilIdle()

        vm.markSeasonPlayed(false)
        advanceUntilIdle()

        val result = vm.state.value.episodes.associateBy { it.id }
        assertFalse(result.getValue("e1").played)
        assertEquals("重置失败保留原进度", 60_000L, result.getValue("e1").progressMs)
        assertFalse(result.getValue("e2").played)
        assertEquals(0L, result.getValue("e2").progressMs)
        assertTrue("原本已看且重置失败的集仍已看", result.getValue("e3").played)
        assertFalse(result.getValue("e4").played)
        assertEquals("未看计数应匹配合并结果，不应仅数成功请求", 3, vm.state.value.seasons.single().unplayedCount)
        assertEquals(3, vm.state.value.episodes.count { !it.played })
        assertTrue(toast.texts.any { it.contains("部分") })
    }

    @Test
    fun rememberedVersionFollowsCurrentEpisode() = runTest {
        // 版本记忆是「按分集」存的（播放层按分集 id 读偏好）。详情页若按剧集 id 读，
        // 选过的版本永远不生效；同时切集后高亮必须跟着换集，而不是一直停在上一集。
        val prefs = newPrefsStore()
        prefs.update("e1", mediaSourceId = "src-a")
        prefs.update("e2", mediaSourceId = "src-b")
        val repository = FakeDetailRepository(
            detail = seriesDetail(),
            seasons = listOf(season("s1", index = 1, unplayed = 2)),
            episodesFor = mapOf("s1" to listOf(episode("e1", seasonId = "s1"), episode("e2", seasonId = "s1"))),
            nextUpHint = episode("e2", seasonId = "s1"),
        )
        val vm = DetailViewModel(
            repository = repository,
            toast = RecordingToastSink(),
            settings = FakeSettingsRepository(),
            playbackPrefs = prefs,
            logger = NoopNovumLogger,
            appContext = ApplicationProvider.getApplicationContext<Context>(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to "se1")),
        )
        advanceUntilIdle()

        assertEquals("进场锚点当前集 = e2", "e2", vm.state.value.currentEpisodeId)
        assertEquals("高亮应取当前集记忆的版本", "src-b", vm.state.value.rememberedMediaSourceId)

        vm.selectEpisode("e1")
        advanceUntilIdle()
        assertEquals("切集后高亮跟着换", "src-a", vm.state.value.rememberedMediaSourceId)

        vm.clearEpisodeContext()
        advanceUntilIdle()
        assertEquals("清选中回落锚点集版本", "src-b", vm.state.value.rememberedMediaSourceId)
    }

    private fun detailData(viewModel: DetailViewModel): MediaDetail {
        val state = viewModel.state.value
        return (state.detail as? UiState.Success)?.data
            ?: error("detail 应处于 Success，实际 ${state.detail}")
    }
}

/** 电影详情：无季无分集，未开始观看。 */
private fun movieDetail(): MediaDetail = MediaDetail(
    id = "m1",
    title = "顺流逆流",
    type = "Movie",
    year = 2000,
    rating = 7.8f,
    officialRating = null,
    runtimeMinutes = 100,
    overview = "简介",
    genres = listOf("动作"),
    posterUrl = null,
    backdropUrl = null,
    isFavorite = false,
    played = false,
    resumePositionMs = 0L,
    childCount = null,
    premiereDate = null,
    status = null,
    cast = emptyList(),
    studios = emptyList(),
)

/** 剧集详情：类型 Series 触发季/分集链路。 */
private fun seriesDetail(): MediaDetail = movieDetail().copy(id = "se1", type = "Series")

private fun season(id: String, index: Int, unplayed: Int?): Season = Season(
    id = id,
    name = "第 $index 季",
    index = index,
    episodeCount = 8,
    unplayedCount = unplayed,
)

private fun episode(
    id: String,
    played: Boolean = false,
    seasonIndex: Int = 1,
    seasonId: String? = null,
): Episode = Episode(
    id = id,
    title = "分集 $id",
    seasonIndex = seasonIndex,
    index = 1,
    played = played,
    progressMs = 0L,
    runtimeMinutes = 45,
    imageUrl = null,
    overview = null,
    seasonId = seasonId,
)

/** 最小 FakeMediaRepository：详情链路与写操作有行为，其余不会被详情页触达。 */
private open class FakeDetailRepository(
    private val detail: MediaDetail,
    private val seasons: List<Season> = emptyList(),
    private val episodesFor: Map<String, List<Episode>> = emptyMap(),
    private val favoriteResult: suspend (itemId: String, favorite: Boolean) -> NovumResult<Boolean> =
        { _, _ -> NovumResult.Success(true) },
    private val playedResult: suspend (itemId: String, played: Boolean) -> NovumResult<Boolean> =
        { _, played -> NovumResult.Success(played) },
    /** NextUp 进场定位提示；null = 服务端无下一集（追平且无上新）。 */
    private val nextUpHint: Episode? = null,
    /** 季 id → 季级演职员；缺省 = 该季无演职员数据（页面回落剧集级）。 */
    private val castFor: Map<String, List<CastMember>> = emptyMap(),
    /** 剧集级 Played 的聚合回传值：标记单集后重拉详情时用它；null = 沿用初始 detail。 */
    private val seriesPlayedOnRefresh: Boolean? = null,
) : MediaRepository {

    /** 依序记录的 setFavorite 调用。 */
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()

    /** 依序记录的 setPlayed 调用。 */
    val playedCalls = mutableListOf<Pair<String, Boolean>>()

    /** detail(...) 被调用次数；验证单集标记后是否重拉详情刷新剧集级状态。 */
    var detailCallCount = 0
        private set

    override suspend fun items(
        parentId: String?,
        startIndex: Int,
        limit: Int,
        sortBy: String,
        ascending: Boolean,
        filters: String?,
        searchTerm: String?,
        includeItemTypes: String?,
    ): NovumResult<MediaPage> = NovumResult.Success(MediaPage(items = emptyList(), total = 0))

    override suspend fun libraries(): NovumResult<List<MediaLibrary>> = NovumResult.Success(emptyList())

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> {
        detailCallCount++
        val played = if (detail.type == "Series" && seriesPlayedOnRefresh != null) {
            seriesPlayedOnRefresh
        } else {
            detail.played
        }
        return NovumResult.Success(detail.copy(played = played))
    }

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> = NovumResult.Success(seasons)

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        NovumResult.Success(episodesFor[seasonId].orEmpty())

    override suspend fun seasonCast(seriesId: String, seasonId: String): NovumResult<List<CastMember>> =
        NovumResult.Success(castFor[seasonId].orEmpty())

    // 进场定位提示
    override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> =
        NovumResult.Success(nextUpHint)

    override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> {
        favoriteCalls += itemId to favorite
        return favoriteResult(itemId, favorite)
    }

    override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> {
        playedCalls += itemId to played
        return playedResult(itemId, played)
    }

    override suspend fun feed(): NovumResult<HomeFeed> = NovumResult.Failure(NovumError.NotFound())
}

/** 用最小依赖构造 ViewModel（toast 记录器走 app 测试基建）。 */
private fun viewModel(
    repository: FakeDetailRepository,
    itemId: String,
    focusCurrentEpisode: Boolean = false,
    /** 优先视频质量策略（设置页口径）；默认空串 = 默认档。 */
    versionStrategy: String = "",
): Pair<DetailViewModel, RecordingToastSink> {
    val toast = RecordingToastSink()
    val vm = DetailViewModel(
        repository = repository,
        toast = toast,
        settings = FakeSettingsRepository(AppSettings(versionStrategy = versionStrategy)),
        playbackPrefs = newPrefsStore(),
        logger = NoopNovumLogger,
        appContext = ApplicationProvider.getApplicationContext<Context>(),
        savedStateHandle = SavedStateHandle(
            mapOf(
                "itemId" to itemId,
                "focusCurrentEpisode" to focusCurrentEpisode,
            ),
        ),
    )
    return vm to toast
}

/**
 * 每个 ViewModel 用独立临时文件，避免版本记忆在测试间互相污染。
 *
 * @param ioDispatcher 文件 IO 调度器：**默认 Unconfined**——真实 IO 线程上的读写
 *   不会被 `advanceUntilIdle` 等待，续体会在用例结束后才回到 Main，撞上
 *   `Dispatchers.setMain` 的切换（报 "Dispatchers.Main is used concurrently"），
 *   断言也可能读到更新前的状态。
 */
private fun newPrefsStore(
    ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) = ItemPlaybackPrefsStore(
    File.createTempFile("novum-detail-prefs", ".json"),
    ioDispatcher = ioDispatcher,
)

/** 设置流首次订阅即抛（错误路径断言用）。 */
private class ThrowingSettingsRepository : SettingsRepository {

    override val settings: Flow<AppSettings> = flow { throw IllegalStateException("DataStore 故障") }

    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
}
