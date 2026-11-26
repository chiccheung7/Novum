package com.novum.tv.ui.screen.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.model.MediaLibrary
import com.novum.tv.ui.component.COLUMN_BOTTOM_SETTLE_DEAD_ZONE
import com.novum.tv.ui.component.LANDSCAPE_ASPECT
import com.novum.tv.ui.component.NovumCard
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumGridFocusScrollScope
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.NovumSkeletonCardGrid
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.mediaCardWidth
import com.novum.tv.ui.component.mediaGridColumns
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 媒体库目录：16:9 库卡网格，点击进入该库条目页。
 * 卡宽与首页横卡共用标准规格，列数随可用宽度计算。
 * 首列静止位与 [NovumDimens.SafeHorizontal] 对齐。
 *
 * @param onOpenLibrary 打开媒体库网格，参数为库 id 与名称。
 * @param modifier 外部修饰符。
 * @param viewModel 页面 ViewModel。
 */
@Composable
fun LibrariesScreen(
    onOpenLibrary: (libraryId: String, libraryName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibrariesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }

    // 全出血宿主（与首页同款）：安全区由标题行与网格 contentPadding 自管；
    // 底部归网格 contentPadding 持有（宿主 bottomPadding=0，避免 54dp 死区）
    NovumContentScaffold(
        modifier = modifier,
        contentHorizontalPadding = 0.dp,
        bottomPadding = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.library_title),
                style = NovumType.Title,
                color = NovumPalette.Paper,
                modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal),
            )
            Spacer(modifier = Modifier.height(NovumDimens.TitleToRow))
            when (val current = state) {
                UiState.Loading -> NovumSkeletonCardGrid(
                    aspectRatio = LANDSCAPE_ASPECT,
                    rows = 2,
                    modifier = Modifier.weight(1f),
                )

                is UiState.Empty -> NovumStateView(state = current, onRetry = {})

                is UiState.Error -> NovumStateView(state = current, onRetry = viewModel::reload)

                is UiState.Success -> {
                    // 首焦只在库卡到位后绑定；页标题在所有状态中保持同一位置。
                    NovumInitialFocus(firstFocus)
                    LibraryGrid(
                        libraries = current.data,
                        firstFocus = firstFocus,
                        onOpenLibrary = onOpenLibrary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
/**
 * 库卡网格：与首页续播、详情选集共用 [NovumDimens.CardWidth] 目标宽度。
 * 列数与实际卡宽由共享布局函数计算，出血按同一实际尺寸反推。
 */
@Composable
private fun LibraryGrid(
    libraries: List<MediaLibrary>,
    firstFocus: FocusRequester,
    onOpenLibrary: (libraryId: String, libraryName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = NovumDimens.CardGap
        // 内容区宽（68dp 对齐线内侧）→ 列数
        val contentWidth = maxWidth - NovumDimens.SafeHorizontal * 2
        val columns = mediaGridColumns(contentWidth.value, NovumDimens.CardWidth.value)
        val cardWidth = mediaCardWidth(contentWidth, NovumDimens.CardWidth)
        // 标准卡实际高度（16:9 图区 + 文字区）→ 出血
        val cardHeight = cardWidth / LANDSCAPE_ASPECT + NovumDimens.CardTextBlockMaxHeight(1)
        val bleed = NovumFocusTokens.bleed(
            if (cardWidth > cardHeight) cardWidth else cardHeight,
        )
        val rowGap = if (gap > bleed) gap else bleed

        // 底边滚动余量包含图下文字块，保证整卡进入视口；顶边保留聚焦出血。
        NovumGridFocusScrollScope(
            topMargin = bleed,
            bottomMargin = NovumDimens.CardImageToTextGap + NovumDimens.CardTextBlockMaxHeight(1) + bleed +
                // 落地回摆死区余量（冻结点允许多沉一个死区，文字不被裁）
                COLUMN_BOTTOM_SETTLE_DEAD_ZONE,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                // 首列卡静止位 = 68dp 对齐线；上下留出血：首/末行聚焦放大与漫射
                // 阴影不被网格视口裁切
                contentPadding = PaddingValues(
                    start = NovumDimens.SafeHorizontal,
                    end = NovumDimens.SafeHorizontal,
                    top = bleed,
                    bottom = NovumDimens.SafeVertical,
                ),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(rowGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items = libraries, key = { _, library -> library.id }) { index, library ->
                    NovumCard(
                        title = library.name,
                        subtitle = library.itemCount
                            ?.takeIf { it > 0 }
                            ?.let { stringResource(R.string.library_item_count, it) },
                        width = cardWidth,
                        aspectRatio = LANDSCAPE_ASPECT,
                        imageUrl = library.imageUrl,
                        onClick = { onOpenLibrary(library.id, library.name) },
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}
