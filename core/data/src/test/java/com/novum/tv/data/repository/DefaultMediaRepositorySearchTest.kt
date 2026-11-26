package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
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

/** 搜索直接使用服务器响应，包括服务端排序、总数和分页。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositorySearchTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun forwardsSearchAndPreservesServerOrderAndTotal() = runTest(dispatcher) {
        val api = SearchApi(
            results = listOf(
                item("first", "海大明", "Movie"),
                item("second", "大明王朝1566", "Series"),
            ),
            totalCount = 17,
        )
        val repository = repository(api)

        val page = success(
            repository.items(
                parentId = null,
                startIndex = 0,
                limit = 60,
                searchTerm = "大明",
                includeItemTypes = "Movie,Series",
            ),
        )

        assertEquals(listOf("海大明", "大明王朝1566"), page.items.map { it.title })
        assertEquals(17, page.total)
        assertEquals(listOf(SearchRequest("大明", "Movie,Series", 0, 60)), api.searchRequests)
        assertEquals(0, api.unfilteredRequests)
    }

    @Test
    fun forwardsServerPaginationWithoutLocalCatalogFallback() = runTest(dispatcher) {
        val api = SearchApi(
            results = listOf(
                item("m-1", "第一部", "Movie"),
                item("m-2", "第二部", "Movie"),
                item("m-3", "第三部", "Movie"),
            ),
        )
        val repository = repository(api)

        val first = success(repository.items(null, 0, 2, searchTerm = "部"))
        val second = success(repository.items(null, 2, 2, searchTerm = "部"))

        assertEquals(listOf("第一部", "第二部"), first.items.map { it.title })
        assertEquals(listOf("第三部"), second.items.map { it.title })
        assertEquals(3, second.total)
        assertEquals(listOf(0, 2), api.searchRequests.map { it.startIndex })
        assertEquals(0, api.unfilteredRequests)
    }

    @Test
    fun serverEmptyResultStaysEmpty() = runTest(dispatcher) {
        val api = SearchApi(results = emptyList())
        val repository = repository(api)

        val page = success(repository.items(null, 0, 60, searchTerm = "不存在"))

        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
        assertEquals(1, api.searchRequests.size)
        assertEquals(0, api.unfilteredRequests)
    }

    private fun success(result: NovumResult<com.novum.tv.model.MediaPage>) =
        (result as NovumResult.Success).value

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
            clients = SearchApiProvider(api),
            sessionHolder = holder,
            dispatchers = dispatchers,
            strings = FakeMediaStrings,
            logger = NoopNovumLogger,
        )
    }

    private fun item(id: String, name: String, type: String) = ItemDto(
        Id = id,
        Name = name,
        Type = type,
        ProductionYear = 2007,
        ImageTags = mapOf("Primary" to "tag-$id"),
    )
}

private data class SearchRequest(
    val term: String?,
    val itemTypes: String?,
    val startIndex: Int,
    val limit: Int,
)

private class SearchApiProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

private class SearchApi(
    private val results: List<ItemDto>,
    private val totalCount: Int = results.size,
) : EmbyApiStub() {
    val searchRequests = mutableListOf<SearchRequest>()
    var unfilteredRequests = 0
        private set

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
    ): QueryResultDto<ItemDto> {
        if (searchTerm == null) {
            unfilteredRequests++
            return QueryResultDto(Items = emptyList(), TotalRecordCount = 0)
        }
        searchRequests += SearchRequest(searchTerm, includeItemTypes, startIndex, limit)
        return QueryResultDto(
            Items = results.drop(startIndex).take(limit),
            TotalRecordCount = totalCount,
        )
    }
}
