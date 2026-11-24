package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 单选芯片：供筛选/排序条与播放器设置抽屉 tab 共用。
 *
 * 未选中 = 玻璃黑底 + 灰字；选中未聚焦 = **淡白底 + Paper 字**（不再画底部短线：
 * 短线和底色叠在一起反而显脏，底色本身已经足够区分）；聚焦时白底黑字。
 * 芯片使用 Pill 形状且不缩放，焦点反馈由底色表达。
 *
 * 高度固定 [CHIP_MIN_HEIGHT]（56dp）：官方焦点目标 48dp@960 换算到 1280 画布
 * 约 64dp，芯片作为页签/筛选条取一档略小的稳定高度，避免筛选行比内容行还抢眼。
 *
 * @param label 芯片文案。
 * @param selected 是否选中。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param focusRequester 焦点索取器。
 */
@Composable
fun NovumChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
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
                    selected -> NovumPalette.RowSelected
                    else -> NovumPalette.Glass
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = CHIP_H_PADDING, vertical = CHIP_V_PADDING),
        contentAlignment = Alignment.Center,
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
private val CHIP_H_PADDING = 20.dp

/** 芯片上下内边距（配合最小高度把文字垂直居中）。 */
private val CHIP_V_PADDING = 8.dp

/** 芯片最小高度：低于此值在 10ft 距离上焦点目标过小。 */
private val CHIP_MIN_HEIGHT = 52.dp
