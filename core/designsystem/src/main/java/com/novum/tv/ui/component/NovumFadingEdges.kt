package com.novum.tv.ui.component

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Apple TV+ 式边缘渐隐通道：卡片滚到行两端时按渐变衰减不透明度。
 *
 * overlayColor 为 null 时使用离屏缓冲与 DstIn，适用于背景图上的行；传入页面底色时绘制渐变盖片，适用于纯色页面。
 * 渐变画笔由 drawWithCache 构建，绘制阶段不分配对象，也不触发重组或重测量。
 * 渐变宽度应与行的水平 contentPadding 一致，宿主需横向铺满屏幕。
 *
 * @param edgeWidth 渐变通道宽度。
 * @param overlayColor 纯色页面底色；背景图行传 null。
 */
@Stable
fun Modifier.appleTvFadingEdges(edgeWidth: Dp, overlayColor: Color? = null): Modifier {
    val base = if (overlayColor == null) {
        this.graphicsLayer {
            // 离屏缓冲：DstIn 需要独立图层，否则渐变会直接抠穿到窗口背景
            compositingStrategy = CompositingStrategy.Offscreen
        }
    } else {
        this
    }
    return base.drawWithCache {
        val edgePx = edgeWidth.toPx()
        val width = size.width
        val valid = edgePx > 0f && width > edgePx * 2f
        // 左缘：x=0 全隐 → x=edgePx 全显；右缘对称
        val leftBrush = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.Black,
            startX = 0f,
            endX = edgePx,
        )
        val rightBrush = Brush.horizontalGradient(
            0f to Color.Black,
            1f to Color.Transparent,
            startX = width - edgePx,
            endX = width,
        )
        // 盖片渐变（overlayColor 路径）：同样在缓存区构建，绘制期零分配
        val overlayLeft = overlayColor?.let {
            Brush.horizontalGradient(
                0f to it,
                1f to Color.Transparent,
                startX = 0f,
                endX = edgePx,
            )
        }
        val overlayRight = overlayColor?.let {
            Brush.horizontalGradient(
                0f to Color.Transparent,
                1f to it,
                startX = width - edgePx,
                endX = width,
            )
        }
        onDrawWithContent {
            drawContent()
            if (!valid) {
                return@onDrawWithContent
            }
            if (overlayLeft == null || overlayRight == null) {
                // DstIn 乘到内容 alpha 上：渐变端头 alpha=0 完全隐去
                drawRect(brush = leftBrush, blendMode = BlendMode.DstIn)
                drawRect(brush = rightBrush, blendMode = BlendMode.DstIn)
            } else {
                // 底色盖片：实底背景上与 alpha 衰减视觉等价，免离屏层
                drawRect(brush = overlayLeft)
                drawRect(brush = overlayRight)
            }
        }
    }
}
