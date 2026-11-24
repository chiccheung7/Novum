package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.AuthRequestDto
import com.novum.tv.data.remote.emby.AuthResultDto
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.PublicInfoDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings

/**
 * 媒体库仓储测试：DTO 映射、图片地址拼接、未登录短路。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun mapsLibrariesAndItems() = runTest(dispatcher) {
        val repository = repository()

        val libraries = repository.libraries()
        assertTrue(libraries is NovumResult.Success)
        val list = (libraries as NovumResult.Success).value
        assertEquals("华语电影", list.first().name)
        assertEquals("movies", list.first().kind)
        assertEquals(3529, list.first().itemCount)

        val page = repository.items(parentId = "lib-1", startIndex = 0, limit = 60)
        val items = (page as NovumResult.Success).value.items
        assertEquals(1, items.size)
        assertEquals("顺流逆流", items.first().title)
        assertTrue(items.first().imageUrl!!.startsWith("https://smart.uhdnow.com/Items/movie-1/Images/Primary"))
        assertEquals("2000 · 电影", items.first().subtitle)
    }

    @Test
    fun returnsUnauthorizedWhenNoSession() = runTest(dispatcher) {
        val clients = FixedMediaProvider(FakeMediaApi())
        val holder = SessionHolder()
        val repository = DefaultMediaRepository(
            clients = clients,
            sessionHolder = holder,
            dispatchers = dispatchers,
            searchCatalog = testSearchCatalog(clients, holder, dispatchers),
            personIndex = PersonIndexStore(
                kotlin.io.path.createTempFile("person_index").toFile(),
                // 同类文件 IO 走测试调度器，防真实 IO 线程越过用例边界回补续体
                ioDispatcher = dispatcher,
            ),
            strings = FakeMediaStrings,
            logger = NoopNovumLogger,
        )

        val result = repository.libraries()

        assertTrue(result is NovumResult.Failure)
    }

    private fun repository(): DefaultMediaRepository {
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
        val clients = FixedMediaProvider(FakeMediaApi())
        return DefaultMediaRepository(
            clients = clients,
            sessionHolder = holder,
            dispatchers = dispatchers,
            searchCatalog = testSearchCatalog(clients, holder, dispatchers),
            personIndex = PersonIndexStore(
                kotlin.io.path.createTempFile("person_index").toFile(),
                // 同类文件 IO 走测试调度器，防真实 IO 线程越过用例边界回补续体
                ioDispatcher = dispatcher,
            ),
            strings = FakeMediaStrings,
            logger = NoopNovumLogger,
        )
    }
}

/** 固定 API 提供者。 */
private class FixedMediaProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

/** 返回一条媒体库与一条电影的假 API。 */
private class FakeMediaApi : EmbyApiStub() {

    override suspend fun publicInfo(): PublicInfoDto = PublicInfoDto(ServerName = "UHD")

    override suspend fun authenticate(body: AuthRequestDto): AuthResultDto = AuthResultDto()

    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto(
            Items = listOf(
                ItemDto(
                    Id = "lib-1",
                    Name = "华语电影",
                    CollectionType = "movies",
                    ChildCount = 3529,
                ),
            ),
            TotalRecordCount = 1,
        )

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
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(
                Id = "movie-1",
                Name = "顺流逆流",
                Type = "Movie",
                ProductionYear = 2000,
                ImageTags = mapOf("Primary" to "tag-1"),
            ),
        ),
        TotalRecordCount = 1,
    )

    override suspend fun resume(uid: String, limit: Int, parentId: String?, mediaTypes: String, fields: String) =
        QueryResultDto<ItemDto>()

    override suspend fun nextUp(uid: String, limit: Int, fields: String, seriesId: String?) = QueryResultDto<ItemDto>()

    override suspend fun latest(uid: String, parentId: String, limit: Int, fields: String) =
        emptyList<ItemDto>()
}
