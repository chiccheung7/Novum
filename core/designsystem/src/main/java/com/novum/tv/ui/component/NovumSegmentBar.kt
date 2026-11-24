package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/**
 * 档位条：把「第几档 / 共几档」画成 N 段小胶囊，当前档及左侧填白。
 *
 * 用于所有"有限档位"的调节项（字号/速度/透明度/界面大小…）。连续值
 * （延迟毫秒、时间偏移）不要用：段数会多到没有信息量，直接显示数值。
 *
 * @param count 总档数（≥2）。
 * @param level 当前档位下标（0 起，越界自动钳制）。
 * @param modifier 外部修饰符。
 * @param segmentHeight 单段高度。
 */
@Composable
internal fun NovumSegmentBar(
    count: Int,
    level: Int,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 6.dp,
) {
    val safeCount = count.coerceAtLeast(2)
    val current = level.coerceIn(0, safeCount - 1)
    // 10 档（透明度/密度）时收窄，整条不超过 ~130dp
    val segmentWidth = if (safeCount > 6) 10.dp else 14.dp
    val gap = if (safeCount > 6) 3.dp else 4.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(safeCount) { index ->
            Box(
                modifier = Modifier
                    .size(width = segmentWidth, height = segmentHeight)
                    .clip(NovumShapes.Pill)
                    .background(if (index <= current) NovumPalette.Paper else NovumPalette.Border),
            )
        }
    }
}
