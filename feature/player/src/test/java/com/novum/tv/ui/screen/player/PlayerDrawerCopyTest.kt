package com.novum.tv.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerDrawerCopyTest {
    @Test
    fun missingAndBlankOverviewReturnTheEmptyState() {
        assertNull(drawerOverviewBody("片名", null))
        assertNull(drawerOverviewBody("片名", " \n\t "))
    }

    @Test
    fun overviewContainingOnlyTheTitleReturnsTheEmptyState() {
        assertNull(drawerOverviewBody("片名", " 片名 \n"))
        assertNull(drawerOverviewBody("片名", "片名： \n"))
        assertNull(drawerOverviewBody("片名", "片名:\n"))
    }

    @Test
    fun blankHeadingKeepsTheTrimmedOverview() {
        assertEquals("正文", drawerOverviewBody(" \t", " \n正文 \n"))
    }

    @Test
    fun normalFirstSentenceWithTheSameWordsIsNotTruncated() {
        assertEquals("人生一串讲述各地的烟火故事。", drawerOverviewBody("人生一串", "人生一串讲述各地的烟火故事。"))
        assertEquals("Home alone at last.", drawerOverviewBody("Home", "Home alone at last."))
        assertEquals("Dune-Part Two explores another chapter.", drawerOverviewBody("Dune", "Dune-Part Two explores another chapter."))
    }

    @Test
    fun standaloneTitleLineIsRemovedAcrossLineEndings() {
        assertEquals("正文", drawerOverviewBody("片名", "片名\n\n正文"))
        assertEquals("正文", drawerOverviewBody("片名", "片名 \t\r\n正文"))
        assertEquals("正文", drawerOverviewBody("片名", "片名\u2028正文"))
    }

    @Test
    fun explicitTitleColonIsRemoved() {
        assertEquals("正文", drawerOverviewBody("片名", "片名：正文"))
        assertEquals("正文", drawerOverviewBody(" 片名 ", "片名 : \n正文"))
    }

    @Test
    fun unrelatedOverviewIsOnlyTrimmedAndTheBodyIsNotRepeatedlyStripped() {
        assertEquals("另一段正文", drawerOverviewBody("片名", " 另一段正文 \n"))
        assertEquals("片名\n正文", drawerOverviewBody("片名", "片名\n片名\n正文"))
    }

    @Test
    fun audioLabelAlreadyContainingChannelsIsNotRepeated() {
        assertEquals("英语 · 6 声道", drawerAudioSummary(" 英语 · 6 声道 ", 6, "%1\$d 声道"))
        assertEquals("英语", drawerAudioSummary("英语", 6, "%1\$d 声道"))
    }

    @Test
    fun channelsAreOnlyUsedWhenTheAudioLabelIsMissing() {
        assertEquals("6 声道", drawerAudioSummary(" \t", 6, "%1\$d 声道"))
        assertNull(drawerAudioSummary("", null, "%1\$d 声道"))
        assertNull(drawerAudioSummary("", 0, "%1\$d 声道"))
        assertNull(drawerAudioSummary("", -1, "%1\$d 声道"))
    }
}
