package com.novum.tv.ui.component

import androidx.compose.ui.graphics.Color
import com.novum.tv.ui.theme.NovumPalette

/**
 * 交互组件的三态文字配色，供芯片、选项行和抽屉 tab 共用。
 *
 * 聚焦使用 [NovumPalette.Ink]（白底黑字），选中未聚焦使用 [NovumPalette.Paper]，未选中使用 [NovumPalette.PaperMuted]。
 */
internal fun interactiveTextColor(focused: Boolean, selected: Boolean): Color = when {
    focused -> NovumPalette.Ink
    selected -> NovumPalette.Paper
    else -> NovumPalette.PaperMuted
}

/**
 * 交互组件的三态底色（同上）：聚焦白底 / 其余透明（芯片用玻璃黑，见其自身）。
 *
 * @param focused 是否聚焦。
 * @param focusBackground 聚焦时的底色，默认 [NovumPalette.Paper]。
 */
internal fun interactiveContainerColor(
    focused: Boolean,
    focusBackground: Color = NovumPalette.Paper,
): Color = if (focused) focusBackground else Color.Transparent
