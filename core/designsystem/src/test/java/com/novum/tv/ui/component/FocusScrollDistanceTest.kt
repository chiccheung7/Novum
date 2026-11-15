package com.novum.tv.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 焦点滚动边距的纯函数测试。
 *
 * 这段逻辑决定「聚焦卡与视口边缘的距离」。算小的表现是放大后的边缘仍被裁 1–4dp；
 * 算大会让贴边卡频繁多滚一截（观感是每次横移都抽一下）。边界逐条钉住。
 */
class FocusScrollDistanceTest {

    private val eps = 0.001f

    @Test
    fun itemInsideMarginNeedsNoScroll() {
        assertEquals(
            0f,
            focusScrollDistance(offset = 100f, size = 300f, containerSize = 1000f, margin = 16f),
            eps,
        )
    }

    @Test
    fun itemTooCloseToLeadingEdgeScrollsBack() {
        assertEquals(
            -12f,
            focusScrollDistance(offset = 4f, size = 300f, containerSize = 1000f, margin = 16f),
            eps,
        )
    }

    @Test
    fun itemTooCloseToTrailingEdgeScrollsForward() {
        // 右缘只剩 10px 边距：还要再滚 6px 才够 16px（正数 = 内容向前滚）
        assertEquals(
            6f,
            focusScrollDistance(offset = 690f, size = 300f, containerSize = 1000f, margin = 16f),
            eps,
        )
    }

    @Test
    fun exactMarginBoundaryIsStable() {
        assertEquals(
            0f,
            focusScrollDistance(offset = 16f, size = 300f, containerSize = 1000f, margin = 16f),
            eps,
        )
        assertEquals(
            0f,
            focusScrollDistance(offset = 684f, size = 300f, containerSize = 1000f, margin = 16f),
            eps,
        )
    }

    @Test
    fun oversizedItemFallsBackToNearestEdge() {
        // 全宽 Hero：尺寸等于容器，任何偏移都往回收
        assertEquals(
            -50f,
            focusScrollDistance(offset = -50f, size = 1000f, containerSize = 1000f, margin = 16f),
            eps,
        )
        assertEquals(
            0f,
            focusScrollDistance(offset = 0f, size = 1000f, containerSize = 1000f, margin = 16f),
            eps,
        )
    }

    @Test
    fun scaleOverflowIsCompensated() {
        // 聚焦后 1.10 缩放使视觉边界多探出 size × 0.05。
        // 320dp 卡 = 240px@0.75x… 这里直接用 300px 尺寸：溢出 15px。
        // 布局边界刚好留够 16px 边距时，视觉边界只剩 1px —— 必须再滚 15px。
        assertEquals(
            15f,
            focusScrollDistance(
                offset = 684f, size = 300f, containerSize = 1000f,
                margin = 16f, overflowRatio = 0.05f,
            ),
            eps,
        )
        // 无溢出参数时保持基础行为。
        assertEquals(
            0f,
            focusScrollDistance(offset = 684f, size = 300f, containerSize = 1000f, margin = 16f),
            eps,
        )
    }

    @Test
    fun degenerateContainerIsIgnored() {
        assertEquals(
            0f,
            focusScrollDistance(offset = 10f, size = 100f, containerSize = 0f, margin = 16f),
            eps,
        )
    }

    @Test
    fun contentPaddingRaisesMarginToLeadingRestLine() {
        // 首页行 contentPadding 为 68dp，大于出血与缩放溢出之和；首卡聚焦后应停在静止对齐线。
        assertEquals(
            -42f,
            focusScrollDistance(
                offset = 26f, size = 300f, containerSize = 1000f, margin = 16f,
                overflowRatio = 0.05f, contentPaddingStart = 68f, contentPaddingEnd = 68f,
            ),
            eps,
        )
    }

    @Test
    fun contentPaddingAlignsTrailingRestLine() {
        // 尾卡越过右静止线（右侧只剩 26px < 68px）：向前滚回静止线
        assertEquals(
            58f,
            focusScrollDistance(
                offset = 690f, size = 300f, containerSize = 1000f, margin = 16f,
                overflowRatio = 0.05f, contentPaddingStart = 68f, contentPaddingEnd = 68f,
            ),
            eps,
        )
    }

    @Test
    fun contentPaddingClampedToHalfSpan() {
        // 窄行：contentPadding 大于可用空间一半时对半钳制，两端不打架
        assertEquals(
            -130f,
            focusScrollDistance(
                offset = 40f, size = 300f, containerSize = 640f, margin = 4f,
                contentPaddingStart = 200f, contentPaddingEnd = 200f,
            ),
            eps,
        )
    }

    // ---- 落地余摆死区 ----

    @Test
    fun settleDeadZoneSwallowsSpringResidue() {
        // 弹簧回摆期的小补滚一律归零。
        assertEquals(0f, settleDeadZone(24.5f, 30f), eps)
        assertEquals(0f, settleDeadZone(-8f, 24f), eps)
        assertEquals(0f, settleDeadZone(0.5f, 24f), eps)
    }

    @Test
    fun settleDeadZoneKeepsRealScrolls() {
        // 真正的位移（跨行进位、远离静止线）远超死区，原样放行
        assertEquals(206f, settleDeadZone(206f, 30f), eps)
        assertEquals(-96f, settleDeadZone(-96f, 24f), eps)
    }

    @Test
    fun settleDeadZoneBoundaryIsExclusive() {
        // 横向规则对等于死区的距离放行。
        assertEquals(-24f, settleDeadZone(-24f, 24f), eps)
    }

    @Test
    fun settleDeadZoneBoundaryInclusiveForColumnBottom() {
        // 纵向底边：几何全整型 px（视口 1080 − 卡 270 − 边距 162 = 648 整），
        // 纵向底边包含边界，避免在死区边缘反复放行。
        assertEquals(0f, settleDeadZone(36f, 36f, inclusiveBoundary = true), eps)
        assertEquals(0f, settleDeadZone(-36f, 36f, inclusiveBoundary = true), eps)
        // 超出死区仍放行（真实跨行位移不受影响）
        assertEquals(36.5f, settleDeadZone(36.5f, 36f, inclusiveBoundary = true), eps)
        // 横向规则保持排他边界。
        assertEquals(36f, settleDeadZone(36f, 36f), eps)
    }
}
