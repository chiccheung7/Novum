package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 统一的选择列表行，供全站列表选择使用。
 *
 * 选中态由文字亮度和浅玻璃底表达；聚焦态为白底黑字。
 *
 * @param text 选项文案。
 * @param selected 是否为当前生效项。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param value 行尾次要值；null 不渲染。
 * @param focusRequester 焦点索取器。
 * @param onFocused 焦点变化回调。
 * @param alwaysOpaque 是否保持文字完全不透明。
 */
@Composable
fun NovumOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    alwaysOpaque: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocused?.invoke(it.isFocused) }
            .clip(NovumShapes.Card)
            // 底框两级：聚焦使用全站白底焦点语义，选中项使用浅玻璃底。
            .background(
                when {
                    focused -> NovumPalette.Paper
                    selected -> NovumPalette.Glass
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val textColor = when {
            focused -> NovumPalette.Ink
            alwaysOpaque -> NovumPalette.Paper
            else -> interactiveTextColor(focused = false, selected = selected)
        }
        Text(
            text = text,
            style = NovumType.Body,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                // 行尾次要值（时间码、剩余时长或开关）比行文案小一档。
                style = NovumType.Caption,
                color = textColor,
            )
        }
    }
}
