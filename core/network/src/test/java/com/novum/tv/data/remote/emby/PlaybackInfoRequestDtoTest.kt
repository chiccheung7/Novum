package com.novum.tv.data.remote.emby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInfoRequestDtoTest {
    @Test
    fun explicitSubtitleZeroOffZeroPositionAndFalseDirectFlagsAreNotOmitted() {
        val json = Json { encodeDefaults = false }
        for (index in listOf(0, -1)) {
            val request = PlaybackInfoRequestDto(
                MediaSourceId = "same-version",
                SubtitleStreamIndex = index,
                StartTimeTicks = 0L,
                EnableDirectPlay = false,
                EnableDirectStream = false,
                EnableTranscoding = true,
                AllowVideoStreamCopy = false,
                IsPlayback = true,
            )
            val body = json.parseToJsonElement(json.encodeToString(PlaybackInfoRequestDto.serializer(), request)).jsonObject
            assertEquals("same-version", body.getValue("MediaSourceId").jsonPrimitive.content)
            assertEquals(index, body.getValue("SubtitleStreamIndex").jsonPrimitive.int)
            assertEquals(0L, body.getValue("StartTimeTicks").jsonPrimitive.long)
            assertEquals(false, body.getValue("EnableDirectPlay").jsonPrimitive.boolean)
            assertEquals(false, body.getValue("EnableDirectStream").jsonPrimitive.boolean)
            assertEquals(false, body.getValue("AllowVideoStreamCopy").jsonPrimitive.boolean)
            assertEquals(true, body.getValue("EnableTranscoding").jsonPrimitive.boolean)
            assertEquals(true, body.getValue("IsPlayback").jsonPrimitive.boolean)
        }
    }

    @Test
    fun explicitBurnInCannotOfferAnExternalAlternativeForTheNewSubtitle() {
        val profile = DeviceProfileDto.forCapabilities(
            audioCodecs = "aac", videoCodecs = "h264", allowDirectPlay = false,
            assBurnIn = false, forceSubtitleBurnIn = true,
        )
        assertTrue(profile.DirectPlayProfiles.isEmpty())
        assertTrue(profile.SubtitleProfiles.all { it.Method == "Encode" })
        assertTrue(profile.SubtitleProfiles.any { it.Format == "srt" })
        assertTrue(profile.SubtitleProfiles.any { it.Format == "ass" })
        assertTrue(profile.SubtitleProfiles.any { it.Format == "pgssub" })
    }
}
