package com.novum.tv.ui.screen.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumSkeletonPosterGrid
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.screen.grid.SortBar
import com.novum.tv.ui.screen.grid.GridSource
import com.novum.tv.ui.screen.grid.GridViewModel
import com.novum.tv.ui.screen.grid.SearchType
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 收藏列表页。
 *
 * 使用服务端 Filters=IsFavorite 查询；网格、焦点和分页行为复用 [PosterGrid]。
 * 展示全库收藏，首焦落在网格首卡。
 *
 * @param onOpenDetail 打开条目详情。
 * @param modifier 外部修饰符。
 * @param viewModel 收藏数据源的网格 ViewModel。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FavoritesScreen(
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    // 排序：收藏页复用网格页 SortBar / GridViewModel 排序能力
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start(GridSource.Favorites(SearchType.All)) }

    // 回到收藏页时静默刷新，以反映详情页的取消收藏操作。
    // 不进入 Loading 态；失败时保留已有内容。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshFavorites()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 本页 Back 返回首页。底部安全区由结果网格持有，宿主 bottomPadding=0，避免双重留白。
    NovumContentScaffold(
        modifier = modifier,
        contentHorizontalPadding = 0.dp,
        bottomPadding = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovumDimens.SafeHorizontal),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = stringResource(R.string.favorites_title),
                    style = NovumType.Title,
                    color = NovumPalette.Paper,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (totalCount > 0) {
                    Text(
                        text = stringResource(R.string.library_item_count, totalCount),
                        style = NovumType.Caption,
                        color = NovumPalette.PaperMuted,
                    )
                }
            }

            // 排序条提供片名、年份和评分切换。
            SortBar(
                sortOption = sortOption,
                sortDescending = sortDescending,
                onSortOption = viewModel::setSortOption,
            )

            when (val current = state) {
                UiState.Loading -> NovumStateView(
                    state = UiState.Loading,
                    onRetry = {},
                    loading = { NovumSkeletonPosterGrid(rows = 2) },
                )

                is UiState.Empty -> NovumStateView(
                    state = current,
                    onRetry = viewModel::retry,
                    emptyMessage = stringResource(R.string.favorites_empty),
                )

                is UiState.Error -> NovumStateView(state = current, onRetry = viewModel::retry)

                is UiState.Success -> PosterGrid(
                    items = current.data,
                    onOpenDetail = onOpenDetail,
                    onLoadMore = viewModel::loadMore,
                    // 首焦落网格首卡（芯片行已移除，本页唯一抓焦源）
                    focusOnEnter = true,
                )
            }

        }
    }
}
