package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings
import com.novum.tv.testing.NoopNovumLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人物搜索直接使用服务器 Persons 响应，不派生或读取本地结果。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryPersonTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun mapsServerPersonAndPreservesResponseOrder() = runTest(dispatcher) {
        val api = PersonsApi(
            people = listOf(
                PersonDto(Id = "p2", Name = "李沁儿", Type = "Person"),
                PersonDto(Id = "p1", Name = "李沁", Type = "Person", ImageTags = mapOf("Primary" to "tag-1")),
            ),
        )
        val repository = repository(api)

        val hits = repository.searchPersons("李沁")

        assertEquals(listOf("李沁儿", "李沁"), hits.map { it.name })
        assertTrue(hits.last().imageUrl?.contains("tag-1") == true)
        assertEquals(1, api.personCalls)
        assertEquals(0, api.itemCalls)
    }

    @Test
    fun filtersNonPersonItemsReturnedByServerRoute() = runTest(dispatcher) {
        val api = PersonsApi(
            people = listOf(
                PersonDto(Id = "movie-1", Name = "信号", Type = "Series"),
                PersonDto(Id = "person-1", Name = "演员甲", Type = "Person"),
            ),
        )

        val hits = repository(api).searchPersons("信号")

        assertEquals(listOf("演员甲"), hits.map { it.name })
        assertEquals(0, api.itemCalls)
    }

    @Test
    fun failedPersonEndpointReturnsNoLocalOrDerivedResults() = runTest(dispatcher) {
        val api = PersonsApi(fail = true)

        val hits = repository(api).searchPersons("张若昀")

        assertTrue(hits.isEmpty())
        assertEquals(1, api.personCalls)
        assertEquals(0, api.itemCalls)
    }

    @Test
    fun personWorksLoadsEveryServerPageInResponseOrder() = runTest(dispatcher) {
        val works = List(125) { index -> ItemDto(Id = "work-$index", Name = "Work $index", Type = "Movie") }
        val api = PersonsApi(works = works)

        val result = repository(api).personWorks("person-1") as NovumResult.Success

        assertEquals(works.map { it.Id }, result.value.map { it.id })
        assertEquals(listOf(0, 60, 120), api.workStartIndices)
    }

    @Test
    fun personWorksStopsWhenGatewayRepeatsTheFirstPage() = runTest(dispatcher) {
        val works = List(125) { index -> ItemDto(Id = "work-$index", Name = "Work $index", Type = "Movie") }
        val api = PersonsApi(works = works, ignoreStartIndex = true)

        val result = repository(api).personWorks("person-1") as NovumResult.Success

        assertEquals(works.take(60).map { it.Id }, result.value.map { it.id })
        assertEquals(listOf(0, 60), api.workStartIndices)
    }

    private fun repository(api: EmbyApi): DefaultMediaRepository {
        val holder = SessionHolder().apply {
            update(
                Session(
                    server = "https://smart.uhdnow.com",
                    token = "token",
                    userId = "uid-1",
                    userName = "chic",
                    serverName = "UHD",
                    deviceId = "device-1",
                ),
            )
        }
        return DefaultMediaRepository(
            clients = PersonApiProvider(api),
            sessionHolder = holder,
            dispatchers = dispatchers,
            strings = FakeMediaStrings,
            logger = NoopNovumLogger,
        )
    }
}

private class PersonApiProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

private class PersonsApi(
    private val people: List<PersonDto> = emptyList(),
    private val fail: Boolean = false,
    private val works: List<ItemDto> = emptyList(),
    private val ignoreStartIndex: Boolean = false,
) : EmbyApiStub() {
    val workStartIndices = mutableListOf<Int>()
    var personCalls = 0
        private set
    var itemCalls = 0
        private set

    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> {
        personCalls++
        if (fail) error("Persons endpoint unavailable")
        return QueryResultDto(Items = people, TotalRecordCount = people.size)
    }

    override suspend fun items(
        uid: String,
        parentId: String?,
        ids: String?,
        personIds: String?,
        recursive: Boolean,
        includeItemTypes: String?,
        searchTerm: String?,
        sortBy: String,
        sortOrder: String,
        startIndex: Int,
        limit: Int,
        maxOfficialRating: String?,
        genres: String?,
        filters: String?,
        minPremiereDate: String?,
        minDateCreated: String?,
        fields: String,
    ): QueryResultDto<com.novum.tv.data.remote.emby.ItemDto> {
        itemCalls++
        workStartIndices += startIndex
        return QueryResultDto(
            Items = works.drop(if (ignoreStartIndex) 0 else startIndex).take(limit),
            TotalRecordCount = works.size,
        )
    }
}
