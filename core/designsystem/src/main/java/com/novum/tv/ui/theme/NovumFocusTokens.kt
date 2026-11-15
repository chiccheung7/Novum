package com.novum.tv.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 焦点令牌：缩放与出血的唯一来源，禁止调用点手写。
 *
 * 普通卡与按钮统一使用 1.10 缩放；出血公式为
 * `bleed = 卡最大边 × (scale − 1) / 2 + 2dp`，
 * 保证聚焦放大后向四周的溢出永远落在容器内，不被 Lazy 容器裁剪。
 */
object NovumFocusTokens {

    /** 内容卡片聚焦缩放。 */
    const val CardScale = 1.10f

    /** 按钮聚焦缩放。 */
    const val ButtonScale = 1.10f

    /**
     * 聚焦项的绘制层级。
     *
     * Lazy 行/网格按索引顺序绘制子项，不抬层时聚焦卡放大的边缘会被后一张卡盖住
     * 。zIndex 只在绘制期排序，不参与测量，不会引发抖动。
     */
    const val FocusZIndex = 1f

    /** 聚焦动画时长。 */
    const val AnimationMs = 150

    /**
     * 按卡片最大边自动计算出血。
     *
     * @param cardMaxDimension 卡片的最大边（横卡取宽，竖卡取高）。
     * @param scale 聚焦缩放系数。
     */
    fun bleed(cardMaxDimension: Dp, scale: Float = CardScale): Dp =
        cardMaxDimension * ((scale - 1f) / 2f) + 2.dp
}
