package com.novum.tv.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/**
 * 图标按钮：单一动作的图标形态，供详情页操作与 Hero 收藏使用。
 * 配色与 [NovumButton] 一致：聚焦白底黑图标、未聚焦浅灰玻璃；选中语义由图标变体承载。
 * 禁用态降低亮度且不进入焦点链。
 *
 * @param icon 图标；选中与未选中使用不同变体。
 * @param contentDescription 无障碍描述。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param onLongClick 长按 OK 回调。
 * @param enabled 是否可用。
 * @param selected 供调用方表达选中态。
 * @param size 正方形按钮尺寸。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovumIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 56.dp,
    /** 图标颜色覆盖（收藏红心等语义色用）；null = 按状态明暗反转。 */
    tintOverride: Color? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // semantics lambda 里 receiver 的 contentDescription 属性会遮蔽同名参数，先取别名
    val description = contentDescription
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            // 图标按钮聚焦缩放比例与全站按钮一致。
            .tvFocusScale(NovumFocusTokens.ButtonScale)
            .onFocusChanged { focused = it.isFocused }
            .clip(NovumShapes.Pill)
            .background(
                when {
                    focused -> NovumPalette.Paper
                    enabled -> NovumPalette.Glass
                    else -> NovumPalette.GlassDisabled
                },
            )
            .combinedClickable(
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            // 无障碍描述挂在可点击容器上（整钮一个语义节点）；Icon 本身 cd 置空，
            // 避免双层播报（tv.material3.Icon 的语义节点在测试里也不可靠）。
            // 用属性键显式赋值：同名参数会遮蔽扩展属性 contentDescription
            .semantics { this[SemanticsProperties.ContentDescription] = listOf(description) },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // tint 负责明暗反转；选中语义由调用方的实心或描边图标变体承载。
            tint = tintOverride ?: when {
                !enabled -> NovumPalette.PaperMuted
                focused -> NovumPalette.Ink
                else -> NovumPalette.Paper
            },
            modifier = Modifier.size(size * 0.4f),
        )
    }
}
