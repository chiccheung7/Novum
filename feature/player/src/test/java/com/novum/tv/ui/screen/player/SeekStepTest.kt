package com.novum.tv.ui.screen.player

import com.novum.tv.core.player.seekStepMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长按 seek 步进测试：单击 10 秒，按住超过阈值后加速到 30 秒。
 */
class SeekStepTest {

    @Test
    fun singlePressUsesShortStep() {
        assertEquals(10_000L, seekStepMs(repeatCount = 0))
        assertEquals(10_000L, seekStepMs(repeatCount = 3))
    }

    @Test
    fun longPressAccelerates() {
        assertEquals(30_000L, seekStepMs(repeatCount = 4))
        assertEquals(30_000L, seekStepMs(repeatCount = 20))
    }
}
