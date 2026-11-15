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
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 媒体库目录：16:9 库卡网格，点击进入该库条目页。
 * 卡宽由 LIBRARY_CARD_WIDTH 控制，列数随可用宽度计算。
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
        when (val current = state) {
            is UiState.Loading -> NovumStateView(state = current, onRetry = {})

            is UiState.Empty -> NovumStateView(state = current, onRetry = {})

            is UiState.Error -> NovumStateView(state = current, onRetry = viewModel::reload)

            is UiState.Success -> LibraryList(
                libraries = current.data,
                firstFocus = firstFocus,
                onOpenLibrary = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun LibraryList(
    libraries: List<MediaLibrary>,
    firstFocus: FocusRequester,
    onOpenLibrary: (libraryId: String, libraryName: String) -> Unit,
) {
    // 首焦 = 第一张库卡
    NovumInitialFocus(firstFocus)
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.library_title),
            style = NovumType.Title,
            color = NovumPalette.Paper,
            modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal),
        )
        Spacer(modifier = Modifier.height(NovumDimens.TitleToRow))
        // 网格自己吃满剩余高度并内部滚动（标题固定，不随网格滚出屏幕）
        LibraryGrid(
            libraries = libraries,
            firstFocus = firstFocus,
            onOpenLibrary = onOpenLibrary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 库卡网格：官方标准卡统一口径（[NovumDimens.CardWidth] 268dp / 16:9），
 * 列数按卡宽自适应（1280dp 内容区 4 列，960dp 3 列），出血按标准卡实际
 * 尺寸反推（不手写常量）。
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
        // 库卡宽 260dp，在目标画布上可并排展示四张。
        val cardWidth = LIBRARY_CARD_WIDTH
        val columns = ((contentWidth + gap) / (cardWidth + gap))
            .toInt()
            .coerceIn(2, 6)
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
                modifier = Modifier
                    .fillMaxSize()

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

/** 媒体库卡宽度。 */
private val LIBRARY_CARD_WIDTH = 260.dp
