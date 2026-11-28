package com.novum.tv.data.remote.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDtoClassificationTest {
    @Test
    fun genresAndContentTagsAreDecodedSeparately() {
        val item = Json.decodeFromString(
            ItemDto.serializer(),
            """{"Id":"movie","Genres":["Adventure"],"Tags":["Animation"]}""",
        )

        assertEquals(listOf("Adventure"), item.Genres)
        assertEquals(listOf("Animation"), item.Tags)
    }

    @Test
    fun omittedClassificationFieldsKeepSparseItemsDecodable() {
        val item = Json.decodeFromString(ItemDto.serializer(), """{"Id":"movie"}""")

        assertTrue(item.Genres.isEmpty())
        assertTrue(item.Tags.isEmpty())
    }
}
