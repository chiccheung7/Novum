package com.novum.tv.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/**
 * 骨架屏基础块：一块带呼吸动画的灰色占位。
 *
 * 性能要点：透明度动画值在 `graphicsLayer` 的绘制回调里读取，
 * 每帧只触发重绘，不触发重组——列表页骨架动辄十几个块，用 `by` 委托会直接掉帧。
 *
 * @param modifier 外部修饰符。
 * @param shape 圆角形状，默认徽章级小圆角。
 * @param width 固定宽度；为 null 时由 [modifier] 决定。
 * @param height 固定高度；为 null 时由 [modifier] 决定。
 */
@Composable
fun NovumSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = NovumShapes.Badge,
    width: Dp? = null,
    height: Dp? = null,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse = transition.animateFloat(
        initialValue = SKELETON_ALPHA_MIN,
        targetValue = SKELETON_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SKELETON_PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .graphicsLayer { alpha = pulse.value }
            .clip(shape)
            .background(NovumPalette.Surface),
    )
}

/**
 * 通用列表骨架：若干「标题 + 行」的占位，用于设置、媒体库、搜索等列表型页面。
 *
 * @param modifier 外部修饰符。
 * @param rows 行数。
 */
@Composable
fun NovumSkeletonRows(
    modifier: Modifier = Modifier,
    rows: Int = DEFAULT_SKELETON_ROWS,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
    ) {
        NovumSkeletonBlock(width = 180.dp, height = 20.dp)
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovumSkeletonBlock(width = 260.dp, height = 18.dp)
                NovumSkeletonBlock(width = 96.dp, height = 36.dp, shape = NovumShapes.Pill)
            }
        }
    }
}

/**
 * 网格骨架：用于媒体库下钻、收藏与搜索结果。
 *
 * @param modifier 外部修饰符。
 * @param rows 行数。
 * @param columns 每行卡片数。
 */
@Composable
fun NovumSkeletonPosterGrid(
    modifier: Modifier = Modifier,
    rows: Int = 3,
    columns: Int = 4,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter * 2),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap)) {
                repeat(columns) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NovumSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(POSTER_ASPECT),
                            shape = NovumShapes.Card,
                        )
                        NovumSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(SKELETON_TITLE_WIDTH)
                                .height(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 详情页骨架：左侧标题/简介/按钮，右侧海报。
 *
 * @param modifier 外部修饰符。
 */
@Composable
fun NovumSkeletonDetail(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = NovumDimens.SafeHorizontal,
                vertical = NovumDimens.SafeVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NovumSkeletonBlock(width = 420.dp, height = 44.dp)
            NovumSkeletonBlock(width = 260.dp, height = 16.dp)
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.9f).height(18.dp))
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.8f).height(18.dp))
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f).height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NovumSkeletonBlock(width = 160.dp, height = 52.dp, shape = NovumShapes.Pill)
                NovumSkeletonBlock(width = 160.dp, height = 52.dp, shape = NovumShapes.Pill)
            }
        }
        Column(
            modifier = Modifier.width(280.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) {
                NovumSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(POSTER_ASPECT),
                    shape = NovumShapes.Card,
                )
            }
        }
    }
}

/** 骨架透明度下限。 */
private const val SKELETON_ALPHA_MIN = 0.22f

/** 骨架透明度上限。 */
private const val SKELETON_ALPHA_MAX = 0.42f

/** 一次呼吸耗时。 */
private const val SKELETON_PULSE_MS = 900

/** 通用列表骨架默认行数。 */
private const val DEFAULT_SKELETON_ROWS = 6

/** 海报标题占位宽度比例。 */
private const val SKELETON_TITLE_WIDTH = 0.7f
