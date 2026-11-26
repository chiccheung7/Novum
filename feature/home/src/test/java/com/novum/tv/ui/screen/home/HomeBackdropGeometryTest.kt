package com.novum.tv.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBackdropGeometryTest {
    @Test
    fun returningToAScrolledHeroRecoversGeometryWithoutReachingTheTop() {
        val bounds = recoverHeroRestBounds(
            heroOffset = -40,
            heroHeight = 444,
            firstVisibleIndex = 1,
            firstVisibleScrollOffset = 40,
            savedRestTop = Float.NaN,
            defaultRestTop = 139f,
        )

        val progress = visibleHeroBackdropProgress(-40f, bounds.first, bounds.second, 16f)
        assertEquals(139f, bounds.first, 0.001f)
        assertEquals(583f, bounds.second, 0.001f)
        assertTrue("可见 Hero 的背景不能因尚未回到绝对顶部而全黑", progress.second < 1f)
    }

    @Test
    fun partlyScrolledTopRowStillReconstructsTheExactRestPosition() {
        val bounds = recoverHeroRestBounds(100, 444, 0, 39, Float.NaN, 0f)

        assertEquals(139f, bounds.first, 0.001f)
        assertEquals(583f, bounds.second, 0.001f)
    }

    @Test
    fun savedGeometryRemainsStableWhileTheHeroIsAtTheViewportStart() {
        val bounds = recoverHeroRestBounds(-60, 444, 1, 60, 139f, 120f)
        val topProgress = visibleHeroBackdropProgress(139f, bounds.first, bounds.second, 16f)

        assertEquals(139f, bounds.first, 0.001f)
        assertEquals(0f to 0f, topProgress)
    }
}
