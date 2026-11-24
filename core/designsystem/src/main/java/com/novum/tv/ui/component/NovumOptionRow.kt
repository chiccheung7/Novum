package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 统一的选择列表行，供全站列表选择使用。
 *
 * 三种语义分开表达，不再靠"文字亮一点"区分：
 * - **可选行**（音轨/字幕/集/章节）：选中未聚焦 = 淡白底 + 左侧 3dp 竖条 + 行尾对勾，
 *   靠形状而不只是明度，三米外可辨；
 * - **调节行**（延迟/字号/高度，传 [adjustable]）：**不显示选中态**——它不是"选项"，
 *   而是"当前值"，获焦时值两侧出现 `‹ ›` 提示可左右调节；
 * - **普通行**：无选中态。
 *
 * 聚焦态全站统一白底黑字（TV 焦点规范里对比最强的一档）。
 *
 * @param text 选项文案。
 * @param selected 是否为当前生效项。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param value 行尾次要值；null 不渲染。
 * @param focusRequester 焦点索取器。
 * @param onFocused 焦点变化回调。
 * @param alwaysOpaque 是否保持文字完全不透明。
 * @param adjustable 是否为调节行：不显示选中态，获焦时给左右调节提示。
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
    adjustable: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // 调节行没有"选中"语义：值本身就是状态，选中标记只会让人以为还能 OK 切换
    val marked = selected && !adjustable
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocused?.invoke(it.isFocused) }
            .clip(NovumShapes.Card)
            // 底框两级：聚焦使用全站白底焦点语义，选中项用淡白底（面板本身是玻璃黑，
            // 旧版 Glass 叠上去几乎看不见选中）
            .background(
                when {
                    focused -> NovumPalette.Paper
                    marked -> NovumPalette.RowSelected
                    else -> Color.Transparent
                },
            )
            // 选中竖条画在行内左侧：形状标记比明度差更抗远距离与低对比屏幕
            .drawBehind {
                if (!marked || focused) return@drawBehind
                val barWidth = 3.dp.toPx()
                val inset = 8.dp.toPx()
                drawRoundRect(
                    color = NovumPalette.Paper,
                    topLeft = Offset(0f, inset),
                    size = Size(barWidth, size.height - inset * 2),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val textColor = when {
            focused -> NovumPalette.Ink
            alwaysOpaque -> NovumPalette.Paper
            else -> interactiveTextColor(focused = false, selected = selected)
        }
        // 调节行获焦时左右各给一个箭头，提示"这个值可以左右调"；
        // 非调节行保持纯文字，避免视觉噪音
        if (adjustable && focused) {
            Text(text = "‹", style = NovumType.Body, color = textColor)
            Spacer(modifier = Modifier.width(6.dp))
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
            // 调节行的"非默认值"提示：一个小圆点，区别于可选行的竖条 + 对勾
            if (adjustable && selected && !focused) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NovumPalette.Paper),
                )
            }
            Text(
                text = value,
                // 行尾次要值（时间码、剩余时长或开关）比行文案小一档。
                style = NovumType.Caption,
                color = textColor,
            )
        }
        if (adjustable && focused) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "›", style = NovumType.Body, color = textColor)
        }
        // 行尾对勾：选中未聚焦的最后一道形状提示（聚焦时整行反白已足够）
        if (marked && !focused) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = NovumIcons.Check,
                contentDescription = null,
                tint = NovumPalette.Paper,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
