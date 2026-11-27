package com.novum.tv.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumDimens
import kotlin.math.floor
import kotlin.math.roundToInt

/** Keep cards close to their preferred size; wide displays add columns instead of enlarging cards. */
fun mediaGridColumns(
    contentWidth: Float,
    preferredWidth: Float = NovumDimens.PosterCardWidth.value,
    gap: Float = NovumDimens.CardGap.value,
): Int {
    if (!contentWidth.isFinite() || contentWidth <= 0f) return 1
    val target = preferredWidth.takeIf { it.isFinite() && it > 0f } ?: NovumDimens.PosterCardWidth.value
    val spacing = gap.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val fittingColumns = ((contentWidth + spacing) / (1f + spacing)).toInt().coerceAtLeast(1)
    return ((contentWidth + spacing) / (target + spacing)).roundToInt().coerceIn(1, fittingColumns)
}

/** Select columns that keep cards at least the preferred width, up to [maxColumns]. */
fun mediaGridColumnsForMinimumWidth(
    contentWidth: Float,
    preferredWidth: Float,
    gap: Float = NovumDimens.CardGap.value,
    maxColumns: Int = Int.MAX_VALUE,
): Int {
    if (!contentWidth.isFinite() || contentWidth <= 0f) return 1
    val target = preferredWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
    val spacing = gap.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val availableColumns = floor((contentWidth + spacing) / (target + spacing)).toInt().coerceAtLeast(1)
    return availableColumns.coerceAtMost(maxColumns.coerceAtLeast(1))
}

/** Width that exactly fills [columns] within the available grid content area. */
fun mediaGridCardWidth(
    contentWidth: Dp,
    columns: Int,
    gap: Dp = NovumDimens.CardGap,
): Dp {
    val available = contentWidth.value.takeIf { it.isFinite() && it > 0f } ?: 1f
    val spacing = gap.value.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val safeColumns = columns.coerceAtLeast(1)
    return ((available - spacing * (safeColumns - 1)) / safeColumns).coerceAtLeast(1f).dp
}

/** Same card width for rows, result grids, their loading states and image requests. */
fun mediaCardWidth(
    contentWidth: Dp,
    preferredWidth: Dp = NovumDimens.PosterCardWidth,
    gap: Dp = NovumDimens.CardGap,
): Dp {
    val available = contentWidth.value.takeIf { it.isFinite() && it > 0f } ?: 1f
    val spacing = gap.value.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val columns = mediaGridColumns(available, preferredWidth.value, spacing)
    return ((available - spacing * (columns - 1)) / columns).coerceAtLeast(1f).dp
}

/** Poster rows share grid dimensions; landscape rows retain their preferred width. */
@Composable
fun rememberMediaCardWidth(aspectRatio: Float = POSTER_ASPECT): Dp {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val target = if (aspectRatio == POSTER_ASPECT) NovumDimens.PosterCardWidth else NovumDimens.CardWidth
    return remember(window.width, density.density, target) {
        val available = with(density) { window.width.toDp() } - NovumDimens.SafeHorizontal * 2
        mediaRowCardWidth(available, aspectRatio)
    }
}

/** 横卡行保留约一成的加宽，不再因四舍五入为四列而把新尺寸压回 271dp。 */
fun mediaRowCardWidth(contentWidth: Dp, aspectRatio: Float): Dp =
    if (aspectRatio == POSTER_ASPECT) {
        mediaCardWidth(contentWidth)
    } else {
        minOf((contentWidth.value.takeIf { it.isFinite() && it > 0f } ?: 1f).dp, NovumDimens.CardWidth)
    }
