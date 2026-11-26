package com.novum.tv.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.component.NovumChip
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.NovumSkeletonBlock
import com.novum.tv.ui.component.NovumSkeletonPosterGrid
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.NovumTextField
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.component.mediaGridColumns
import com.novum.tv.ui.component.tvFocusScale
import com.novum.tv.ui.screen.grid.GridSource
import com.novum.tv.ui.screen.grid.GridViewModel
import com.novum.tv.ui.screen.grid.SearchType
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import com.novum.tv.data.repository.PersonRef
import kotlinx.coroutines.delay

/** 输入防抖时长：停顿即搜，无需确认按钮。 */
private const val SEARCH_DEBOUNCE_MS = 500L

/** 全部分类中预览的人物数；“全部人物”进入同页完整结果网格。 */
private const val PERSON_PREVIEW_LIMIT = 8

private enum class SearchCategory(val labelRes: Int) {
    All(R.string.search_category_all),
    Movie(R.string.search_category_movies),
    Series(R.string.search_category_series),
    People(R.string.search_category_people),
}

/**
 * 搜索页：影片与人物分类筛选、最近搜索，以及可通过遥控器浏览的结果。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    onOpenPerson: (personId: String, personName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel = hiltViewModel(),
    personViewModel: PersonSearchViewModel = hiltViewModel(),
    historyViewModel: SearchHistoryViewModel = hiltViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(SearchCategory.All) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val personHits by personViewModel.hits.collectAsStateWithLifecycle()
    val peopleLoading by personViewModel.loading.collectAsStateWithLifecycle()
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
        fun searchPeople() {
            if (force) personViewModel.search(normalized)
            else personViewModel.start(normalized)
        }
        when (selected) {
            SearchCategory.People -> {
                searchPeople()
            }
            SearchCategory.All -> {
                searchMedia(SearchType.All)
                searchPeople()
            }
            SearchCategory.Movie -> {
                searchMedia(SearchType.Movie)
                personViewModel.clear()
            }
            SearchCategory.Series -> {
                searchMedia(SearchType.Series)
                personViewModel.clear()
            }
        }
    }

    LaunchedEffect(query, category) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            submitted = false
            personViewModel.clear()
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
            } else if (category == SearchCategory.People) {
                PeopleResults(
                    people = personHits,
                    loading = peopleLoading,
                    onOpenPerson = { onOpenPerson(it.personId, it.name) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    if (category == SearchCategory.All && personHits.isNotEmpty()) {
                        PersonPreview(
                            people = personHits,
                            onOpenPerson = { onOpenPerson(it.personId, it.name) },
                            onViewAll = { category = SearchCategory.People },
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (val current = state) {
                            UiState.Loading -> NovumSkeletonPosterGrid()
                            is UiState.Empty -> if (category != SearchCategory.All || personHits.isEmpty()) {
                                NovumStateView(
                                    state = current,
                                    onRetry = { searchNow(query, category) },
                                    emptyMessage = stringResource(R.string.search_empty),
                                )
                            }
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

@Composable
private fun PersonPreview(
    people: List<PersonRef>,
    onOpenPerson: (PersonRef) -> Unit,
    onViewAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NovumDimens.SafeHorizontal),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.search_person_section),
                style = NovumType.BodyStrong,
                color = NovumPalette.PaperMuted,
            )
            Spacer(Modifier.weight(1f))
            if (people.size > PERSON_PREVIEW_LIMIT) {
                NovumChip(
                    label = stringResource(R.string.search_person_all),
                    selected = false,
                    onClick = onViewAll,
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
        ) {
            people.take(PERSON_PREVIEW_LIMIT).forEach { person ->
                PersonHitChip(person = person, onClick = { onOpenPerson(person) })
            }
        }
    }
}

@Composable
private fun PeopleResults(
    people: List<PersonRef>,
    loading: Boolean,
    onOpenPerson: (PersonRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.search_person_count, people.size),
            style = NovumType.BodyStrong,
            color = NovumPalette.PaperMuted,
            modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal),
        )
        when {
            loading -> PeopleSkeletonGrid(modifier = Modifier.weight(1f))
            people.isEmpty() -> NovumStateView(
                state = UiState.Empty(),
                onRetry = {},
                emptyMessage = stringResource(R.string.search_empty),
            )
            else -> BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val contentWidth = maxWidth - NovumDimens.SafeHorizontal * 2
                val columns = mediaGridColumns(contentWidth.value)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NovumDimens.SafeHorizontal,
                        end = NovumDimens.SafeHorizontal,
                        top = NovumDimens.FocusBleed,
                        bottom = NovumDimens.SafeVertical,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
                    verticalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
                ) {
                    items(people, key = PersonRef::personId) { person ->
                        PersonHitChip(
                            person = person,
                            onClick = { onOpenPerson(person) },
                            width = null,
                        )
                    }
                }
            }
        }
    }
}

/** 人物网格的圆形占位，与头像和姓名卡片的实际结构一致。 */
@Composable
private fun PeopleSkeletonGrid(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentWidth = maxWidth - NovumDimens.SafeHorizontal * 2
        val columns = mediaGridColumns(contentWidth.value)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NovumDimens.SafeHorizontal,
                end = NovumDimens.SafeHorizontal,
                top = NovumDimens.FocusBleed,
                bottom = NovumDimens.SafeVertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
            verticalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
            userScrollEnabled = false,
        ) {
            items(columns * 2, key = { it }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NovumSkeletonBlock(modifier = Modifier.size(88.dp), shape = CircleShape)
                    NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.58f), height = 21.dp)
                }
            }
        }
    }
}

/** Person search card: portrait and name, with visible D-pad focus feedback. */
@Composable
private fun PersonHitChip(
    person: PersonRef,
    onClick: () -> Unit,
    width: androidx.compose.ui.unit.Dp? = 124.dp,
) {
    Column(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .tvFocusScale()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.novum.tv.ui.component.NovumAvatar(
            url = person.imageUrl,
            name = person.name,
            size = 88.dp,
        )
        Text(
            text = person.name,
            style = NovumType.Caption,
            color = NovumPalette.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
