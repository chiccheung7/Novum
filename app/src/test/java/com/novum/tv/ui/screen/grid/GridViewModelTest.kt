package com.novum.tv.ui.screen.grid

import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season
import com.novum.tv.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网格 ViewModel 测试：排序切换、分页归零和 Emby 查询参数映射。
 * state 为 StateFlow，测试调度器空转后读取 value 得到终态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class GridViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadsLibraryAndReportsTotal() = runTest {
        val repository = FakeGridRepository(total = 2368)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Library(libraryId = "lib-1"))
        advanceUntilIdle()

        val success = successItems(viewModel)
        assertEquals(1, repository.calls.size)
        assertEquals("lib-1", repository.calls.single().parentId)
        assertEquals(2368, viewModel.totalCount.value)
        // 默认按年份从新到旧。
        assertEquals("默认年份", "PremiereDate", repository.calls.single().sortBy)
        assertEquals("年份降序", false, repository.calls.single().ascending)
        assertNull("默认无观看状态过滤", repository.calls.single().filters)
        assertEquals(60, success.size)
    }

    @Test
    fun sortOptionPassesSortByAndOrder() = runTest {
        val repository = FakeGridRepository(total = 0)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Library(libraryId = "lib-1"))
        advanceUntilIdle()

        // 首次点击默认年份档时保持降序。
        viewModel.setSortOption(SortOption.Year)
        advanceUntilIdle()
        assertEquals("年份应传 PremiereDate", "PremiereDate", repository.calls.last().sortBy)
        assertEquals("首次点击恒降序", false, repository.calls.last().ascending)

        // 再点同一档：翻转为升序
        viewModel.setSortOption(SortOption.Year)
        advanceUntilIdle()
        assertEquals("同档再点翻升序", true, repository.calls.last().ascending)

        // 换档（评分）：首次点击恒降序
        viewModel.setSortOption(SortOption.Rating)
        advanceUntilIdle()
        assertEquals("评分应传 CommunityRating", "CommunityRating", repository.calls.last().sortBy)
        assertEquals("换档首点恒降序", false, repository.calls.last().ascending)
    }

    @Test
    fun nameSortStartsAscendingOnFirstClick() = runTest {
        val repository = FakeGridRepository(total = 0)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Library(libraryId = "lib-1"))
        advanceUntilIdle()

        // 片名档首点按 A→Z（SortOption.ascending = true）；此前恒降序会按 Z→A 排
        viewModel.setSortOption(SortOption.Name)
        advanceUntilIdle()
        assertEquals("片名应传 SortName", "SortName", repository.calls.last().sortBy)
        assertEquals("片名首点应升序", true, repository.calls.last().ascending)

        viewModel.setSortOption(SortOption.Name)
        advanceUntilIdle()
        assertEquals("同档再点翻降序", false, repository.calls.last().ascending)
    }

    @Test
    fun loadMoreAccumulatesUntilSortResets() = runTest {
        val repository = FakeGridRepository(total = 90, pageSize = 60)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Library(libraryId = "lib-1"))
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals("第一页 + 第二页", 90, successItems(viewModel).size)
        assertEquals(2, repository.calls.size)
        assertEquals("第二页 startIndex=60", 60, repository.calls.last().startIndex)

        viewModel.setSortOption(SortOption.Year)
        advanceUntilIdle()
        assertEquals("切排序后回第一页重查（limit 不变）", 60, repository.calls.last().limit)
        assertEquals(0, repository.calls.last().startIndex)
    }

    @Test
    fun favoritesSourceRespondsToSort() = runTest {
        val repository = FakeGridRepository(total = 8)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Favorites())
        advanceUntilIdle()

        // 收藏数据源同样响应排序重查。
        viewModel.setSortOption(SortOption.Rating)
        advanceUntilIdle()

        assertEquals("收藏数据源应因排序重查", 2, repository.calls.size)
        assertEquals("收藏仍走 IsFavorite", "IsFavorite", repository.calls.last().filters)
        assertEquals("评分排序生效", "CommunityRating", repository.calls.last().sortBy)
    }

    @Test
    fun refreshFavoritesRequeriesSilently() = runTest {
        val repository = FakeGridRepository(total = 8)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Favorites())
        advanceUntilIdle()

        // 模拟在详情页取消收藏后回到收藏页：条目数变少
        repository.total = 5
        viewModel.refreshFavorites()
        advanceUntilIdle()

        assertEquals("静默刷新应重查收藏", 2, repository.calls.size)
        assertEquals("刷新后回到第一页", 0, repository.calls.last().startIndex)
        assertEquals("收藏仍走 IsFavorite", "IsFavorite", repository.calls.last().filters)
        assertEquals(5, viewModel.totalCount.value)
        assertEquals("刷新后列表为服务端新值", 5, successItems(viewModel).size)
    }

    @Test
    fun emptyLibraryShowsEmptyState() = runTest {
        val repository = FakeGridRepository(total = 0)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Library(libraryId = "lib-1"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is UiState.Empty)
        assertEquals("服务端总数 0 应同步到标题", 0, viewModel.totalCount.value)
    }

    @Test
    fun searchPassesTypeAsIncludeItemTypes() = runTest {
        val repository = FakeGridRepository(total = 12)
        val viewModel = GridViewModel(repository)

        viewModel.search("无间道", SearchType.Movie)
        advanceUntilIdle()

        val call = repository.calls.single()
        assertEquals("电影类型应传 IncludeItemTypes=Movie", "Movie", call.includeItemTypes)
        assertEquals("无间道", call.searchTerm)

        viewModel.search("无间道", SearchType.All)
        advanceUntilIdle()
        assertNull("全部应清掉类型过滤", repository.calls.last().includeItemTypes)
    }

    @Test
    fun favoritesTypeSwitchRequeriesWithIncludeItemTypes() = runTest {
        val repository = FakeGridRepository(total = 8)
        val viewModel = GridViewModel(repository)
        viewModel.start(GridSource.Favorites())
        advanceUntilIdle()
        assertEquals("收藏默认不带类型过滤", null, repository.calls.single().includeItemTypes)

        viewModel.setFavoritesType(SearchType.Series)
        advanceUntilIdle()

        assertEquals("切类型应重查", 2, repository.calls.size)
        assertEquals("剧集类型应传 IncludeItemTypes=Series", "Series", repository.calls.last().includeItemTypes)
        assertEquals("切类型应回第一页", 0, repository.calls.last().startIndex)
        assertEquals("收藏仍走 IsFavorite", "IsFavorite", repository.calls.last().filters)
    }

    private fun successItems(viewModel: GridViewModel): List<MediaItem> {
        val state = viewModel.state.value
        check(state is UiState.Success) { "state 应处于 Success，实际 $state" }
        return state.data
    }
}

/** 记录式 FakeMediaRepository：记录每次 items 调用的关键参数，页数可配。 */
private class FakeGridRepository(
    total: Int,
    private val pageSize: Int = 60,
) : MediaRepository {

    /** 可变总数：模拟详情页操作后回到收藏页时服务端现况变化。 */
    var total: Int = total

    /** 依序记录的 items 调用参数。 */
    val calls = mutableListOf<ItemCall>()

    data class ItemCall(
        val parentId: String?,
        val startIndex: Int,
        val limit: Int,
        val sortBy: String,
        val ascending: Boolean,
        val filters: String?,
        val searchTerm: String?,
        val includeItemTypes: String?,
    )

    override suspend fun items(
        parentId: String?,
        startIndex: Int,
        limit: Int,
        sortBy: String,
        ascending: Boolean,
        filters: String?,
        searchTerm: String?,
        includeItemTypes: String?,
    ): NovumResult<MediaPage> {
        calls += ItemCall(parentId, startIndex, limit, sortBy, ascending, filters, searchTerm, includeItemTypes)
        // 末页不足一页时只返回剩余条数（真实服务端行为）
        val count = if (startIndex >= total) 0 else minOf(pageSize, total - startIndex)
        val pageItems = List(count) { index ->
            MediaItem(
                id = "item-${startIndex + index}",
                title = "条目 ${startIndex + index}",
                type = null,
                year = null,
                rating = null,
                imageUrl = null,
                subtitle = null,
            )
        }
        return NovumResult.Success(MediaPage(items = pageItems, total = total))
    }

    override suspend fun libraries(): NovumResult<List<MediaLibrary>> = NovumResult.Success(emptyList())

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
        NovumResult.Failure(NovumError.NotFound())

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> = NovumResult.Success(emptyList())

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        NovumResult.Success(emptyList())

    // 详情页进场定位的 NextUp 提示——各 fake 默认无提示
    override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> =
        NovumResult.Success(null)

    override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> =
        NovumResult.Success(favorite)

    override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> =
        NovumResult.Success(played)

    override suspend fun feed(): NovumResult<com.novum.tv.model.HomeFeed> =
        NovumResult.Failure(NovumError.NotFound())
}
