package com.novum.tv.ui.screen.home

import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.Episode
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season
import com.novum.tv.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首页 ViewModel 单元测试。
 *
 * 本文件覆盖 feed 装载、静默刷新与首焦重锚版本号（focusEpoch）。
 *
 * feed 是 [kotlinx.coroutines.flow.StateFlow]：空转测试调度器后直接读 `.value`
 * 即终态，不经过 Turbine 缓冲。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadsFeedIntoSuccess() = runTest {
        val viewModel = HomeViewModel(FakeMediaRepository(feed = testFeed()))
        advanceUntilIdle()

        val success = successFeed(viewModel)
        assertEquals("h1", success.hero.first().id)
    }

    @Test
    fun loadFailureEndsInError() = runTest {
        val repository = object : FakeMediaRepository(feed = testFeed()) {
            override suspend fun feed(): NovumResult<HomeFeed> =
                NovumResult.Failure(com.novum.tv.core.error.NovumError.Network())
        }
        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()

        assertTrue("装载失败应进错误态", viewModel.feed.value is UiState.Error)
        assertEquals("失败不递增首焦重锚版本", 0, viewModel.focusEpoch.value)
    }

    @Test
    fun refreshSilentlySwapsFeedWhenAlreadySuccessful() = runTest {
        var current = testFeed()
        val viewModel = HomeViewModel(
            object : FakeMediaRepository(feed = testFeed()) {
                override suspend fun feed(): NovumResult<HomeFeed> =
                    NovumResult.Success(current)
            },
        )
        advanceUntilIdle()

        // 回到首页：服务器已有新数据（Hero 换了一张）
        current = current.copy(hero = listOf(HomeEntry(id = "h2", title = "爱情来了", type = "Movie")))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("静默刷新应换入新 Hero", "h2", successFeed(viewModel).hero.first().id)
        // 刷新不再递增 focusEpoch——静默刷新完成时
        // 用户往往已在顶栏/内容区浏览，重锚会把焦点从手上抢走；「回首页锚 Hero」
        // 由重进组合的初始抓焦天然保证
        assertEquals("静默刷新不递增首焦重锚版本", 1, viewModel.focusEpoch.value)
    }
}

/** 断言辅助：feed 必须已到成功态，返回其中的聚合数据。 */
private fun successFeed(viewModel: HomeViewModel): HomeFeed {
    val state = viewModel.feed.value
    check(state is UiState.Success) { "feed 应处于 Success，实际 $state" }
    return state.data
}

/** 首页测试数据：Hero + 继续观看 + 一个分类库行。 */
private fun testFeed(): HomeFeed = HomeFeed(
    hero = listOf(HomeEntry(id = "h1", title = "顺流逆流", type = "Movie")),
    resume = HomeRow(
        id = "resume",
        items = listOf(HomeEntry(id = "ep-1", title = "红高粱", type = "Episode", seriesId = "series-1")),
    ),
    nextUp = null,
    latest = listOf(
        HomeLatestRow(
            libraryId = "lib-1",
            libraryName = "华语电影",
            items = listOf(HomeEntry(id = "l1", title = "爱情来了", type = "Movie")),
        ),
    ),
)

/**
 * 最小 FakeMediaRepository：只有 feed 有行为（收藏接口首页已不触达，
 * 保留平凡实现满足接口完整性）。
 */
private open class FakeMediaRepository(private val feed: HomeFeed) : MediaRepository {

    override suspend fun libraries(): NovumResult<List<MediaLibrary>> = NovumResult.Success(emptyList())

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

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
        NovumResult.Failure(com.novum.tv.core.error.NovumError.NotFound())

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> = NovumResult.Success(emptyList())

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        NovumResult.Success(emptyList())

    // 详情页进场定位的 NextUp 提示——首页 fake 不会被触达
    override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> =
        NovumResult.Success(null)

    override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> =
        NovumResult.Success(favorite)

    override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> =
        NovumResult.Success(played)

    override suspend fun feed(): NovumResult<HomeFeed> = NovumResult.Success(feed)
}
