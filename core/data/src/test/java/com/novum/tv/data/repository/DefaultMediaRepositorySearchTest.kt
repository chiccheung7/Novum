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
import org.junit.Test

/**
 * 搜索测试：本地目录负责"任意位置命中"，不依赖服务端 SearchTerm 的中文前缀语义。
 *
 * 背景：Emby 的 SearchTerm 对中文只认从头匹配（官方 FAQ：至少两个字符、
 * 从第一个字开始），搜「大明」拿不到「大明王朝1566」，而「1566」这种数字
 * 片段反而能命中——实机反馈正是这个现象。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositorySearchTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    /** 搜「大明」：服务端返回空，本地目录要能命中「大明王朝1566」。 */
    @Test
    fun matchesAnywhereInTitle() = runTest(dispatcher) {
        val api = CatalogApi(
            listOf(
                item("series-1", "大明王朝1566", "Series"),
                item("movie-1", "顺流逆流", "Movie"),
            ),
        )
        val repository = repository(api)

        val page = success(repository.items(parentId = null, startIndex = 0, limit = 60, searchTerm = "大明"))

        assertEquals(listOf("大明王朝1566"), page.items.map { it.title })
        // 服务端 SearchTerm 从头到尾没被用来做关键词检索
        assertEquals(0, api.searchTermCalls)
    }

    /** 相关度：完全相同 > 前缀 > 中间包含。 */
    @Test
    fun ranksExactThenPrefixThenContains() = runTest(dispatcher) {
        val api = CatalogApi(
            listOf(
                item("m-3", "海大明", "Movie"),
                item("m-2", "大明王朝1566", "Series"),
                item("m-1", "大明", "Movie"),
            ),
        )
        val repository = repository(api)

        val page = success(repository.items(parentId = null, startIndex = 0, limit = 60, searchTerm = "大明"))

        assertEquals(listOf("大明", "大明王朝1566", "海大明"), page.items.map { it.title })
    }

    /** 分页：total 是全部命中数，按 startIndex/limit 切片。 */
    @Test
    fun pagesThroughLocalMatches() = runTest(dispatcher) {
        val api = CatalogApi(
            listOf(
                item("s-1", "大明王朝1566", "Series"),
                item("s-2", "大明风华", "Series"),
                item("s-3", "大明宫词", "Series"),
            ),
        )
        val repository = repository(api)

        val first = success(repository.items(parentId = null, startIndex = 0, limit = 2, searchTerm = "大明"))
        val second = success(repository.items(parentId = null, startIndex = 2, limit = 2, searchTerm = "大明"))

        assertEquals(3, first.total)
        assertEquals(2, first.items.size)
        assertEquals(1, second.items.size)
    }

    /** 类型过滤：剧集筛选不返回同名电影。 */
    @Test
    fun filtersByItemType() = runTest(dispatcher) {
        val api = CatalogApi(
            listOf(
                item("s-1", "大明王朝1566", "Series"),
                item("m-1", "大明劫", "Movie"),
            ),
        )
        val repository = repository(api)

        val page = success(repository.items(parentId = null, startIndex = 0, limit = 60, searchTerm = "大明", includeItemTypes = "Series"))

        assertEquals(listOf("大明王朝1566"), page.items.map { it.title })
    }

    /** 目录只拉一次：连续搜索不重复请求全库。 */
    @Test
    fun catalogIsFetchedOnce() = runTest(dispatcher) {
        val api = CatalogApi(listOf(item("s-1", "大明王朝1566", "Series")))
        val repository = repository(api)

        repository.items(parentId = null, startIndex = 0, limit = 60, searchTerm = "大明")
        repository.items(parentId = null, startIndex = 0, limit = 60, searchTerm = "王朝")

        assertEquals(1, api.catalogCalls)
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
        val clients = CatalogProvider(api)
        return DefaultMediaRepository(
            clients = clients,
            sessionHolder = holder,
            dispatchers = dispatchers,
            personIndex = PersonIndexStore(
                kotlin.io.path.createTempFile("person_index").toFile(),
                ioDispatcher = dispatcher,
            ),
            searchCatalog = testSearchCatalog(clients, holder, dispatchers),
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

/** 固定提供者。 */
private class CatalogProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

/**
 * 模拟 Emby：`SearchTerm` 只做"从头匹配"（搜「大明」返回空），
 * 无关键词的列表请求则返回全库。
 */
private class CatalogApi(private val catalog: List<ItemDto>) : EmbyApiStub() {

    /** 目录（无关键词）请求次数。 */
    var catalogCalls = 0
        private set

    /** 带关键词的请求次数（应当为 0：搜索不再依赖服务端语义）。 */
    var searchTermCalls = 0
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
        if (searchTerm != null) {
            searchTermCalls++
            // Emby 行为：中文只从头匹配，这里直接返回空模拟「搜不到」
            return QueryResultDto(Items = emptyList(), TotalRecordCount = 0)
        }
        catalogCalls++
        val slice = catalog.drop(startIndex).take(limit)
        return QueryResultDto(Items = slice, TotalRecordCount = catalog.size)
    }
}
