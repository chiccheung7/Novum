package com.novum.tv.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 统一的选择列表行，供全站列表选择使用。
 *
 * 三种语义分开表达，不再靠"文字亮一点"区分：
 * - **可选行**（音轨/字幕/集/章节）：选中未聚焦 = 淡白底 + 左侧 4dp 竖条，
 *   靠形状而不只是明度，三米外可辨（不再叠加对勾：竖条已足够，
 *   两个形状标记只会互相削弱）；
 * - **调节行**（延迟/字号/高度，传 [adjustable]）：**不显示选中态**——它不是"选项"，
 *   而是"当前值"；值的左右各有一个固定宽度的 `‹ ›` 槽位，获焦时淡入，
 *   非默认值在行尾点一个小圆点。槽位恒占位，聚焦时文字不会左右跳。
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
 * @param enabled 是否可操作；false = 只展示不可聚焦（灰字、不进焦点链），
 *   用于「只有一条音轨」「没有字幕」这类没有选择余地的行。
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
    /** 有限档位时传总档数：行尾先画档位条，再显示数值（调节行专用）。 */
    segmentCount: Int? = null,
    /** 档位条当前档（0 起）。 */
    segmentLevel: Int = 0,
    enabled: Boolean = true,
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
                    // 只有"选项"聚焦才整行反白；调节行是"当前值"，反白会误导成可选中
                    focused && !adjustable -> NovumPalette.Paper
                    marked -> NovumPalette.RowSelected
                    else -> Color.Transparent
                },
            )
            .border(
                width = 2.dp,
                color = if (focused && adjustable) NovumPalette.Paper else Color.Transparent,
                shape = NovumShapes.Card,
            )
            // 选中竖条画在行内左侧：形状标记比明度差更抗远距离与低对比屏幕
            .drawBehind {
                if (!marked || focused) return@drawBehind
                val barWidth = 4.dp.toPx()
                val inset = 12.dp.toPx()
                drawRoundRect(
                    color = NovumPalette.Paper,
                    topLeft = Offset(0f, inset),
                    size = Size(barWidth, size.height - inset * 2),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
            // 不可操作的行不进入焦点链（clickable(enabled=false) 不产生焦点节点）
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        val textColor = when {
            !enabled -> NovumPalette.PaperFaint
            focused && !adjustable -> NovumPalette.Ink
            focused -> NovumPalette.Paper
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
            // 有限档位：先画档位条（"第几档/共几档"一眼可见），再显示数值。
            // 连续值（延迟/偏移）不传 segmentCount，只显示数值。
            if (segmentCount != null) {
                NovumSegmentBar(
                    count = segmentCount,
                    level = segmentLevel,
                    modifier = Modifier.padding(end = 14.dp),
                )
            }
            // 只有「连续值」调节行（延迟/偏移）才需要 ‹ › 提示：
            // 已经有档位条的行，条本身就在说「可以左右拨」，再叠箭头是重复噪音。
            val showChevrons = adjustable && segmentCount == null
            if (showChevrons) {
                AdjustChevron(direction = -1, visible = focused, color = textColor)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = value,
                // 行尾次要值（时间码、剩余时长或开关）比行文案小一档。
                style = NovumType.Caption,
                color = textColor,
            )
            if (showChevrons) {
                Spacer(modifier = Modifier.width(6.dp))
                AdjustChevron(direction = 1, visible = focused, color = textColor)
            }
        }
    }
}

/** 调节行 `‹ ›` 槽位宽度：恒定占位，聚焦淡入时文字不位移。 */
private val CHEVRON_SLOT = 22.dp

/**
 * 调节提示箭头：两条圆头斜线画出的 chevron。
 *
 * 用线条而不是 `‹›` 字形——字形在不同字体下的粗细/基线不一致，看着"生硬"；
 * 线条可以精确控制粗细（2dp）与圆头，和全站 1.8dp 图标笔触同族。
 *
 * @param direction -1 = 左（减小），1 = 右（增大）。
 * @param visible 是否显示（未聚焦时淡出，但保留占位）。
 * @param color 线条颜色。
 */
@Composable
private fun AdjustChevron(direction: Int, visible: Boolean, color: Color) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "chevronAlpha",
    )
    Box(
        modifier = Modifier.size(CHEVRON_SLOT).alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(width = 10.dp, height = 16.dp)) {
            val stroke = 2.dp.toPx()
            val left = size.width * 0.25f
            val right = size.width * 0.75f
            val top = size.height * 0.2f
            val bottom = size.height * 0.8f
            val middle = size.height * 0.5f
            if (direction < 0) {
                drawLine(color, Offset(right, top), Offset(left, middle), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(left, middle), Offset(right, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                drawLine(color, Offset(left, top), Offset(right, middle), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(right, middle), Offset(left, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}
