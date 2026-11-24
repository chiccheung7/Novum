package com.novum.tv.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视口归一化：过小的画布收敛到 1280×720dp 设计基准，本来就够大的画布保持原样。
 *
 * 断言口径：归一化后画布 dp 尺寸 = 物理像素 / 新 density。
 */
class TvViewportTest {

    /** 1080p @ 320dpi：系统画布 960×540，比设计基准小 → 放大到 1280×720。 */
    @Test
    fun smallCanvasIsEnlargedToDesignBaseline() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 960f,
            windowHeightDp = 540f,
            systemDensity = Density(2f),
        )

        assertEquals(1.5f, normalized.density, 0.001f)
        assertEquals(1280f, 1920f / normalized.density, 0.001f)
        assertEquals(720f, 1080f / normalized.density, 0.001f)
    }

    /** 1080p @ 480dpi：系统画布只有 640×360，卡片会占半屏 → 收敛到基准。 */
    @Test
    fun highDensityTvIsScaledDown() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 640f,
            windowHeightDp = 360f,
            systemDensity = Density(3f),
        )

        assertEquals(1280f, 1920f / normalized.density, 0.001f)
        assertEquals(720f, 1080f / normalized.density, 0.001f)
    }

    /** 画布恰好是设计基准（1080p @ 240dpi）：一像素都不动。 */
    @Test
    fun designCanvasIsUntouched() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1280f,
            windowHeightDp = 720f,
            systemDensity = Density(1.5f),
        )

        assertEquals(1.5f, normalized.density, 0.001f)
    }

    /**
     * 4K @ 320dpi：系统画布 1920×1080，本来就比设计基准宽松。
     *
     * 必须保持原样——把这类设备也压到 1280 会把原本合适的界面整体放大 1.5 倍，
     * 正是"所有东西撑满屏幕"的来源。
     */
    @Test
    fun largeCanvasKeepsSystemDensity() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1920f,
            windowHeightDp = 1080f,
            systemDensity = Density(2f),
        )

        assertEquals(2f, normalized.density, 0.001f)
    }

    /** 画布大得离谱（4K @160dpi = 3840×2160dp）：收敛到 1920×1080 上限，避免字小到看不清。 */
    @Test
    fun absurdlyLargeCanvasIsCapped() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 3840f,
            windowHeightDp = 2160f,
            systemDensity = Density(1f),
        )

        assertEquals(1920f, 3840f / normalized.density, 0.001f)
        assertEquals(1080f, 2160f / normalized.density, 0.001f)
    }

    /** 21:9 超宽屏：高度不足 720 时由高度决定缩放，宽度多出来的部分只多显示内容。 */
    @Test
    fun ultrawideIsDrivenByHeight() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1280f,
            windowHeightDp = 540f,
            systemDensity = Density(2f),
        )

        assertEquals(720f, 1080f / normalized.density, 0.001f)
        assertTrue("超宽屏不应被压窄", 2560f / normalized.density > 1280f)
    }

    /** 4:3 窄屏：宽度不足 1280 时由宽度决定缩放，高度多出来的部分留给内容。 */
    @Test
    fun narrowScreenIsDrivenByWidth() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1024f,
            windowHeightDp = 768f,
            systemDensity = Density(1f),
        )

        assertEquals(1280f, 1024f / normalized.density, 0.001f)
    }

    /** 系统字体放大不参与归一化：TV 固定画布，字体会撑破行高（字号由「界面大小」提供）。 */
    @Test
    fun fontScaleIsPinnedToOne() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1280f,
            windowHeightDp = 720f,
            systemDensity = Density(2f, fontScale = 1.35f),
        )

        assertEquals(1f, normalized.fontScale, 0.001f)
    }

    /** 「界面大小」档位：>1 表示界面更大（密度更大、画布更小）。 */
    @Test
    fun userScaleEnlargesUi() {
        val normalized = normalizeTvViewportDensity(
            windowWidthDp = 1280f,
            windowHeightDp = 720f,
            systemDensity = Density(1f),
            uiScale = 1.15f,
        )

        assertEquals(1.15f, normalized.density, 0.001f)
    }

    /** 「界面大小」越界（手改设置/脏数据）钳到合法区间，不产出荒唐密度。 */
    @Test
    fun userScaleIsClamped() {
        assertEquals(
            MAX_UI_SCALE,
            normalizeTvViewportDensity(1280f, 720f, Density(1f), uiScale = 3f).density,
            0.001f,
        )
        assertEquals(
            MIN_UI_SCALE,
            normalizeTvViewportDensity(1280f, 720f, Density(1f), uiScale = 0.1f).density,
            0.001f,
        )
    }

    /** 窗口尺寸尚未测量（0）时原样返回，避免除零算出荒唐密度。 */
    @Test
    fun unmeasuredWindowFallsBackToSystemDensity() {
        val system = Density(3f, fontScale = 1.2f)
        assertEquals(system, normalizeTvViewportDensity(0f, 0f, system))
    }
}
