package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.component.NovumButton
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.common.userMessage
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 统一状态视图：加载中 / 空态 / 错误态。
 *
 * 只处理非成功态；`UiState.Success` 时什么都不渲染，由页面自己画内容。
 *
 * 加载态一律用骨架屏而不是「正在加载…」文字：首屏结构提前出现，
 * 数据到位时页面不发生整体跳变，也避免转圈/文字在电视上显得廉价。
 *
 * @param state 页面状态。
 * @param onRetry 错误态的重试回调。
 * @param modifier 外部修饰符。
 * @param emptyMessage 空态文案，默认「这里还没有内容」。
 * @param loading 自定义加载态骨架；默认是通用列表骨架。
 */
@Composable
fun NovumStateView(
    state: UiState<*>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    loading: @Composable () -> Unit = { NovumSkeletonRows() },
) {
    when (state) {
        UiState.Loading -> Box(modifier = modifier.fillMaxWidth()) { loading() }

        is UiState.Empty -> StateMessage(
            text = emptyMessage ?: stringResource(R.string.state_empty_message),
            modifier = modifier,
        )

        is UiState.Error -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NovumDimens.TextBlockGap),
        ) {
            // 错误态文案使用更大字号与 Danger 色，方便在远距离识别；加载态与空态保持中性色。
            Text(
                text = state.error.userMessage(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                style = NovumType.Section,
                color = NovumPalette.Danger,
                textAlign = TextAlign.Center,
            )
            if (state.retryable) {
                NovumButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.state_retry), style = NovumType.ButtonLg)
                }
            }
        }

        is UiState.Success -> Unit
    }
}

/** 单行居中的状态文案。 */
@Composable
private fun StateMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        // fillMaxWidth 让 textAlign.Center 作用于整个内容区域。
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        style = NovumType.Body,
        color = NovumPalette.PaperMuted,
        textAlign = TextAlign.Center,
    )
}
