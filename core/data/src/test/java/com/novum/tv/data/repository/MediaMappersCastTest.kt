package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.testing.FakeMediaStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演职员映射去重：Emby 的 `People` 会把同一个人按多个职位重复下发，
 * 详情页 LazyRow 用人物 id 作 key，重复即抛
 * `IllegalArgumentException: Key "..." was already used`（实机闪退）。
 */
class MediaMappersCastTest {

    @Test
    fun duplicatePersonIdsCollapseToOneEntry() {
        val people = listOf(
            PersonDto(Name = "陈晓卿", Role = "导演", Type = "Director", Id = "p01K"),
            PersonDto(Name = "陈晓卿", Role = "旁白", Type = "Actor", Id = "p01K"),
            PersonDto(Name = "李立宏", Role = "解说", Type = "Actor", Id = "p02L"),
        )

        val cast = people.toCastMembers("http://server", FakeMediaStrings)

        assertEquals(listOf("陈晓卿", "李立宏"), cast.map { it.name })
        assertEquals("同人优先展示演员角色", listOf("旁白", "解说"), cast.map { it.role })
        assertEquals(2, cast.mapNotNull { it.id }.distinct().size)
    }

    @Test
    fun actorRecordKeepsItsOwnAvatarAndRoleTogether() {
        val cast = listOf(
            PersonDto(Name = "成龙", Type = "Director", Id = "jackie", PrimaryImageTag = "director-tag"),
            PersonDto(Name = "成龙", Role = "陈家驹", Type = "Actor", Id = "jackie", PrimaryImageTag = "actor-tag"),
        ).toCastMembers("https://server", FakeMediaStrings)

        assertEquals(1, cast.size)
        assertEquals("陈家驹", cast.single().role)
        assertTrue(cast.single().imageUrl!!.contains("/Items/jackie/Images/Primary"))
        assertTrue(cast.single().imageUrl!!.endsWith("tag=actor-tag"))
    }

    @Test
    fun noAvatarTagUsesNamePlaceholderInsteadOfAnUnverifiedImage() {
        val cast = listOf(PersonDto(Name = "成龙", Type = "Actor", Id = "jackie"))
            .toCastMembers("https://server", FakeMediaStrings)
        assertNull(cast.single().imageUrl)
        assertEquals("jackie", cast.single().id)
    }

    @Test
    fun seasonFrequencyCountsEpisodesInsteadOfRepeatedPositions() {
        val main = PersonDto(Name = "主演", Type = "Actor", Id = "main")
        val guest = PersonDto(Name = "客串", Type = "Actor", Id = "guest")
        val cast = listOf(
            ItemDto(People = listOf(guest.copy(Type = "Director"), guest, guest, main)),
            ItemDto(People = listOf(main)),
        ).toSeasonCast("https://server", FakeMediaStrings)

        assertEquals(listOf("main", "guest"), cast.map { it.id })
        assertEquals("演员", cast.last().role)
    }

    @Test
    fun missingIdFallsBackToNameDedupe() {
        val people = listOf(
            PersonDto(Name = "某群演", Role = "村民", Type = "Actor"),
            PersonDto(Name = "某群演", Role = "路人", Type = "Actor"),
        )

        assertEquals(1, people.toCastMembers("http://server", FakeMediaStrings).size)
    }
}
