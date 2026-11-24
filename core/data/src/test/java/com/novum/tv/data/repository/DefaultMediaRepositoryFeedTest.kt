package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.session.Session
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings

/** 用运行当天做日期基准，避免测试数据随时间过期。 */
private val today: String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

/**
 * 首页聚合测试：Hero 候选池口径（近期电影 + 近期剧集合并、三级兜底、
 * 继续观看剔除）、剧集去重、窗口过滤与空区块隐藏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryFeedTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun buildsHeroPoolFromRecentMoviesAndSeries() = runTest(dispatcher) {
        val repo = repository(FeedApi())
        // 固定种子：轮换是按日 seed 的确定性洗牌，不固定种子测试会随日期 flaky
        repo.heroSeedProvider = { 42L }
        val feed = (repo.feed() as NovumResult.Success).value

        // Hero 池 = 近期电影（评分序）+ 近期上新剧集（上新时间序），背景图可用是硬门槛；
        // 三池交错 + 确定性洗牌——小池下两个候选都入选，集合语义断言
        // movie-1 有背景图、series-1 有背景图；series-2「另一部剧」无背景图被滤掉
        assertEquals(setOf("movie-1", "series-1"), feed.hero.map { it.id }.toSet())
        assertEquals(HERO_EXPECTED_SIZE, feed.hero.size)

        // 继续观看条目不应占用 Hero 推荐位。
        assertTrue(feed.hero.none { it.id == "ep-9" })

        // 近一年之外的电影被过滤（服务端参数不可靠，靠本地兜底）
        assertTrue(feed.hero.none { it.id == "movie-old" })

        // 一月内无更新的剧不进候选（series-3 停更）
        assertTrue(feed.hero.none { it.id == "series-3" })

        // Hero 候选池内剧集条目按通用映射（副标题 = 年份 · 剧集）
        val seriesEntry = feed.hero.single { it.id == "series-1" }
        assertEquals("剧集", seriesEntry.subtitle)

        // 继续观看的进度按 ticks 换算
        val resume = feed.resume!!.items.single()
        assertEquals(0.5f, resume.progress!!, 0.001f)
        assertEquals("红高粱", resume.title)
        // `SxxExx · 分集标题`；标题仅为序号（第9集/S01E09）时判为重复
        assertEquals("S01E09", resume.subtitle)

        // 两个库各一行，空库不下发
        assertEquals(2, feed.latest.size)
        assertEquals("华语电影", feed.latest[0].libraryName)
        assertEquals("华语剧集", feed.latest[1].libraryName)

        // 下一集为空 → 整行为 null
        assertNull(feed.nextUp)
    }

    @Test
    fun heroFallsBackToLatestWhenPoolsHaveNoBackdrops() = runTest(dispatcher) {
        // 电影/剧综候选池全部没有背景图 → 第三级兜底：各库 latest 里有背景图的条目顶上，
        // 保证 uhdnow 正常时 Hero 必有内容（单元素池洗牌无歧义，无需固定种子）
        val feed = (repository(BackdroplessPoolsApi()).feed() as NovumResult.Success).value

        assertEquals(listOf("movie-2"), feed.hero.map { it.id })
    }

    @Test
    fun heroRotationIsDeterministicPerSeed() = runTest(dispatcher) {
        // 同一天内 banner 必须稳定：同 seed 两次刷新（回页/下拉/焦点重锚）顺序全等
        val repo = repository(LargeRotationApi())
        repo.heroSeedProvider = { 7L }
        val first = (repo.feed() as NovumResult.Success).value.hero.map { it.id }
        val second = (repo.feed() as NovumResult.Success).value.hero.map { it.id }

        assertEquals("同种子两次刷新 banner 顺序应一致（同日稳定）", first, second)
    }

    @Test
    fun heroRotationChangesAcrossSeeds() = runTest(dispatcher) {
        // 不同日期种子应产生不同排列。
        // 交错池 16 条取 8，两种子撞同一有序采样的概率 ~1/4×10^8，可视为不可能
        val repo = repository(LargeRotationApi())
        repo.heroSeedProvider = { 1L }
        val day1 = (repo.feed() as NovumResult.Success).value.hero.map { it.id }
        repo.heroSeedProvider = { 2L }
        val day2 = (repo.feed() as NovumResult.Success).value.hero.map { it.id }

        assertTrue("跨天应换一批 banner 排列", day1 != day2)
    }

    @Test
    fun heroRotationKeepsQualityGates() = runTest(dispatcher) {
        // 洗牌只排列不筛选：无论种子怎么洗，Hero 都来自「有背景图 + 非续播」的候选
        val repo = repository(FeedApi())
        repeat(20) { attempt ->
            repo.heroSeedProvider = { attempt.toLong() }
            val hero = (repo.feed() as NovumResult.Success).value.hero
            assertTrue(
                "attempt=$attempt 续播条目不得进 Hero",
                hero.none { it.id == "ep-9" },
            )
            assertTrue(
                "attempt=$attempt 无背景图条目不得进 Hero",
                hero.none { it.id in setOf("series-2", "movie-old", "series-3") },
            )
        }
    }

    @Test
    fun emptyServerProducesEmptyFeed() = runTest(dispatcher) {
        val feed = (repository(EmptyFeedApi()).feed() as NovumResult.Success).value

        assertTrue(feed.isEmpty)
        assertNull(feed.resume)
    }

    @Test
    fun resumeRowAbsorbsNextUpEntries() = runTest(dispatcher) {
        // 看完或追平的剧集由服务端 NextUp 递补进
        // 继续观看行——上新（新一季/新一集）后该剧集自动回到本行。
        // 去重规则：与在看的同 id 剔除；同剧集已有在看的卡剔除；同剧集多集只留首个。
        val feed = (repository(NextUpMergeApi()).feed() as NovumResult.Success).value

        assertEquals(listOf("ep-9", "ep-8-1"), feed.resume?.items?.map { it.id })
        // NextUp 条目并入继续观看行；独立字段保持为空。
        assertNull(feed.nextUp)
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
        val clients = object : EmbyApiProvider {
            override fun api(baseUrl: String): EmbyApi = api
        }
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

/** buildsHeroPool 用例期望的 Hero 条数（小池两候选）。 */
private const val HERO_EXPECTED_SIZE = 2

/**
 * 大候选池变体：8 部有背景图电影 + 8 部近一周有更新的有背景图剧集——
 * 交错池 16 条，验证按日轮换的确定性与跨天变化。
 */
private open class LargeRotationApi : EmbyApiStub() {

    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto(
            Items = listOf(ItemDto(Id = "lib-tv", Name = "华语剧集", CollectionType = "tvshows")),
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
    ): QueryResultDto<ItemDto> = when (includeItemTypes) {
        "Movie" -> QueryResultDto(
            Items = (1..8).map { index ->
                ItemDto(
                    Id = "m-$index",
                    Name = "电影 $index",
                    Type = "Movie",
                    CommunityRating = 9f - index * 0.1f,
                    BackdropImageTags = listOf("bt-$index"),
                    PremiereDate = "${today}T00:00:00.000Z",
                )
            },
            TotalRecordCount = 8,
        )

        else -> QueryResultDto()
    }

    override suspend fun latest(
        uid: String,
        parentId: String,
        limit: Int,
        fields: String,
    ): List<ItemDto> = (1..8).map { index ->
        ItemDto(
            Id = "s-$index",
            Name = "剧集 $index",
            Type = "Series",
            BackdropImageTags = listOf("sbt-$index"),
            DateLastMediaAdded = "${today}T00:00:00.000Z",
        )
    }
}

/** 覆盖首页五区块的假 API。 */
private open class FeedApi : EmbyApiStub() {

    /** NextUp 返回（继续观看合并测试用），默认空。 */
    protected open val nextUpItems: List<ItemDto> = emptyList()

    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto(
            Items = listOf(
                ItemDto(Id = "lib-movies", Name = "华语电影", CollectionType = "movies"),
                ItemDto(Id = "lib-tv", Name = "华语剧集", CollectionType = "tvshows"),
            ),
            TotalRecordCount = 2,
        )

    override suspend fun resume(uid: String, limit: Int, parentId: String?, mediaTypes: String, fields: String) =
        QueryResultDto(
            Items = listOf(
                ItemDto(
                    Id = "ep-9",
                    Name = "第 9 集",
                    Type = "Episode",
                    SeriesId = "series-9",
                    SeriesName = "红高粱",
                    ParentIndexNumber = 1,
                    IndexNumber = 9,
                    RunTimeTicks = 1_000L,
                    // 带背景图但不进 Hero：验证「继续观看从 Hero 池剔除」
                    BackdropImageTags = listOf("rbt"),
                    UserData = UserDataDto(PlaybackPositionTicks = 500L),
                ),
            ),
            TotalRecordCount = 1,
        )

    override suspend fun nextUp(uid: String, limit: Int, fields: String, seriesId: String?) =
        QueryResultDto(Items = nextUpItems, TotalRecordCount = nextUpItems.size)

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
    ): QueryResultDto<ItemDto> = when (includeItemTypes) {
        "Movie" -> QueryResultDto(
            Items = listOf(
                ItemDto(
                    Id = "movie-1",
                    Name = "顺流逆流",
                    Type = "Movie",
                    ProductionYear = 2000,
                    CommunityRating = 8.2f,
                    ImageTags = mapOf("Primary" to "pt"),
                    BackdropImageTags = listOf("bt"),
                    PremiereDate = "${today}T00:00:00.000Z",
                ),
                ItemDto(
                    Id = "movie-old",
                    Name = "老片",
                    Type = "Movie",
                    ProductionYear = 1988,
                    CommunityRating = 9.9f,
                    PremiereDate = "1988-01-01T00:00:00.000Z",
                ),
            ),
            TotalRecordCount = 2,
        )

        else -> QueryResultDto()
    }

    override suspend fun latest(
        uid: String,
        parentId: String,
        limit: Int,
        fields: String,
    ): List<ItemDto> = when (parentId) {
        "lib-movies" -> listOf(ItemDto(Id = "movie-2", Name = "新片", Type = "Movie"))
        "lib-tv" -> listOf(
            ItemDto(
                Id = "series-1",
                Name = "庆余年",
                Type = "Series",
                ImageTags = mapOf("Primary" to "spt"),
                BackdropImageTags = listOf("sbt"),
                CommunityRating = 7.9f,
                RecursiveItemCount = 36,
                Status = "Continuing",
                DateLastMediaAdded = "${today}T00:00:00.000Z",
            ),
            ItemDto(
                Id = "series-2",
                Name = "另一部剧",
                Type = "Series",
                ImageTags = mapOf("Primary" to "spt2"),
                RecursiveItemCount = 12,
                Status = "Ended",
                DateLastMediaAdded = "${today}T00:00:00.000Z",
            ),
            ItemDto(
                Id = "series-3",
                Name = "停更的剧",
                Type = "Series",
                DateLastMediaAdded = "2024-01-01T00:00:00.000Z",
                DateCreated = "2024-01-01T00:00:00.000Z",
            ),
        )

        else -> emptyList()
    }
}

/**
 * 电影/剧综候选池全无背景图的变体：验证 Hero 三级兜底的第三级——
 * 各库 latest 里有背景图的条目顶上（服务器正常时 Hero 必有内容）。
 */
/** 继续观看合并 NextUp 的去重用例：在看 ep-9（series-9），NextUp 回四条。 */
private class NextUpMergeApi : FeedApi() {
    override val nextUpItems: List<ItemDto> = listOf(
        // 与在看的同分集 id → 剔除
        ItemDto(
            Id = "ep-9",
            Type = "Episode",
            SeriesId = "series-9",
            SeriesName = "红高粱",
            ParentIndexNumber = 1,
            IndexNumber = 9,
            RunTimeTicks = 1_000L,
        ),
        // 同剧集（series-9）的另一集 → 剔除（在看的卡已代表该剧集）
        ItemDto(
            Id = "ep-10",
            Type = "Episode",
            SeriesId = "series-9",
            SeriesName = "红高粱",
            ParentIndexNumber = 1,
            IndexNumber = 10,
            RunTimeTicks = 1_000L,
        ),
        // 新剧集（series-8）的第一集 → 保留
        ItemDto(
            Id = "ep-8-1",
            Type = "Episode",
            SeriesId = "series-8",
            SeriesName = "另一部剧",
            ParentIndexNumber = 1,
            IndexNumber = 1,
            RunTimeTicks = 1_000L,
        ),
        // 新剧集的第二集 → 剔除（同剧集只留首个）
        ItemDto(
            Id = "ep-8-2",
            Type = "Episode",
            SeriesId = "series-8",
            SeriesName = "另一部剧",
            ParentIndexNumber = 1,
            IndexNumber = 2,
            RunTimeTicks = 1_000L,
        ),
    )
}

private class BackdroplessPoolsApi : FeedApi() {
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
    ): QueryResultDto<ItemDto> = when (includeItemTypes) {
        // movie-1 不带背景图（区别于 [FeedApi]）：候选池里没有可展示的电影
        "Movie" -> QueryResultDto(
            Items = listOf(
                ItemDto(
                    Id = "movie-1",
                    Name = "顺流逆流",
                    Type = "Movie",
                    ProductionYear = 2000,
                    CommunityRating = 8.2f,
                    ImageTags = mapOf("Primary" to "pt"),
                    PremiereDate = "${today}T00:00:00.000Z",
                ),
                ItemDto(
                    Id = "movie-old",
                    Name = "老片",
                    Type = "Movie",
                    ProductionYear = 1988,
                    CommunityRating = 9.9f,
                    PremiereDate = "1988-01-01T00:00:00.000Z",
                ),
            ),
            TotalRecordCount = 2,
        )

        else -> QueryResultDto()
    }

    override suspend fun latest(
        uid: String,
        parentId: String,
        limit: Int,
        fields: String,
    ): List<ItemDto> = when (parentId) {
        // movie-2 带背景图：兜底条目
        "lib-movies" -> listOf(
            ItemDto(Id = "movie-2", Name = "新片", Type = "Movie", BackdropImageTags = listOf("fbt")),
        )
        // 剧集无背景图：全滤掉，不影响兜底路径
        "lib-tv" -> listOf(
            ItemDto(
                Id = "series-1",
                Name = "庆余年",
                Type = "Series",
                ImageTags = mapOf("Primary" to "spt"),
                RecursiveItemCount = 36,
                Status = "Continuing",
                DateLastMediaAdded = "${today}T00:00:00.000Z",
            ),
            ItemDto(
                Id = "series-2",
                Name = "另一部剧",
                Type = "Series",
                ImageTags = mapOf("Primary" to "spt2"),
                RecursiveItemCount = 12,
                Status = "Ended",
                DateLastMediaAdded = "${today}T00:00:00.000Z",
            ),
            ItemDto(
                Id = "series-3",
                Name = "停更的剧",
                Type = "Series",
                DateLastMediaAdded = "2024-01-01T00:00:00.000Z",
                DateCreated = "2024-01-01T00:00:00.000Z",
            ),
        )

        else -> emptyList()
    }
}

/** 全空的假 API：用于验证整页空态。 */
private class EmptyFeedApi : EmbyApiStub() {
    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto<ItemDto>()
}
