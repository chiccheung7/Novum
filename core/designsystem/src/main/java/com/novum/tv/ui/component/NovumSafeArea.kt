package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette

/**
 * TV 安全区容器：统一处理 overscan 与页面底色。
 *
 * 电视的 overscan 会裁掉屏幕边缘 3%–5%，所有页面必须经由本容器铺内容，
 * 不要各自写 padding，避免不同页面安全边距不一致。
 *
 * @param modifier 外部修饰符。
 * @param horizontalPadding 水平安全边距，默认 [NovumDimens.SafeHorizontal]。
 * @param verticalPadding 垂直安全边距，默认 [NovumDimens.SafeVertical]。
 * @param backgroundColor 页面底色；已有全屏背景时可用透明色。
 * @param content 安全区内的内容。
 */
@Composable
fun NovumSafeArea(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = NovumDimens.SafeHorizontal,
    verticalPadding: Dp = NovumDimens.SafeVertical,
    backgroundColor: Color = NovumPalette.Ink,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        content = content,
    )
}
