package com.novum.tv.data.repository

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleDeliveryTest {
    @Test fun pgsIsNeverPassedToTheTextSrtParser() {
        assertEquals(MimeTypes.APPLICATION_PGS, "pgssub".toSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_PGS, "sup".toSubtitleMime())
        assertEquals(MimeTypes.TEXT_SSA, "ass".toSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_SUBRIP, "subrip".toSubtitleMime())
        assertNull("vobsub".toSubtitleMime())
        assertNull("dvbsub".toSubtitleMime())
        assertNull("unknown".toSubtitleMime())
    }

    @Test fun burnedInStatusRequiresTheActualEncodeMethodAndAValidSelectedIndex() {
        assertEquals(7, burnedInSubtitleIndex("https://example.test/play?SubtitleMethod=Encode&SubtitleStreamIndex=7"))
        assertEquals(3, burnedInSubtitleIndex("https://example.test/play?subtitlestreamindex=3&subtitlemethod=%45ncode"))
        assertNull(burnedInSubtitleIndex("https://example.test/play?SubtitleMethod=External&SubtitleStreamIndex=7"))
        assertNull(burnedInSubtitleIndex("https://example.test/play?SubtitleMethod=Encode&SubtitleStreamIndex=-1"))
        assertNull(burnedInSubtitleIndex("https://example.test/play?SubtitleMethod=Encode"))
    }
}
