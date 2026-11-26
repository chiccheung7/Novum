package com.novum.tv.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleGeometryTest {
    @Test fun widescreenLetterboxUsesContainerWidth() {
        assertEquals(92.2176f, bottomLetterboxHeight(1280f, 720f, 2.39f, VideoScaleMode.Fit), 0.01f)
    }

    @Test fun fullScreenAndSideBarsHaveNoBottomLetterbox() {
        assertEquals(0f, bottomLetterboxHeight(1280f, 720f, 16f / 9f, VideoScaleMode.Fit), 0.001f)
        assertEquals(0f, bottomLetterboxHeight(1280f, 720f, 4f / 3f, VideoScaleMode.Fit), 0.001f)
    }

    @Test fun stretchCropAndUnknownAspectNeverInventBlackBars() {
        listOf(VideoScaleMode.Stretch, VideoScaleMode.Crop).forEach {
            assertEquals(0f, bottomLetterboxHeight(1280f, 720f, 2.39f, it), 0.001f)
        }
        assertEquals(0f, bottomLetterboxHeight(1280f, 720f, null, VideoScaleMode.Fit), 0.001f)
        assertEquals(0f, bottomLetterboxHeight(1280f, 720f, Float.NaN, VideoScaleMode.Fit), 0.001f)
    }

    @Test fun positionedSubtitlesFollowTheActualFitAndCropRectangle() {
        val fit = videoViewport(1280f, 720f, 2.39f, VideoScaleMode.Fit)
        assertEquals(1280f, fit.width, 0.001f)
        assertEquals(1280f / 2.39f, fit.height, 0.001f)
        val crop = videoViewport(1280f, 720f, 2.39f, VideoScaleMode.Crop)
        assertEquals(720f, crop.height, 0.001f)
        assertEquals(720f * 2.39f, crop.width, 0.001f)
    }
}
