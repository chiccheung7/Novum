package com.novum.tv.ui.screen.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerTabOverflowTest {
    @Test
    fun showsOnlyRightHintAtScrollStart() {
        assertFalse(hasMoreTabsBefore(scrollValue = 0, maxValue = 120))
        assertTrue(hasMoreTabsAfter(scrollValue = 0, maxValue = 120))
    }

    @Test
    fun showsBothHintsWhileScrolledInTheMiddle() {
        assertTrue(hasMoreTabsBefore(scrollValue = 60, maxValue = 120))
        assertTrue(hasMoreTabsAfter(scrollValue = 60, maxValue = 120))
    }

    @Test
    fun showsOnlyLeftHintAtScrollEnd() {
        assertTrue(hasMoreTabsBefore(scrollValue = 120, maxValue = 120))
        assertFalse(hasMoreTabsAfter(scrollValue = 120, maxValue = 120))
    }

    @Test
    fun showsNoHintsWhenAllTabsFit() {
        assertFalse(hasMoreTabsBefore(scrollValue = 0, maxValue = 0))
        assertFalse(hasMoreTabsAfter(scrollValue = 0, maxValue = 0))
    }
}
