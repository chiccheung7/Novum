package com.novum.tv.ui.screen.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.component.NovumChip
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumSkeletonPosterGrid
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 媒体库下钻网格页，支持片名、年份和评分排序。
 *
 * 网关忽略 IsPlayed/IsUnplayed，故不提供观看状态筛选。收藏与搜索使用各自固定查询。
 *
 * 交互：
 * - 首焦落第一张海报，上键可进入排序芯片。
 * - 切换排序回到第一页，焦点保持在芯片上。
 * - 接近末尾时加载下一页；Back 返回媒体库目录。
 *
 * @param libraryId 媒体库 id。
 * @param title 媒体库名称。
 * @param onOpenDetail 打开条目详情。
 * @param modifier 外部修饰符。
 * @param viewModel 页面 ViewModel。
 */
@Composable
fun GridScreen(
    libraryId: String,
    title: String,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()

    LaunchedEffect(libraryId) { viewModel.start(GridSource.Library(libraryId)) }

    // 宿主全宽，标题和排序条使用 68dp 对齐线，网格自行管理 contentPadding。
    // 页头随内容滚动；底部安全区仅由网格持有，避免双重留白。
    NovumContentScaffold(
        modifier = modifier,
        contentHorizontalPadding = 0.dp,
        bottomPadding = 0.dp,
    ) {
        // 页头内容（Success 态作为 header 槽传入；Loading/Empty 态直接铺）
        @Composable
        fun GridHeader() {
            // 网格 contentPadding 已包含安全边距，页头不能重复添加，否则标题与卡片不对齐。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = title,
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
            SortBar(
                sortOption = sortOption,
                sortDescending = sortDescending,
                onSortOption = viewModel::setSortOption,
                // 网格 contentPadding 已含 68dp，header 内不再重复加
                applyHorizontalPadding = false,
            )
        }

        when (val current = state) {
            UiState.Loading -> Column(
                modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal),
                verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
            ) {
                GridHeader()
                NovumStateView(
                    state = UiState.Loading,
                    onRetry = {},
                    loading = { NovumSkeletonPosterGrid() },
                )
            }

            is UiState.Empty -> NovumStateView(
                state = current,
                onRetry = viewModel::retry,
                emptyMessage = stringResource(R.string.library_empty),
            )

            is UiState.Error -> NovumStateView(state = current, onRetry = viewModel::retry)

            is UiState.Success -> PosterGrid(
                items = current.data,
                onOpenDetail = onOpenDetail,
                onLoadMore = viewModel::loadMore,
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter)) {
                        GridHeader()
                    }
                },
            )
        }
    }
}

/**
 * 排序条：片名、年份和评分三枚芯片，焦点与选中态由 [NovumChip] 管理。
 *
 * @param sortOption 当前排序。
 * @param onSortOption 切换排序。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SortBar(
    sortOption: SortOption,
    sortDescending: Boolean,
    onSortOption: (SortOption) -> Unit,
    /**
     * 是否自带水平安全边距：独立使用时传 true，作为网格页头时传 false。
     * 网格 contentPadding 已包含安全边距，重复添加会让排序标签缩进过多。
     */
    applyHorizontalPadding: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (applyHorizontalPadding) {
                    Modifier.padding(horizontal = NovumDimens.SafeHorizontal)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 年份排最前，默认从新到旧；选中标签以 ↑/↓ 指示方向，再次点按翻转升降。
        listOf(SortOption.Year, SortOption.Name, SortOption.Rating).forEach { option ->
            val selected = option == sortOption
            val arrow = if (selected) {
                if (sortDescending) " ↓" else " ↑"
            } else {
                ""
            }
            // 每枚芯片持有稳定 FocusRequester。排序异步重查会重建网格和芯片节点，
            // 因此按下后逐帧重试索焦，任一成功即停止。
            val chipFocus = remember(option) { FocusRequester() }
            val scope = rememberCoroutineScope()
            NovumChip(
                label = chipLabel(option) + arrow,
                selected = selected,
                focusRequester = chipFocus,
                onClick = {
                    onSortOption(option)
                    scope.launch {
                        repeat(FOCUS_RESTORE_FRAMES) {
                            withFrameNanos { }
                            if (runCatching { chipFocus.requestFocus() }.isSuccess) {
                                return@launch
                            }
                        }
                    }
                },
            )
        }
    }
}

/** 排序选项 → 文案。 */
@Composable
internal fun chipLabel(option: SortOption): String = when (option) {
    SortOption.Name -> stringResource(R.string.sort_name)
    SortOption.Year -> stringResource(R.string.sort_year)
    SortOption.Rating -> stringResource(R.string.sort_rating)
}

/** 排序后恢复焦点的逐帧重试次数（覆盖异步重查 + 列表重建）。 */
private const val FOCUS_RESTORE_FRAMES = 10