package com.novum.tv.ui.screen.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 内容类型筛选（规范 搜索/收藏：全部 / 电影 / 剧集）。
 *
 * 走 Emby `IncludeItemTypes` 参数——与观看状态筛选不同，该参数服务端原生支持。
 */
enum class SearchType(
    /** 传给 Emby `IncludeItemTypes` 的值；null 表示不过滤。 */
    val embyIncludeItemTypes: String?,
) {
    All(null),
    Movie("Movie"),
    Series("Series"),
}

/**
 * 海报网格的数据源。
 *
 * 媒体库、收藏、搜索三种页面共用一套分页与状态机，差异只在查询参数。
 */
sealed interface GridSource {

    /** 某个媒体库的全部条目。 */
    data class Library(val libraryId: String) : GridSource

    /** 当前用户的收藏；[type] 为类型筛选。 */
    data class Favorites(val type: SearchType = SearchType.All) : GridSource

    /** 按关键词搜索；[type] 为类型筛选。 */
    data class Search(val query: String, val type: SearchType = SearchType.All) : GridSource
}

/** 排序选项（规范 片名 / 上映年份 / 评分）。 */
enum class SortOption(
    /** 传给 Emby `SortBy` 参数的值。 */
    val embySortBy: String,
    /** 排序方向：片名升序、年份与评分降序。 */
    val ascending: Boolean,
) {
    Name("SortName", true),
    Year("PremiereDate", false),
    Rating("CommunityRating", false),
}

/**
 * 网格 ViewModel：分页加载媒体库、收藏和搜索结果。
 *
 * [start] 在数据源变化时重置列表；[loadMore] 使用 loading 与 total 防止重复加载。
 * 加载更多失败时保留已有内容。切换排序将 startIndex 归零并重新请求第一页。
 * 网关忽略 IsPlayed/IsUnplayed；收藏仍使用 Filters=IsFavorite。
 *
 * @param repository 媒体库仓储。
 */
@HiltViewModel
class GridViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<MediaItem>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MediaItem>>> = _state.asStateFlow()

    /** 服务端报告的总条数（随当前排序重查刷新），页面标题右侧展示。 */
    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    // 默认按年份从新到旧；userSorted 为 true 后，同一标签再次点按才翻转方向。
    private val _sortOption = MutableStateFlow(SortOption.Year)
    private val _sortDescending = MutableStateFlow(true)
    private var userSorted = false
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private var source: GridSource? = null
    private var startIndex = 0
    private var total = 0
    private var loading = false

    /**
     * 绑定数据源并加载第一页；数据源未变化时直接返回。
     *
     * @param source 数据源。
     */
    fun start(source: GridSource) {
        if (this.source == source) return
        reset(source)
    }

    /**
     * 强制按关键词重新搜索（同一关键词再次点击也要刷新）。
     *
     * @param query 关键词。
     * @param type 类型筛选（全部/电影/剧集），切类型也会重新取数。
     */
    fun search(query: String, type: SearchType = SearchType.All) {
        reset(GridSource.Search(query, type))
    }

    /**
     * 切换收藏页的类型筛选（全部/电影/剧集），回到第一页重新取数。
     *
     * @param type 目标类型。
     */
    fun setFavoritesType(type: SearchType) {
        val current = source as? GridSource.Favorites ?: return
        if (current.type == type) return
        reset(GridSource.Favorites(type))
    }

    /**
     * 切换排序并重新加载第一页，媒体库、收藏和搜索均响应。
     * 首次选择排序档恒为降序，再次点按同档时翻转方向。
     *
     * @param option 目标排序。
     */
    fun setSortOption(option: SortOption) {
        if (_sortOption.value == option && userSorted) {
            // 同档再点（用户已主动排过序）：翻转升降
            _sortDescending.value = !_sortDescending.value
        } else {
            // 换档首点，或首次点当前默认档：恒降序
            _sortOption.value = option
            _sortDescending.value = true
        }
        userSorted = true
        when (val current = source) {
            is GridSource.Library -> reset(current)
            is GridSource.Favorites -> reset(current)
            // 搜索：结果由关键词决定，排序不重查（Emby 搜索接口不支持 SortBy）
            else -> Unit
        }
    }

    /**
     * 返回收藏页时静默刷新，失败保留已有内容，不进入 Loading 态。
     * 刷新将分页归零，仅响应收藏数据源，绕过 start 的同源守卫。
     */
    fun refreshFavorites() {
        val current = source as? GridSource.Favorites ?: return
        viewModelScope.launch {
            when (
                val result = repository.items(
                    parentId = null,
                    startIndex = 0,
                    limit = PAGE_SIZE,
                    filters = FILTER_FAVORITE,
                    includeItemTypes = current.type.embyIncludeItemTypes,
                )
            ) {
                is NovumResult.Success -> {
                    val page = result.value
                    total = page.total
                    startIndex = page.items.size
                    _totalCount.value = page.total
                    _state.value = if (page.items.isEmpty()) UiState.Empty() else UiState.Success(page.items)
                }

                is NovumResult.Failure -> Unit
            }
        }
    }

    /** 错误态重试。 */
    fun retry() {
        source?.let { reset(it) }
    }

    /** 滚动接近底部时加载下一页。 */
    fun loadMore() {
        if (loading || startIndex >= total) return
        load(reset = false)
    }

    private fun reset(source: GridSource) {
        this.source = source
        startIndex = 0
        total = 0
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        val current = source ?: return
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (reset) {
                _state.value = UiState.Loading
                startIndex = 0
            }
            when (val result = fetch(current)) {
                is NovumResult.Success -> {
                    val page = result.value
                    total = page.total
                    startIndex += page.items.size
                    _totalCount.value = page.total
                    val merged = if (reset) page.items else currentItems() + page.items
                    _state.value = if (merged.isEmpty()) UiState.Empty() else UiState.Success(merged)
                }

                is NovumResult.Failure -> {
                    if (reset) {
                        _state.value = UiState.Error(result.error)
                    } else {
                        _state.value = UiState.Success(currentItems())
                    }
                }
            }
            loading = false
        }
    }

    private suspend fun fetch(source: GridSource): NovumResult<com.novum.tv.model.MediaPage> =
        when (source) {
            is GridSource.Library -> repository.items(
                parentId = source.libraryId,
                startIndex = startIndex,
                limit = PAGE_SIZE,
                sortBy = _sortOption.value.embySortBy,
                ascending = !_sortDescending.value,
            )

            is GridSource.Favorites -> repository.items(
                parentId = null,
                startIndex = startIndex,
                limit = PAGE_SIZE,
                filters = FILTER_FAVORITE,
                includeItemTypes = source.type.embyIncludeItemTypes,
                // 收藏页接排序条后，排序参数必须传给服务端
                sortBy = _sortOption.value.embySortBy,
                ascending = !_sortDescending.value,
            )

            is GridSource.Search -> repository.items(
                parentId = null,
                startIndex = startIndex,
                limit = PAGE_SIZE,
                sortBy = SORT_BY_NAME,
                searchTerm = source.query,
                includeItemTypes = source.type.embyIncludeItemTypes,
            )
        }

    private fun currentItems(): List<MediaItem> = (_state.value as? UiState.Success)?.data.orEmpty()

    private companion object {
        const val PAGE_SIZE = 60
        const val FILTER_FAVORITE = "IsFavorite"
        const val SORT_BY_NAME = "SortName"
    }
}
