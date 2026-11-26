package com.novum.tv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedLineAccountTest {
    private val settings = AppSettings(
        pinnedLineUrl = "https://line-a.example",
        pinnedLineName = "线路 A",
        pinnedLineAccountKey = "account-a-hash",
    )

    @Test
    fun onlyTheMatchingAccountCanUseThePinnedLine() {
        assertEquals("https://line-a.example", settings.pinnedLineUrlFor("account-a-hash"))
        assertNull(settings.pinnedLineUrlFor("account-b-hash"))
    }

    @Test
    fun missingCurrentAccountOrLegacyOwnerNeverUsesThePinnedLine() {
        assertNull(settings.pinnedLineUrlFor(null))
        assertNull(settings.pinnedLineUrlFor(""))
        assertNull(settings.pinnedLineUrlFor(" "))
        assertNull(settings.copy(pinnedLineAccountKey = "").pinnedLineUrlFor("account-a-hash"))
        assertNull(settings.copy(pinnedLineAccountKey = "").pinnedLineUrlFor(""))
    }

    @Test
    fun emptyLineRemainsAutomaticEvenForTheMatchingAccount() {
        assertNull(settings.copy(pinnedLineUrl = "").pinnedLineUrlFor("account-a-hash"))
        assertNull(settings.copy(pinnedLineUrl = " ").pinnedLineUrlFor("account-a-hash"))
    }
}
