package com.novum.tv.data.remote.emby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataUpdateDtoTest {
    @Test
    fun resetRequestExplicitlySerializesZeroProgressAndBothPlayedStatesWithoutEncodingDefaults() {
        val json = Json { encodeDefaults = false }
        for (played in listOf(true, false)) {
            val request = UserDataUpdateDto(
                PlaybackPositionTicks = 0L,
                Played = played,
                PlayedPercentage = 0.0,
                IsFavorite = false,
            )

            val body = json.parseToJsonElement(json.encodeToString(UserDataUpdateDto.serializer(), request)).jsonObject

            assertTrue(body.keys.containsAll(setOf("PlaybackPositionTicks", "Played", "PlayedPercentage", "IsFavorite")))
            assertEquals(0L, body.getValue("PlaybackPositionTicks").jsonPrimitive.long)
            assertEquals(played, body.getValue("Played").jsonPrimitive.boolean)
            assertEquals(0.0, body.getValue("PlayedPercentage").jsonPrimitive.double, 0.0)
            assertEquals(false, body.getValue("IsFavorite").jsonPrimitive.boolean)
        }
    }
}
