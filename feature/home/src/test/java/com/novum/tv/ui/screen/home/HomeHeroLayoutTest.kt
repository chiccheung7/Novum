package com.novum.tv.ui.screen.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroLayoutTest {

    @Test
    fun heroZoneAdaptsWithinContentSafeBounds() {
        assertEquals(360.dp, adaptiveHeroInfoZoneHeight(500.dp))
        assertEquals(406.dp, adaptiveHeroInfoZoneHeight(700.dp))
        assertEquals(420.dp, adaptiveHeroInfoZoneHeight(900.dp))
    }

    @Test
    fun overviewUsesFewerLinesOnShortScreens() {
        assertEquals(3, adaptiveHeroOverviewLines(360.dp))
        assertEquals(4, adaptiveHeroOverviewLines(400.dp))
        assertEquals(5, adaptiveHeroOverviewLines(420.dp))
    }
}
