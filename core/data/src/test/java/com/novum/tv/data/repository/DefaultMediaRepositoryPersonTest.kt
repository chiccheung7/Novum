package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人物搜索测试：三路来源（Persons 端点 / 条目 People / 本地索引）的合并与排序。
 *
 * 背景：实机上「搜演员名搜不到人」——`/Persons` 在部分网关不转发，
 * 必须能从条目搜索结果的 `People[]` 兜底出人物。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryPersonTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    /** `/Persons` 正常返回；头像版本号走 `ImageTags.Primary`（该端点的口径）。 */
    @Test
    fun personsEndpointIsUsedFirst() = runTest(dispatcher) {
        val repository = repository(PersonsApi())

        val hits = repository.searchPersons("张若昀")

        assertEquals(listOf("张若昀"), hits.map { it.name })
        assertEquals("p1", hits.single().personId)
        // `ImageTags.Primary` 必须被认成头像版本号，否则人物卡全是无图占位
        assertTrue(
            "头像 URL 应带 ImageTags.Primary 的版本号",
            hits.single().imageUrl?.contains("tag-1") == true,
        )
    }

    /** `/Persons` 不可用（网关不转发）时，从条目搜索结果的 People 兜底。 */
    @Test
    fun fallsBackToItemPeopleWhenPersonsEndpointFails() = runTest(dispatcher) {
        val repository = repository(BrokenPersonsApi())

        val hits = repository.searchPersons("张若昀")

        assertEquals(listOf("张若昀"), hits.map { it.name })
    }

    /** 兜底来源按姓名过滤：只留包含关键词的人，不把整部剧的演职员都列出来。 */
    @Test
    fun derivedPeopleAreFilteredByKeyword() = runTest(dispatcher) {
        val repository = repository(BrokenPersonsApi())

        val hits = repository.searchPersons("李")

        assertEquals(listOf("李沁"), hits.map { it.name })
    }

    /** 完全相同 > 前缀 > 包含：搜索词就是人名时，本人必须排第一。 */
    @Test
    fun exactNameRanksBeforePartialMatches() = runTest(dispatcher) {
        val repository = repository(PrefixApi())

        val hits = repository.searchPersons("李沁")

        assertEquals("李沁", hits.first().name)
    }

    /** 同一人出现在多个来源时按 id 去重，并保留带头像的那条。 */
    /**
     * 回归：部分网关把 `/Persons` 接到普通条目搜索上，返回的是**条目**。
     *
     * 人物区因此摆出过一排电影海报（实测）。只有 `Type == Person`（或缺省）
     * 的结果才允许进人物区。
     */
    @Test
    fun personsEndpointItemsAreFilteredByType() = runTest(dispatcher) {
        val repository = repository(ItemsAsPersonsApi())

        val hits = repository.searchPersons("信号")

        assertEquals(listOf("真人演员"), hits.map { it.name })
    }

    @Test
    fun mergesDuplicatePersonFromDifferentSources() = runTest(dispatcher) {
        val repository = repository(DuplicateApi())

        val hits = repository.searchPersons("张若昀")

        assertEquals(1, hits.size)
        assertNotNull("合并后应保留有头像的那条", hits.single().imageUrl)
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
        val clients = FixedApiProvider(api)
        return DefaultMediaRepository(
            clients = clients,
            sessionHolder = holder,
            dispatchers = dispatchers,
            searchCatalog = testSearchCatalog(clients, holder, dispatchers),
            personIndex = PersonIndexStore(
                kotlin.io.path.createTempFile("person_index").toFile(),
                ioDispatcher = dispatcher,
            ),
            strings = FakeMediaStrings,
            logger = NoopNovumLogger,
        )
    }
}

/** 固定返回同一个 API 实例。 */
private class FixedApiProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

/** `/Persons` 正常返回。 */
private class PersonsApi : EmbyApiStub() {
    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto(
        Items = listOf(
            PersonDto(
                Id = "p1",
                Name = "张若昀",
                // /Persons 端点返回的条目 Type 是 Person（People[] 里才是 Actor 等职位）
                Type = "Person",
                ImageTags = mapOf("Primary" to "tag-1"),
            ),
        ),
        TotalRecordCount = 1,
    )
}

/**
 * `/Persons` 404：模拟不转发该路由的网关；条目搜索里挂 People。
 */
private class BrokenPersonsApi : EmbyApiStub() {
    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = throw IllegalStateException("HTTP 404 /Persons")

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
                Id = "series-1",
                Name = "庆余年",
                Type = "Series",
                People = listOf(
                    PersonDto(Id = "p1", Name = "张若昀", Type = "Actor"),
                    PersonDto(Id = "p2", Name = "李沁", Type = "Actor"),
                ),
            ),
        ),
        TotalRecordCount = 1,
    )
}

/** 服务端顺序：包含匹配在前、完全匹配在后——客户端要按相关度重排。 */
private class PrefixApi : EmbyApiStub() {
    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto(
        Items = listOf(
            PersonDto(Id = "p2", Name = "李沁儿"),
            PersonDto(Id = "p1", Name = "李沁"),
        ),
        TotalRecordCount = 2,
    )
}

/** 同一 id 同时出现在两路来源：`/Persons` 无图，条目 People 有图。 */
private class DuplicateApi : EmbyApiStub() {
    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto(
        Items = listOf(PersonDto(Id = "p1", Name = "张若昀")),
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
                Id = "series-1",
                Name = "庆余年",
                Type = "Series",
                People = listOf(
                    PersonDto(Id = "p1", Name = "张若昀", Type = "Actor", PrimaryImageTag = "tag-9"),
                ),
            ),
        ),
        TotalRecordCount = 1,
    )
}

/** `/Persons` 返回的其实是条目（Type != Person）：必须被过滤掉。 */
private class ItemsAsPersonsApi : EmbyApiStub() {
    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto(
        Items = listOf(
            PersonDto(Id = "movie-1", Name = "信号", Type = "Series"),
            PersonDto(Id = "movie-2", Name = "信號", Type = "Movie"),
            PersonDto(Id = "person-1", Name = "真人演员"),
        ),
        TotalRecordCount = 3,
    )
}
