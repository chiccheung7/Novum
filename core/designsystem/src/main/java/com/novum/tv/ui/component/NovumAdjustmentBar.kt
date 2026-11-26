package com.novum.tv.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumPalette
import kotlin.math.max
import kotlin.math.min

/**
 * 播放抽屉调节行共用的控制条；焦点、方向键与实际值写回由外层设置行持有。
 *
 * [fraction] 为当前归一值；[centerFraction] 用于音画延迟等有正负方向的值。
 * 离散档位传 [stepCount]，较少的档位会显示刻度；不为每一毫秒绘制刻度。
 */
@Composable
internal fun NovumAdjustmentBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    stepCount: Int = 0,
    centerFraction: Float? = null,
    enabled: Boolean = true,
) {
    val center = centerFraction?.normalizedFraction(fallback = 0.5f)
    val current = fraction.normalizedFraction(fallback = center ?: 0f)
    val anchor = center ?: 0f
    val activeColor = if (enabled) NovumPalette.Paper else NovumPalette.PaperFaint
    val trackColor = NovumPalette.Border.copy(alpha = if (enabled) 1f else 0.6f)
    val steps = if (stepCount >= 2) stepCount - 2 else 0

    Canvas(
        modifier = modifier
            .size(width = 128.dp, height = 18.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f, steps)
                if (!enabled) disabled()
            },
    ) {
        // 两端各留出拇指半径，端点时圆形手柄也完整留在组件边界内。
        val thumbRadius = 5.dp.toPx()
        val startX = thumbRadius
        val endX = max(startX, size.width - thumbRadius)
        val trackWidth = endX - startX
        val y = size.height / 2f
        fun xAt(value: Float): Float = startX + trackWidth * value

        drawLine(
            color = trackColor,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (current != anchor) {
            drawLine(
                color = activeColor,
                start = Offset(xAt(anchor), y),
                end = Offset(xAt(current), y),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (stepCount in 2..10) {
            val filledStart = min(anchor, current)
            val filledEnd = max(anchor, current)
            repeat(stepCount) { index ->
                val tick = index.toFloat() / (stepCount - 1)
                val tickColor = if (tick in filledStart..filledEnd) {
                    NovumPalette.Ink.copy(alpha = 0.45f)
                } else {
                    NovumPalette.PaperFaint
                }
                drawCircle(
                    color = tickColor,
                    radius = 1.dp.toPx(),
                    center = Offset(xAt(tick), y),
                )
            }
        }
        center?.let { zero ->
            drawLine(
                color = NovumPalette.PaperMuted,
                start = Offset(xAt(zero), y - 6.dp.toPx()),
                end = Offset(xAt(zero), y + 6.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(
            color = activeColor,
            radius = thumbRadius,
            center = Offset(xAt(current), y),
        )
    }
}

/** 无效当前值回落至零位；无穷值按对应端点显示，避免向 Canvas 传入非有限坐标。 */
private fun Float.normalizedFraction(fallback: Float): Float =
    if (isNaN()) fallback else coerceIn(0f, 1f)
