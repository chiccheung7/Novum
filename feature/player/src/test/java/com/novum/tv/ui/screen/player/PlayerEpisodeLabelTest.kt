package com.novum.tv.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEpisodeLabelTest {
    @Test
    fun keepsTheFullEpisodeNumberPastNinetyNine() {
        assertEquals("S01E99", episodeCode(1, 99))
        assertEquals("S01E100", episodeCode(1, 100))
        assertEquals("S02E125", episodeCode(2, 125))
    }

    @Test
    fun padsShortSeasonAndEpisodeNumbers() {
        assertEquals("S00E01", episodeCode(0, 1))
        assertEquals("S02E03", episodeCode(2, 3))
    }

    @Test
    fun episodeRowDoesNotRepeatSourceNumberingOrLeaveAnEmptySeparator() {
        assertEquals("S01E01 · 开始", drawerEpisodeLabel(1, 1, "第1集 开始"))
        assertEquals("S01E100 · Finale", drawerEpisodeLabel(1, 100, "S01E100 · Finale"))
        assertEquals("S01E01", drawerEpisodeLabel(1, 1, "第1集"))
        assertEquals("S01E01", drawerEpisodeLabel(1, 1, " "))
        assertEquals("", stripEpisodePrefix("第1集"))
    }
}
