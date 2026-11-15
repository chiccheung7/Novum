package com.novum.tv.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens

/** 焦点滚动死区：小于此距离的滚动请求归零。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovumFocusScrollTuning(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val deadZonePx = with(density) { NOVUM_SCROLL_DEAD_ZONE.toPx() }
    val spec = remember(deadZonePx) {
        object : BringIntoViewSpec {
            /**
             * @param offset 焦点矩形相对容器起点的偏移。
             * @param size 焦点矩形尺寸。
             * @param containerSize 容器尺寸。
             * @return 需要滚动的距离；0 表示不动。
             */
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                val maxOffset = containerSize - size
                val distance = when {
                    offset < 0f -> offset
                    offset > maxOffset -> offset - maxOffset
                    else -> 0f
                }
                return if (kotlin.math.abs(distance) < deadZonePx) 0f else distance
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * 横向行/列焦点滚动的落地余摆死区：忽略弹簧动画落定时的微小回摆。
 * 回摆位于焦点边距预算内；追逐式补滚会造成重复位移。
 *
 * 取值需大于横向余摆，同时小于静止边距。
 */
private val NOVUM_SCROLL_DEAD_ZONE = 16.dp

/**
 * 横向行/列焦点滚动的**落地余摆死区**，用于忽略弹簧动画落定时的微小回摆。
 *
 * bring-into-view 的落地滚动使用弹簧动画；回摆期间的追逐式小修正会造成重复位移。
 * 落地余摆在焦点边距预算内不可见，因此不追踪：
 * 余摆在边距预算内不可见（首/尾静止线 68dp 预算、行间出血 26dp），
 * 而追逐修正本身才是可见缺陷。
 *
 * 16dp 大于常见微小回摆，同时小于静止对齐线，较大的对齐位移不受影响。
 */
private val FOCUS_SCROLL_SETTLE_DEAD_ZONE = 16.dp

/**
 * 纵向列表/网格的底边落地余摆死区，按横移期间测得的底边余摆范围设置。
 * 边界包含在死区内；顶边不使用死区，以便修正顶部行位置。
 * 布局预算应计入该死区，避免卡片文字被裁切。
 */
val COLUMN_BOTTOM_SETTLE_DEAD_ZONE = 24.dp

/**
 * 落地余摆死区计算（纯函数）。小于死区的补滚请求归零；边界是否包含由 inclusiveBoundary 指定。
 *
 * @param distance 滚动请求距离。
 * @param deadZonePx 死区大小。
 * @param inclusiveBoundary 等于死区时是否归零。
 */
fun settleDeadZone(
    distance: Float,
    deadZonePx: Float,
    inclusiveBoundary: Boolean = false,
): Float = when {
    kotlin.math.abs(distance) < deadZonePx -> 0f
    inclusiveBoundary && kotlin.math.abs(distance) == deadZonePx -> 0f
    else -> distance
}

/**
 * 计算「带焦点边距」的滚动距离（纯函数，便于单测穷举）。
 *
 * 内建 bring-into-view 只把焦点项的**布局边界**塞进视口，聚焦放大后的溢出正好压在
 * 视口裁剪线上——卡片右缘/下缘被切掉 1–4dp，卡片越大越明显。
 * 这里额外要求焦点项与视口边缘至少保持 [margin]，把缩放溢出留在视口内。
 *
 * 尺寸超过容器的项（全宽 Hero 等）退化为标准「对齐最近边」：否则 margin 会把
 * 放不下的项反复向两侧推，产生来回滚动。
 *
 * @param offset 焦点项相对容器起点的偏移。
 * @param size 焦点项尺寸。
 * @param containerSize 容器尺寸。
 * @param margin 焦点项与容器边缘的最小距离（像素）。
 * @param overflowRatio 缩放单侧溢出比例 = (scale − 1) / 2。
 * @param contentPaddingStart 行首静止对齐线（contentPadding.start，px）。
 * @param contentPaddingEnd 行尾静止对齐线（contentPadding.end，px）。
 * @return 需要滚动的距离；0 表示不动。
 */
fun focusScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    margin: Float,
    overflowRatio: Float = 0f,
    contentPaddingStart: Float = 0f,
    contentPaddingEnd: Float = 0f,
): Float {
    if (containerSize <= 0f || size <= 0f) return 0f
    val maxOffset = containerSize - size
    // 布局尺寸不包含 graphicsLayer 缩放溢出，按 overflowRatio 扩展边界。
    val overflow = size * overflowRatio
    if (maxOffset <= 0f) {
        return when {
            offset < 0f -> offset
            offset > maxOffset -> offset - maxOffset
            else -> 0f
        }
    }
    // 首尾卡以 contentPadding 作为静止对齐线；两侧边距钳制在可用空间一半。
    val bleed = margin + overflow
    val effectiveStart = minOf(maxOf(contentPaddingStart, bleed), maxOffset / 2f)
    val effectiveEnd = minOf(maxOf(contentPaddingEnd, bleed), maxOffset / 2f)
    return when {
        offset < effectiveStart -> offset - effectiveStart
        offset > maxOffset - effectiveEnd -> offset - (maxOffset - effectiveEnd)
        else -> 0f
    }
}

/**
 * 带焦点边距的滚动作用域，适用于横向卡片行和网格。
 * 焦点项进入视口时保留 [focusMargin]，缩放溢出不会被裁切；已满足边距时不触发滚动。
 * 设置页等无缩放内容可使用 [NovumNoScrollDeadZone]。
 *
 * @param focusMargin 焦点项与容器边缘的最小距离。
 * @param contentPadding 行静止对齐线。
 * @param deadZone 落地余摆死区；无缩放元素传 0。
 * @param content 作用域内容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovumFocusScrollScope(
    focusMargin: Dp = NovumDimens.FocusBleed,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    deadZone: Dp = FOCUS_SCROLL_SETTLE_DEAD_ZONE,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val marginPx = with(density) { focusMargin.toPx() }
    val paddingStartPx = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() }
    val paddingEndPx = with(density) { contentPadding.calculateEndPadding(layoutDirection).toPx() }
    val deadZonePx = with(density) { deadZone.toPx() }
    // 布局尺寸不包含 graphicsLayer 缩放溢出，按 overflowRatio 扩展边界。
    val overflowRatio = (NovumFocusTokens.CardScale - 1f) / 2f
    val spec = remember(marginPx, paddingStartPx, paddingEndPx, overflowRatio, deadZonePx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                val raw = focusScrollDistance(
                    offset,
                    size,
                    containerSize,
                    marginPx,
                    overflowRatio,
                    contentPaddingStart = paddingStartPx,
                    contentPaddingEnd = paddingEndPx,
                )
                return settleDeadZone(raw, deadZonePx)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * 关闭死区的作用域，适用于不需要缩放余量的内容。
 *
 * 死区只该压"卡片缩放溢出"造成的 5–7dp 微滚动。以下两类容器不含这个误差来源，
 * 死区却会把**正常的补齐滚动**（把只差几 dp 的内容滚完整）一起吞掉，表现为顶/底/边缘
 * 内容停在半截：
 * - 设置页这类纵向滚动内容：焦点目标是按钮，[NovumButton] 只变色不缩放；
 * - 横向卡行：现在由 [NovumFocusScrollScope] 的焦点边距负责，卡片不应再用这个零边距版本。
 *
 * @param content 作用域内容。
 */
@Composable
fun NovumNoScrollDeadZone(content: @Composable () -> Unit) {
    NovumFocusScrollScope(focusMargin = 0.dp, deadZone = 0.dp, content = content)
}

/**
 * 网格专用首尾不对称焦点滚动规格。
 *
 * bring-into-view 的目标矩形是可聚焦节点的图区，文字块位于图区之外，因此顶底边距不能对称。
 *
 * 两边各给各的：**顶边 = [topMargin]**（图区小出血即可）；
 * **底边 = [bottomMargin]**（必须 ≥ 图下文字块 + 图文间距 + 出血，图区对进来时
 * 文字块必然还在视口内）。
 *
 * 与 [NovumColumnFocusScrollScope] 共享同一 spec 工厂（BringIntoViewSpec 轴无关，
 * 数学见 [asymmetricBringIntoViewSpec]）。
 *
 * @param topMargin 焦点图区与滚动视口顶边的最小距离。
 * @param bottomMargin 焦点图区与滚动视口底边的最小距离。
 * @param content 作用域内容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovumGridFocusScrollScope(
    topMargin: Dp,
    bottomMargin: Dp,
    content: @Composable () -> Unit,
) {
    val topPx = with(LocalDensity.current) { topMargin.toPx() }
    val bottomPx = with(LocalDensity.current) { bottomMargin.toPx() }
    val bottomDeadZonePx = with(LocalDensity.current) { COLUMN_BOTTOM_SETTLE_DEAD_ZONE.toPx() }
    // 缩放单侧溢出比例：整卡浮起 1.10 → 每侧多探出 5% 边长
    val overflowRatio = (NovumFocusTokens.CardScale - 1f) / 2f
    val spec = remember(topPx, bottomPx, bottomDeadZonePx, overflowRatio) {
        asymmetricBringIntoViewSpec(topPx, bottomPx, overflowRatio, bottomDeadZonePx)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * 列表和网格共用的首尾不对称焦点滚动作用域。
 * 边距需包含 graphicsLayer 缩放产生的视觉溢出；底边优先保留文字块空间。
 *
 * @param topMargin 焦点图区与滚动视口顶边的最小距离。
 * @param bottomMargin 焦点图区与滚动视口底边的最小距离。
 * @param content 作用域内容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovumColumnFocusScrollScope(
    topMargin: Dp,
    bottomMargin: Dp,
    content: @Composable () -> Unit,
) = NovumGridFocusScrollScope(topMargin = topMargin, bottomMargin = bottomMargin, content = content)

/**
 * 首尾不对称滚动距离计算（列表/网格共享）。
 *
 * bring-into-view 的 `size` 是布局尺寸；graphicsLayer 缩放不改变布局尺寸，
 * 因此边距要加上 `size × overflowRatio`，使缩放后的视觉边界留在视口内。
 *
 * 边距钳制时优先保证底边空间，顶边使用剩余可用空间。
 *
 * @param topPx 顶边距（px）。
 * @param bottomPx 底边距（px）。
 * @param overflowRatio 缩放单侧溢出比例 = (scale − 1) / 2。
 * @param bottomDeadZonePx 底边落地余摆死区（px）；顶边不使用死区，以便修正顶部行位置。
 */
private fun asymmetricBringIntoViewSpec(
    topPx: Float,
    bottomPx: Float,
    overflowRatio: Float = 0f,
    bottomDeadZonePx: Float = 0f,
): BringIntoViewSpec =
    object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float {
            val maxOffset = containerSize - size
            if (maxOffset <= 0f) return 0f
            // 缩放溢出补偿：聚焦后视觉边界比布局边界多探出这么多
            val overflow = size * overflowRatio
            // 底边优先：文字块必须完整；顶边吃剩余空间
            val effectiveBottom = minOf(bottomPx + overflow, maxOffset)
            val effectiveTop = minOf(topPx + overflow, maxOffset - effectiveBottom)
            val distance = when {
                offset < effectiveTop -> offset - effectiveTop
                offset > maxOffset - effectiveBottom ->
                    settleDeadZone(
                        offset - (maxOffset - effectiveBottom),
                        bottomDeadZonePx,
                        inclusiveBoundary = true,
                    )
                else -> 0f
            }
            return distance
        }
    }
