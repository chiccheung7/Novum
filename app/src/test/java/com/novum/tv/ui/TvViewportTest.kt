package com.novum.tv.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视口归一化：不同 dpi 报告的电视必须落到同一个 960×540dp 设计基准。
 *
 * 断言口径：归一化后画布 dp 尺寸 = 物理像素 / 新 density，应不窄于 960、不矮于 540；
 * 16:9 机型恰好 960×540。
 */
class TvViewportTest {

    /** 1080p @ 320dpi：系统画布本来就是 960×540，归一化后保持 1:1。 */
    @Test
    fun standardTvKeepsOneToOneBaseline() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 960f,
            windowHeightDp = 540f,
            systemDensity = Density(2f),
        )

        assertEquals(2f, normalized.density, 0.001f)
        assertEquals(960f, 1920f / normalized.density, 0.001f)
        assertEquals(540f, 1080f / normalized.density, 0.001f)
    }

    /** 1080p @ 480dpi：系统画布只有 640×360，必须缩到基准，否则卡片占半屏。 */
    @Test
    fun highDensityTvIsScaledDownToBaseline() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 640f,
            windowHeightDp = 360f,
            systemDensity = Density(3f),
        )

        assertEquals(960f, 1920f / normalized.density, 0.001f)
        assertEquals(540f, 1080f / normalized.density, 0.001f)
    }

    /** 4K @ 320dpi：系统画布 1920×1080，归一化后仍是 960×540 基准。 */
    @Test
    fun uhdTvIsScaledUpToBaseline() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1920f,
            windowHeightDp = 1080f,
            systemDensity = Density(2f),
        )

        assertEquals(960f, 3840f / normalized.density, 0.001f)
        assertEquals(540f, 2160f / normalized.density, 0.001f)
    }

    /** 21:9 超宽屏：高度先到 540，不再压缩纵向布局（宽度多出来的部分只多显示内容）。 */
    @Test
    fun ultrawideKeepsDesignHeight() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1280f,
            windowHeightDp = 540f,
            systemDensity = Density(2f),
        )

        assertEquals(540f, 1080f / normalized.density, 0.001f)
        assertEquals(1280f, 2560f / normalized.density, 0.001f)
    }

    /** 4:3 窄屏：宽度先到 960，高度多出来的部分留给内容，不挤压横向行卡。 */
    @Test
    fun narrowScreenKeepsDesignWidth() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1024f,
            windowHeightDp = 768f,
            systemDensity = Density(1f),
        )

        assertEquals(960f, 1024f / normalized.density, 0.001f)
    }

    /** 系统字体放大不参与归一化：TV 固定画布，字体会撑破行高（字号由应用内档位提供）。 */
    @Test
    fun fontScaleIsPinnedToOne() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 960f,
            windowHeightDp = 540f,
            systemDensity = Density(2f, fontScale = 1.35f),
        )

        assertEquals(1f, normalized.fontScale, 0.001f)
    }

    /** 窗口尺寸尚未测量（0）时原样返回，避免除零算出荒唐密度。 */
    @Test
    fun unmeasuredWindowFallsBackToSystemDensity() {
        val system = Density(3f, fontScale = 1.2f)
        assertEquals(system, normalizeTvViewportDensity(0f, 0f, system))
    }
}
