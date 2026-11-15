package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 单选芯片：供筛选/排序条与播放器设置抽屉 tab 共用。
 *
 * 未选中为浅灰玻璃底与灰字；选中未聚焦为浅灰玻璃底与 Paper 字色；聚焦时白底黑字。芯片使用 Pill 形状且不缩放，焦点反馈由底色表达。
 *
 * @param label 芯片文案。
 * @param selected 是否选中。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param focusRequester 焦点索取器。
 * @param fixedHeight 固定高度；null 时自适应。
 */
@Composable
fun NovumChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    fixedHeight: Dp? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clip(NovumShapes.Pill)
            .background(
                when {
                    focused -> NovumPalette.Paper
                    else -> NovumPalette.Glass
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = CHIP_H_PADDING, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (fixedHeight != null) Arrangement.Center else Arrangement.Top,
    ) {
        Text(
            text = label,
            style = NovumType.Body,
            color = interactiveTextColor(focused = focused, selected = selected),
            maxLines = 1,
        )
    }
}

/** 芯片左右内边距：与顶栏页签一致。 */
private val CHIP_H_PADDING = 14.dp
