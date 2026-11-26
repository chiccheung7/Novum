package com.novum.tv.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterGridTest {
    @Test
    fun `poster columns scale with available width`() {
        assertEquals(2, posterColumnCount(320f))
        assertEquals(4, posterColumnCount(700f))
        assertEquals(6, posterColumnCount(1144f))
    }

    @Test
    fun `wide screens add columns instead of enlarging posters`() {
        assertEquals(1, posterColumnCount(0f))
        assertEquals(10, posterColumnCount(2000f))
        assertEquals(3, posterColumnCount(510f, minCardWidth = 150f, gap = 16f))
    }
}
