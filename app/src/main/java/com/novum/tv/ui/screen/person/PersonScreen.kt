package com.novum.tv.ui.screen.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumSkeletonPosterGrid
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 人物作品页，从详情页演职员头像进入。
 *
 * 人物名标题加作品海报网格，数据使用 PersonIds 过滤并按首播/年份倒序。
 * 复用 [PosterGrid]；一次性加载全部作品，onLoadMore 为空实现。
 *
 * @param personName 人物名称，用作页标题。
 * @param onOpenDetail 打开条目详情。
 * @param onBack 返回详情页。
 * @param modifier 外部修饰符。
 * @param viewModel 页面 ViewModel。
 */
@Composable
fun PersonScreen(
    personName: String,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 人物页 Back 回详情页
    BackHandler(onBack = onBack)

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
                    text = personName,
                    style = NovumType.Title,
                    color = NovumPalette.Paper,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1f))
                val count = (state.detail as? UiState.Success)?.data?.size ?: 0
                if (count > 0) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            com.novum.tv.core.designsystem.R.string.library_item_count,
                            count,
                        ),
                        style = NovumType.Caption,
                        color = NovumPalette.PaperMuted,
                    )
                }
            }

            when (val current = state.detail) {
                UiState.Loading -> NovumStateView(
                    state = UiState.Loading,
                    onRetry = {},
                    loading = { NovumSkeletonPosterGrid(rows = 2) },
                )

                is UiState.Empty -> NovumStateView(
                    state = current,
                    onRetry = viewModel::load,
                    emptyMessage = androidx.compose.ui.res.stringResource(
                        com.novum.tv.core.designsystem.R.string.favorites_empty,
                    ),
                )

                is UiState.Error -> NovumStateView(state = current, onRetry = viewModel::load)

                is UiState.Success -> PosterGrid(
                    items = current.data,
                    onOpenDetail = onOpenDetail,
                    onLoadMore = {},
                )
            }
        }
    }
}
