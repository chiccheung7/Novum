package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
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
import org.junit.Test

/**
 * 搜索目录缓存归属的回归测试。
 *
 * [SearchCatalogStore] 是进程级单例：退出登录再换服务器/换账号后，目录必须按
 * 「服务器 + 用户」重新拉取——否则新会话的搜索会拿旧服务器、旧账号可见范围里的
 * 条目（连同旧服务器的图片地址）作答。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchCatalogStoreTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun sameSessionFetchesCatalogOnce() = runTest(dispatcher) {
        val holder = SessionHolder().apply { update(session(server = "https://a.example", userId = "user-a")) }
        val api = MutableCatalogApi(listOf(item("a-1", "条目 A")))
        val store = store(api, holder)

        store.load()
        store.load()

        assertEquals("同一会话只拉一次全库", 1, api.catalogCalls)
    }

    @Test
    fun catalogIsRefetchedAfterSwitchingServerAndUser() = runTest(dispatcher) {
        val holder = SessionHolder().apply { update(session(server = "https://a.example", userId = "user-a")) }
        val api = MutableCatalogApi(listOf(item("a-1", "旧服务器条目")))
        val store = store(api, holder)
        assertEquals(listOf("旧服务器条目"), store.load().map { it.title })

        api.catalog = listOf(item("b-1", "新服务器条目"))
        holder.update(session(server = "https://b.example", userId = "user-b"))

        assertEquals("换服务器/账号后必须重拉目录", listOf("新服务器条目"), store.load().map { it.title })
        assertEquals(2, api.catalogCalls)
    }

    @Test
    fun catalogIsRefetchedWhenOnlyUserChanges() = runTest(dispatcher) {
        val holder = SessionHolder().apply { update(session(server = "https://a.example", userId = "user-a")) }
        val api = MutableCatalogApi(listOf(item("a-1", "用户 A 可见")))
        val store = store(api, holder)
        store.load()

        api.catalog = listOf(item("a-2", "用户 B 可见"))
        holder.update(session(server = "https://a.example", userId = "user-b"))

        assertEquals("同服务器换账号：可见范围不同，必须重拉", listOf("用户 B 可见"), store.load().map { it.title })
        assertEquals(2, api.catalogCalls)
    }

    private fun store(api: EmbyApi, holder: SessionHolder): SearchCatalogStore = SearchCatalogStore(
        clients = SessionCatalogProvider(api),
        sessionHolder = holder,
        strings = FakeMediaStrings,
        dispatchers = dispatchers,
        logger = NoopNovumLogger,
        personIndex = PersonIndexStore(
            kotlin.io.path.createTempFile("catalog_session_index").toFile(),
            ioDispatcher = dispatcher,
        ),
    )

    private fun session(server: String, userId: String) = Session(
        server = server,
        token = "token",
        userId = userId,
        userName = "user",
        serverName = "server",
        deviceId = "device-1",
    )

    private fun item(id: String, name: String) = ItemDto(Id = id, Name = name, Type = "Movie")
}

/** 固定提供者（命名避开 DefaultAuthRepositoryTest 的 FixedProvider，同包同名顶层类会冲突）。 */
private class SessionCatalogProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

/** 可变全库桩：目录请求按当前 catalog 返回，并计数。 */
private class MutableCatalogApi(var catalog: List<ItemDto>) : EmbyApiStub() {
    var catalogCalls = 0
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
        catalogCalls++
        return QueryResultDto(Items = catalog.drop(startIndex).take(limit), TotalRecordCount = catalog.size)
    }
}
