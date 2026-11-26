package com.novum.tv.ui.screen.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.component.NovumChip
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.NovumSkeletonPosterGrid
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.NovumTextField
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.screen.grid.GridSource
import com.novum.tv.ui.screen.grid.GridViewModel
import com.novum.tv.ui.screen.grid.SearchType
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.delay

/** 输入防抖时长：停顿即搜，无需确认按钮。 */
private const val SEARCH_DEBOUNCE_MS = 500L

private enum class SearchCategory(val labelRes: Int) {
    All(R.string.search_category_all),
    Movie(R.string.search_category_movies),
    Series(R.string.search_category_series),
}

/**
 * 搜索页：影片分类筛选、最近搜索，以及可通过遥控器浏览的结果。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel = hiltViewModel(),
    historyViewModel: SearchHistoryViewModel = hiltViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(SearchCategory.All) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentSearches by historyViewModel.recentSearches.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }

    if (!submitted) NovumInitialFocus(inputFocus)

    fun searchNow(keyword: String, selected: SearchCategory, force: Boolean = true) {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return
        submitted = true
        historyViewModel.record(normalized)
        fun searchMedia(type: SearchType) {
            if (force) viewModel.search(normalized, type)
            else viewModel.start(GridSource.Search(normalized, type))
        }
        searchMedia(
            when (selected) {
                SearchCategory.All -> SearchType.All
                SearchCategory.Movie -> SearchType.Movie
                SearchCategory.Series -> SearchType.Series
            },
        )
    }

    LaunchedEffect(query, category) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            submitted = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        // 去重由 ViewModel 的当前请求决定：返回保留分页，新进程的空 VM 仍会加载。
        searchNow(keyword, category, force = false)
    }

    NovumContentScaffold(
        modifier = modifier,
        contentHorizontalPadding = 0.dp,
        bottomPadding = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
        ) {
            Text(
                text = stringResource(R.string.search_title),
                style = NovumType.Title,
                color = NovumPalette.Paper,
                modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal),
            )

            NovumTextField(
                value = query,
                onValueChange = {
                    query = it
                },
                label = stringResource(R.string.search_label),
                placeholder = stringResource(R.string.search_placeholder),
                imeAction = ImeAction.Search,
                keyboardActions = KeyboardActions(
                    onSearch = { searchNow(query, category) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovumDimens.SafeHorizontal)
                    .focusRequester(inputFocus),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovumDimens.SafeHorizontal),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SearchCategory.entries.forEach { candidate ->
                    NovumChip(
                        label = stringResource(candidate.labelRes),
                        selected = candidate == category,
                        onClick = {
                            category = candidate
                        },
                    )
                }
            }

            if (!submitted) {
                SearchHistory(
                    searches = recentSearches,
                    onSelect = { selectedQuery ->
                        category = SearchCategory.All
                        query = selectedQuery
                        runCatching { inputFocus.requestFocus() }
                    },
                    onClear = historyViewModel::clear,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val current = state) {
                        UiState.Loading -> NovumSkeletonPosterGrid()
                        is UiState.Empty -> NovumStateView(
                            state = current,
                            onRetry = { searchNow(query, category) },
                            emptyMessage = stringResource(R.string.search_empty),
                        )
                        is UiState.Error -> NovumStateView(
                            state = current,
                            onRetry = { searchNow(query, category) },
                        )
                        is UiState.Success -> PosterGrid(
                            items = current.data,
                            onOpenDetail = onOpenDetail,
                            onLoadMore = viewModel::loadMore,
                            // 防抖结果不能打断正在输入或选择分类的遥控器焦点。
                            focusOnEnter = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistory(
    searches: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NovumDimens.SafeHorizontal),
        verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = NovumType.Caption,
                color = NovumPalette.PaperMuted,
            )
            Spacer(Modifier.weight(1f))
            if (searches.isNotEmpty()) {
                NovumChip(
                    label = stringResource(R.string.search_history_clear),
                    selected = false,
                    onClick = onClear,
                )
            }
        }
        if (searches.isEmpty()) {
            Text(
                text = stringResource(R.string.search_history_empty),
                style = NovumType.Body,
                color = NovumPalette.PaperMuted,
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                searches.forEach { term ->
                    NovumChip(label = term, selected = false, onClick = { onSelect(term) })
                }
            }
        }
    }
}
