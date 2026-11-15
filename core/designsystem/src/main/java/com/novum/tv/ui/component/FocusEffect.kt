package com.novum.tv.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens

/**
 * 不带阴影的轻量焦点动效，用于图标按钮等只需缩放反馈的元素。
 *
 * @param scale 聚焦缩放比例。
 */
@Composable
fun Modifier.tvFocusScale(scale: Float = NovumDimens.FocusScale): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scaleAnim = animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = tween(durationMillis = NovumDimens.FocusAnimationMs, easing = FastOutSlowInEasing),
        label = "tvFocusScaleOnly",
    )
    return this
        .onFocusChanged { focused = it.isFocused }
        .zIndex(if (focused) NovumFocusTokens.FocusZIndex else 0f)
        .graphicsLayer {
            scaleX = scaleAnim.value
            scaleY = scaleAnim.value
            clip = false
        }
}
