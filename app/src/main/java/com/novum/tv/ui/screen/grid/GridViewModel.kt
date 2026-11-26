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
import kotlinx.coroutines.Job
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
    private var loadJob: Job? = null
    /** 请求序号：旧请求即使没有被及时取消，也不允许写回状态。 */
    private var requestSeq = 0

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
     * 首次选择排序档按该档的默认方向（[SortOption.ascending]：片名升序、年份/评分降序），
     * 再次点按同档时翻转方向。
     *
     * @param option 目标排序。
     */
    fun setSortOption(option: SortOption) {
        if (_sortOption.value == option && userSorted) {
            // 同档再点（用户已主动排过序）：翻转升降
            _sortDescending.value = !_sortDescending.value
        } else {
            // 换档首点，或首次点当前默认档：用该档默认方向——此前恒降序让「片名」
            // 首点按 Z→A 排，而 SortOption.ascending 字段从未被消费
            _sortOption.value = option
            _sortDescending.value = !option.ascending
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
        if (source !is GridSource.Favorites) return
        // 与首屏/分页共用同一请求队列：返回页时若还有在途 loadMore，
        // 不能让两个协程同时改 total/startIndex 和列表状态。
        val hasContent = _state.value is UiState.Success || _state.value is UiState.Empty
        load(reset = true, silent = hasContent)
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

    /**
     * 发起请求：新请求一律取消在途请求，并通过序号拦住已无法取消的迟达结果。
     *
     * @param reset 是否回到第一页。
     * @param silent 静默刷新（收藏页返回）：不进入 Loading，失败保留已有内容。
     */
    private fun load(reset: Boolean, silent: Boolean = false) {
        val current = source ?: return
        loadJob?.cancel()
        val seq = ++requestSeq
        val start = if (reset) 0 else startIndex
        loading = true
        // 静默刷新保留现有列表及游标，等成功后一起替换。否则连续刷新取消
        // 前一次请求后，第二次失败只能恢复到已被清零的游标。
        if (reset && !silent) {
            startIndex = 0
            total = 0
            _state.value = UiState.Loading
        }
        loadJob = viewModelScope.launch {
            when (val result = fetch(current, start)) {
                is NovumResult.Success -> {
                    if (seq != requestSeq) return@launch
                    val page = result.value
                    total = page.total
                    // 总数可能晚于媒体删除更新；空页已没有可继续请求的内容。
                    startIndex = if (page.items.isEmpty()) total else start + page.items.size
                    _totalCount.value = page.total
                    // 列表在分页之间可能发生新增/重排，重叠条目不能产生重复 LazyGrid key。
                    // 游标仍按服务器返回数推进，而不是去重后的可见卡片数。
                    val merged = (if (reset) page.items else currentItems() + page.items).distinctBy { it.id }
                    _state.value = if (merged.isEmpty()) UiState.Empty() else UiState.Success(merged)
                }

                is NovumResult.Failure -> {
                    if (seq != requestSeq) return@launch
                    when {
                        reset && !silent -> _state.value = UiState.Error(result.error)
                        !reset -> _state.value = UiState.Success(currentItems())
                        else -> Unit
                    }
                }
            }
            if (seq == requestSeq) loading = false
        }
    }

    private suspend fun fetch(
        source: GridSource,
        start: Int,
    ): NovumResult<com.novum.tv.model.MediaPage> =
        when (source) {
            is GridSource.Library -> repository.items(
                parentId = source.libraryId,
                startIndex = start,
                limit = PAGE_SIZE,
                sortBy = _sortOption.value.embySortBy,
                ascending = !_sortDescending.value,
            )

            is GridSource.Favorites -> repository.items(
                parentId = null,
                startIndex = start,
                limit = PAGE_SIZE,
                filters = FILTER_FAVORITE,
                includeItemTypes = source.type.embyIncludeItemTypes,
                // 收藏页接排序条后，排序参数必须传给服务端
                sortBy = _sortOption.value.embySortBy,
                ascending = !_sortDescending.value,
            )

            is GridSource.Search -> repository.items(
                parentId = null,
                startIndex = start,
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
