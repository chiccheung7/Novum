package com.novum.tv.ui.screen.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.component.tvFocusScale
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.NovumTextField
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.screen.grid.GridViewModel
import com.novum.tv.ui.screen.grid.SearchType
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.delay

/** 输入防抖时长：停顿即搜，无需按钮确认。 */
private const val SEARCH_DEBOUNCE_MS = 500L

/** 人物命中展示上限：一行排得下，再多会挤掉下方的条目网格。 */
private const val PERSON_HIT_LIMIT = 8

/**
 * 搜索页：输入停顿 [SEARCH_DEBOUNCE_MS] 后查询全库，展示条目网格与人物命中。
 * 进入时焦点落在输入框，按确定拉起软键盘；输入框保持显示，方便继续输入。
 *
 * @param onOpenDetail 打开条目详情。
 * @param modifier 外部修饰符。
 * @param viewModel 搜索数据源的网格 ViewModel。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    onOpenPerson: (personId: String, personName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel = hiltViewModel(),
    personViewModel: PersonSearchViewModel = hiltViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(true) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val personHits by personViewModel.hits.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }
    // 未提交搜索时将初始焦点交给输入框，避免停留在顶栏。
    if (!submitted) {
        NovumInitialFocus(inputFocus)
    }

    // 进页即预热搜索目录（本地模糊匹配要先有全库片名），等打字时已就绪
    LaunchedEffect(Unit) { viewModel.warmSearch() }

    // 输入停顿即搜（防抖重启天然去抖）；清空回到未提交态；人物索引同节奏匹配
    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            submitted = false
            personViewModel.clear()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        submitted = true
        viewModel.search(keyword, SearchType.All)
        personViewModel.search(keyword)
    }

    // 本页 Back 返回首页；底部安全区由结果网格持有，宿主 bottomPadding=0。
    NovumContentScaffold(
        modifier = modifier,
        contentHorizontalPadding = 0.dp,
        bottomPadding = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
        ) {
            Text(
                text = stringResource(R.string.search_title),
                style = NovumType.Title,
                color = NovumPalette.Paper,
                modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal),
            )

            // 输入框保持显示，结果到达时不抢焦点或收起输入法；继续输入即可重新搜索。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovumDimens.SafeHorizontal),
                horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
                verticalAlignment = Alignment.Bottom,
            ) {
                NovumTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.search_label),
                    placeholder = stringResource(R.string.search_placeholder),
                    imeAction = ImeAction.Search,
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            val keyword = query.trim()
                            if (keyword.isNotEmpty()) {
                                submitted = true
                                viewModel.search(keyword, SearchType.All)
                                // 关键词未变化时防抖不会重启，这里必须同步刷新人物命中
                                personViewModel.search(keyword)
                            }
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocus)
                        .onFocusChanged { inputFocused = it.isFocused },
                )
            }

            if (!submitted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        style = NovumType.Body,

                        color = NovumPalette.PaperMuted,
                    )
                }
            } else {
                // 人物命中区位于网格上方：带「人物」小标题 + 头像卡，
                // 与下面的海报网格区分开（纯头像行容易被当成关键词条）
                if (personHits.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NovumDimens.SafeHorizontal),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.search_person_section),
                            style = NovumType.BodyStrong,
                            color = NovumPalette.PaperMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter)) {
                            personHits.take(PERSON_HIT_LIMIT).forEach { person ->
                                PersonHitChip(person = person, onClick = {
                                    onOpenPerson(person.personId, person.name)
                                })
                            }
                        }
                    }
                }
                when (val current = state) {
                    UiState.Loading -> NovumStateView(state = UiState.Loading, onRetry = {})

                    // 只有人物命中、没有条目命中时不算"无结果"：
                    // 搜演员名本来就该看到人物，摆一个空态会把结果盖掉
                    is UiState.Empty -> if (personHits.isEmpty()) NovumStateView(
                        state = current,
                        onRetry = {
                            val keyword = query.trim()
                            if (keyword.isNotEmpty()) {
                                viewModel.search(keyword, SearchType.All)
                                personViewModel.search(keyword)
                            }
                        },
                        emptyMessage = stringResource(R.string.search_empty),
                    )

                    is UiState.Error -> NovumStateView(
                        state = current,
                        onRetry = {
                            val keyword = query.trim()
                            if (keyword.isNotEmpty()) {
                                viewModel.search(keyword, SearchType.All)
                                personViewModel.search(keyword)
                            }
                        },
                    )

                    is UiState.Success -> PosterGrid(
                        items = current.data,
                        onOpenDetail = onOpenDetail,
                        onLoadMore = viewModel::loadMore,
                    )
                }
            }

        }
    }
}

/**
 * 人物命中卡：圆形头像 + 名字（本地索引）；tvFocusScale 提供聚焦反馈。
 */
@Composable
private fun PersonHitChip(
    person: com.novum.tv.data.repository.PersonRef,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .tvFocusScale()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 无头像的人物不再留空白圆：首字 + 姓名哈希底色占位
        com.novum.tv.ui.component.NovumAvatar(
            url = person.imageUrl,
            name = person.name,
            size = 88.dp,
        )
        Text(
            text = person.name,
            style = NovumType.Label,
            color = NovumPalette.Paper,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
