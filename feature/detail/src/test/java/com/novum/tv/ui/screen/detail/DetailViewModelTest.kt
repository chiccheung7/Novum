package com.novum.tv.ui.screen.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 详情页 ViewModel 单元测试。
 *
 * 覆盖：电影/剧集加载、默认季选择（未看集优先 → 第一常规季 → 第一个）
 * 标为已看的乐观切换（成功校正 + 失败回滚 + 内联标记）、收藏状态
 * 当前集选中（进场直达/点卡切当前集/返回清选中/切季清空）
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
    fun settingsStreamFailureKeepsPageFunctional() = runTest {
        // 原 runCatching 包 collect 改为 Flow catch——错误路径必须
        // ①不崩（异常不得逃逸到 viewModelScope）②详情照常加载且画质策略保持默认档
        val repository = FakeDetailRepository(detail = movieDetail())
        val vm = DetailViewModel(
            repository = repository,
            toast = RecordingToastSink(),
            settings = ThrowingSettingsRepository(),
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
        // 清选中（返回键）回落默认播放目标集——操作行图标与页面背景都跟它走
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

private fun episode(id: String, played: Boolean = false, seasonIndex: Int = 1): Episode = Episode(
    id = id,
    title = "分集 $id",
    seasonIndex = seasonIndex,
    index = 1,
    played = played,
    progressMs = 0L,
    runtimeMinutes = 45,
    imageUrl = null,
    overview = null,
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
) : MediaRepository {

    /** 依序记录的 setFavorite 调用。 */
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()

    /** 依序记录的 setPlayed 调用。 */
    val playedCalls = mutableListOf<Pair<String, Boolean>>()

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

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> = NovumResult.Success(detail)

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> = NovumResult.Success(seasons)

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        NovumResult.Success(episodesFor[seasonId].orEmpty())

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

/** 设置流首次订阅即抛（错误路径断言用）。 */
private class ThrowingSettingsRepository : SettingsRepository {

    override val settings: Flow<AppSettings> = flow { throw IllegalStateException("DataStore 故障") }

    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
}
