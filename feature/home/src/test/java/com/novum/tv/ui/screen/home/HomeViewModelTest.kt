package com.novum.tv.ui.screen.home

import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.AuthRepository
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
import com.novum.tv.testing.NoopAuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
        val viewModel = HomeViewModel(FakeMediaRepository(feed = testFeed()), NoopAuthRepository)
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
        val viewModel = HomeViewModel(repository, NoopAuthRepository)
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
            NoopAuthRepository,
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

    @Test
    fun olderRefreshCannotReplaceNewerFeed() = runTest {
        val oldResponse = CompletableDeferred<NovumResult<HomeFeed>>()
        val newer = testFeed().copy(hero = listOf(HomeEntry(id = "new", title = "新内容")))
        var calls = 0
        val viewModel = HomeViewModel(object : FakeMediaRepository(testFeed()) {
            override suspend fun feed(): NovumResult<HomeFeed> = when (calls++) {
                0 -> NovumResult.Success(testFeed())
                1 -> withContext(NonCancellable) { oldResponse.await() }
                else -> NovumResult.Success(newer)
            }
        }, NoopAuthRepository)
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        runCurrent()
        assertEquals("new", successFeed(viewModel).hero.single().id)

        oldResponse.complete(NovumResult.Success(testFeed()))
        advanceUntilIdle()
        assertEquals("迟到的刷新不能覆盖最新内容", "new", successFeed(viewModel).hero.single().id)
        assertEquals("静默刷新不重锚焦点", 1, viewModel.focusEpoch.value)
    }

    @Test
    fun retryErrorCannotBeOverwrittenByAnEarlierRefresh() = runTest {
        val oldResponse = CompletableDeferred<NovumResult<HomeFeed>>()
        var calls = 0
        val viewModel = HomeViewModel(object : FakeMediaRepository(testFeed()) {
            override suspend fun feed(): NovumResult<HomeFeed> = when (calls++) {
                0 -> NovumResult.Success(testFeed())
                1 -> withContext(NonCancellable) { oldResponse.await() }
                else -> NovumResult.Failure(com.novum.tv.core.error.NovumError.Network())
            }
        }, NoopAuthRepository)
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent()
        viewModel.load()
        runCurrent()
        assertTrue(viewModel.feed.value is UiState.Error)

        oldResponse.complete(NovumResult.Success(testFeed()))
        advanceUntilIdle()
        assertTrue("旧刷新不能覆盖重试的错误态", viewModel.feed.value is UiState.Error)
    }

    @Test
    fun quietUnauthorizedRefreshExposesReauthenticationWithoutLoggingOutAutomatically() = runTest {
        var authorized = true
        var logoutCalls = 0
        val auth = object : AuthRepository by NoopAuthRepository {
            override suspend fun logout() { logoutCalls++ }
        }
        val viewModel = HomeViewModel(object : FakeMediaRepository(testFeed()) {
            override suspend fun feed(): NovumResult<HomeFeed> =
                if (authorized) NovumResult.Success(testFeed()) else NovumResult.Failure(NovumError.Unauthorized())
        }, auth)
        advanceUntilIdle()

        authorized = false
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue((viewModel.feed.value as UiState.Error).error is NovumError.Unauthorized)
        assertEquals("401 本身不自动清空凭证", 0, logoutCalls)
    }

    @Test
    fun quietNetworkFailureKeepsFeedAndDoesNotClearCredentials() = runTest {
        var fail = false
        var logoutCalls = 0
        val viewModel = HomeViewModel(object : FakeMediaRepository(testFeed()) {
            override suspend fun feed(): NovumResult<HomeFeed> =
                if (fail) NovumResult.Failure(NovumError.Network()) else NovumResult.Success(testFeed())
        }, object : AuthRepository by NoopAuthRepository {
            override suspend fun logout() { logoutCalls++ }
        })
        advanceUntilIdle()
        fail = true
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.reauthenticate { error("网络错误不能跳登录页") }
        advanceUntilIdle()

        assertTrue(viewModel.feed.value is UiState.Success)
        assertEquals(0, logoutCalls)
    }

    @Test
    fun reauthenticationWaitsForClearAndIgnoresRepeatedClicks() = runTest {
        val cleared = CompletableDeferred<Unit>()
        var logoutCalls = 0
        var navigations = 0
        val viewModel = HomeViewModel(unauthorizedRepository(), object : AuthRepository by NoopAuthRepository {
            override suspend fun logout() { logoutCalls++; cleared.await() }
        })
        advanceUntilIdle()

        viewModel.reauthenticate { navigations++ }
        viewModel.reauthenticate { navigations++ }
        runCurrent()
        assertTrue(viewModel.reauthenticating.value)
        assertEquals(1, logoutCalls)
        assertEquals(0, navigations)

        cleared.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, navigations)
        assertEquals(false, viewModel.reauthenticating.value)
    }

    @Test
    fun failedSessionClearKeepsRecoveryAvailableForRetry() = runTest {
        var fail = true
        var navigations = 0
        val viewModel = HomeViewModel(unauthorizedRepository(), object : AuthRepository by NoopAuthRepository {
            override suspend fun logout() { if (fail) error("fixture disk failure") }
        })
        advanceUntilIdle()
        viewModel.reauthenticate { navigations++ }
        advanceUntilIdle()
        assertEquals(0, navigations)
        assertTrue(viewModel.reauthenticationError.value is NovumError.Unknown)
        assertEquals(false, viewModel.reauthenticating.value)

        fail = false
        viewModel.reauthenticate { navigations++ }
        advanceUntilIdle()
        assertEquals(1, navigations)
        assertEquals(null, viewModel.reauthenticationError.value)
    }
}

private fun unauthorizedRepository(): MediaRepository = object : FakeMediaRepository(testFeed()) {
    override suspend fun feed(): NovumResult<HomeFeed> = NovumResult.Failure(NovumError.Unauthorized())
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
