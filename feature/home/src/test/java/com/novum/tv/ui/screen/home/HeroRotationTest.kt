package com.novum.tv.ui.screen.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeroRotationTest {

    @Test
    fun intervalStartsOnlyAfterCurrentBackdropSettles() = runTest {
        val settledBackdrop = MutableStateFlow<String?>(null)
        var rotations = 0

        backgroundScope.launch {
            awaitHeroRotationInterval(settledBackdrop, "backdrop-a", intervalMs = 1_000L)
            rotations++
        }
        runCurrent()
        assertEquals("An unfinished image must not consume the display interval", 0, rotations)

        settledBackdrop.value = "backdrop-a"
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals("The full interval starts after the image settles", 0, rotations)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, rotations)
    }

    @Test
    fun missingBackdropStillUsesTheNormalRotationInterval() = runTest {
        var rotations = 0
        backgroundScope.launch {
            awaitHeroRotationInterval(MutableStateFlow(null), null, intervalMs = 1_000L)
            rotations++
        }

        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, rotations)
    }
}
