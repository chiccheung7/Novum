package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.testing.FakeMediaStrings
import org.junit.Assert.assertEquals
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
        assertEquals("保留首次出现的职位", listOf("导演", "解说"), cast.map { it.role })
        assertEquals(2, cast.mapNotNull { it.id }.distinct().size)
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
