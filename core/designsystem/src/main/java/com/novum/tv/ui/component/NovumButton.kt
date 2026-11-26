package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.LocalContentColor
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/**
 * 品牌按钮：统一 TV 按钮的四态配色。
 *
 * 首页与详情文本按钮使用 [NovumType.ButtonLg]、[NovumDimens.ButtonHeight] 和 [novumActionButtonWidth]，跨页面保持同宽。
 * 按钮常态使用 [NovumPalette.Glass]，聚焦白底黑字，按压态浅灰，禁用态降低亮度。
 *
 * 定宽按钮必须传 [centerContent]=true；不定宽按钮不能启用，否则会撑满整行。
 *
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param onLongClick 长按 OK 回调。
 * @param enabled 是否可用。
 * @param centerContent 是否水平居中。
 * @param scale 聚焦/按压缩放。
 * @param content 按钮内容。
 */
@Composable
fun NovumButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    centerContent: Boolean = false,
    scale: ButtonScale = ButtonDefaults.scale(
        focusedScale = NovumFocusTokens.ButtonScale,
        pressedScale = 1f,
        disabledScale = 1f,
    ),
    content: @Composable RowScope.() -> Unit,
) {
    // 内容色与容器色使用同一焦点状态解析，确保聚焦时保持足够对比。
    var focused by remember { mutableStateOf(false) }
    val resolvedContentColor = when {
        !enabled -> NovumPalette.PaperMuted
        focused -> NovumPalette.Ink
        else -> NovumPalette.Paper
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            // 按钮最小高度。
            .heightIn(min = BUTTON_MIN_HEIGHT),
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        // 首页双 CTA 与详情页播放/选季统一使用胶囊形。
        shape = ButtonDefaults.shape(NovumShapes.Pill),
        // 内容内边距显式收窄：字阶放大到 26sp 后，默认内边距会把 60dp 的按钮撑爆
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        colors = ButtonDefaults.colors(
            // 按钮常态底使用浅灰玻璃；聚焦时反转为白底黑字。
            containerColor = NovumPalette.Glass,
            contentColor = NovumPalette.Paper,
            // 聚焦态使用全站一致的白底黑字语义。
            focusedContainerColor = NovumPalette.Paper,
            focusedContentColor = NovumPalette.Ink,
            pressedContainerColor = NovumPalette.PaperPressed,
            pressedContentColor = NovumPalette.Ink,
            disabledContainerColor = NovumPalette.GlassDisabled,
            disabledContentColor = NovumPalette.PaperMuted,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
            InnerContent(centerContent = centerContent, content = content)
        }
    }
}

/**
 * 按钮内容容器：定宽时水平居中（不定宽时不能 fillMaxWidth，否则按钮会被撑满整行）。
 */
@Composable
private fun RowScope.InnerContent(
    centerContent: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    if (centerContent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    } else {
        content()
    }
}

/** 胶囊形按钮与全站按钮保持一致。 */
private val BUTTON_MIN_HEIGHT = NovumDimens.ButtonHeight

/** 首页、详情及其骨架共用尺寸；大字体时整组按钮按同一比例放宽。 */
@Composable
fun novumActionButtonWidth(): Dp =
    NovumDimens.ActionButtonWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
