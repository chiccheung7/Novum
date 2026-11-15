package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 区块标题：左侧标题 + 右侧槽位（纯文本计数或可聚焦动作）。
 *
 * @param title 区块标题。
 * @param modifier 外部修饰符。
 * @param trailing 右侧次要信息；与 [trailingContent] 二选一。
 * @param trailingContent 右侧可组合槽位；优先于 [trailing]。
 * @param horizontalPadding 标题行水平边距；默认由 [NovumRow.edgePadding] 提供。
 */
@Composable
fun NovumSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = NovumDimens.FocusBleed,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 标题与 NovumRow 内容左边界对齐；标题到首卡间距为 12dp。右侧留出出血，防止聚焦按钮被视口裁切。
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = NovumDimens.TitleToRow,
            ),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = NovumType.Section,
            color = NovumPalette.Paper,
        )
        if (trailingContent != null) {
            trailingContent()
        } else if (trailing != null) {
            Text(
                text = trailing,
                style = NovumType.Caption,
                color = NovumPalette.PaperMuted,
            )
        }
    }
}
