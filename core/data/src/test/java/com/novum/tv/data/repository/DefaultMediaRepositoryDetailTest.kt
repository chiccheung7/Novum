package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.NameIdDto
import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.session.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings

/**
 * 详情相关映射测试：详情、季、分集、收藏。
 *
 * 重点验证 ticks → 毫秒换算与兜底规则，这两处最容易出错且直接表现为
 * 「续播位置错乱」「时长显示成 0」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryDetailTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun mapsDetailFields() = runTest(dispatcher) {
        val repository = repository(DetailApi())

        val detail = (repository.detail("series-1") as NovumResult.Success).value

        assertEquals("庆余年", detail.title)
        assertEquals(7.9f, detail.rating!!, 0.001f)
        assertEquals(45, detail.runtimeMinutes)
        assertEquals("TV-14", detail.officialRating)
        assertEquals("2024-05-01", detail.premiereDate)
        assertEquals("Ended", detail.status)
        assertTrue(detail.isFavorite)
        // 5,400,000,000 ticks = 540 秒 = 540,000 毫秒
        assertEquals(540_000L, detail.resumePositionMs)
        assertTrue(detail.backdropUrl!!.contains("/Items/series-1/Images/Backdrop"))
        // 演员与导演都进演职员列表，导演没有 Role 时用中文职位兜底
        assertEquals(listOf("张若昀", "李沁", "孙皓"), detail.cast.map { it.name })
        assertEquals("范闲", detail.cast.first().role)
        assertEquals("导演", detail.cast.last().role)
        assertEquals(listOf("新丽传媒"), detail.studios)
        assertTrue(detail.isSeries)
    }

    @Test
    fun mapsSeasonsAndEpisodes() = runTest(dispatcher) {
        val repository = repository(DetailApi())

        val seasons = (repository.seasons("series-1") as NovumResult.Success).value
        assertEquals(2, seasons.size)
        assertEquals(1, seasons.first().index)

        val episodes = (repository.episodes("series-1", "season-1") as NovumResult.Success).value
        assertEquals(2, episodes.size)
        assertEquals(1, episodes.first().seasonIndex)
        assertEquals(1, episodes.first().index)
        assertTrue(episodes.first().played)
        assertEquals(0L, episodes.first().progressMs)
        assertEquals(45, episodes.first().runtimeMinutes)
    }

    @Test
    fun setFavoriteReturnsServerState() = runTest(dispatcher) {
        val repository = repository(DetailApi())

        val added = repository.setFavorite("series-1", favorite = true)
        assertEquals(true, (added as NovumResult.Success).value)
    }

    @Test
    fun detailWithoutIdFallsBackToRequestedId() = runTest(dispatcher) {
        val repository = repository(NoIdApi())

        val detail = (repository.detail("requested-id") as NovumResult.Success).value

        assertEquals("requested-id", detail.id)
    }

    @Test
    fun episodeWithoutImageHasNullUrl() = runTest(dispatcher) {
        val repository = repository(DetailApi())

        val episodes = (repository.episodes("series-1", "season-1") as NovumResult.Success).value

        assertFalse(episodes.any { it.imageUrl != null })
    }

    @Test
    fun episodesCarrySeriesWideArtForUnifiedCards() = runTest(dispatcher) {
        val repository = repository(DetailApi())

        val episodes = (repository.episodes("series-1", "season-1") as NovumResult.Success).value

// 分集 16:9 卡统一使用剧集横版主视觉。
        // ——链首 = 剧集 Thumb
        assertTrue(
            episodes.all {
                it.seriesWideUrl!!.contains("/Items/series-1/Images/Thumb") &&
                    it.seriesWideUrl!!.contains("tag=thumb-tag")
            },
        )
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
            clients = FixedDetailProvider(api),
            sessionHolder = holder,
            dispatchers = dispatchers,
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
private class FixedDetailProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

/** 剧集详情 + 两季 + 两集的假 API。 */
private class DetailApi : EmbyApiStub() {

    override suspend fun detail(uid: String, itemId: String, fields: String): ItemDto = ItemDto(
        Id = "series-1",
        Name = "庆余年",
        Type = "Series",
        ProductionYear = 2024,
        CommunityRating = 7.9f,
        OfficialRating = "TV-14",
        RunTimeTicks = 27_000_000_000L,
        Overview = "少年范闲的成长故事",
        Genres = listOf("剧情", "古装"),
        ImageTags = mapOf("Primary" to "ptag"),
        BackdropImageTags = listOf("btag"),
        PremiereDate = "2024-05-01T00:00:00.0000000Z",
        Status = "Ended",
        ChildCount = 36,
        Studios = listOf(NameIdDto(Name = "新丽传媒", Id = "studio-1")),
        People = listOf(
            PersonDto(Name = "张若昀", Role = "范闲", Type = "Actor", Id = "p1", PrimaryImageTag = "t1"),
            PersonDto(Name = "李沁", Role = "林婉儿", Type = "Actor", Id = "p2"),
            PersonDto(Name = "孙皓", Type = "Director", Id = "p3"),
        ),
        UserData = UserDataDto(
            PlaybackPositionTicks = 5_400_000_000L,
            IsFavorite = true,
        ),
    )

    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto()

    override suspend fun seasons(
        seriesId: String,
        userId: String,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(Id = "season-1", Name = "第 1 季", IndexNumber = 1, ChildCount = 36),
            ItemDto(Id = "season-2", Name = "第 2 季", IndexNumber = 2, ChildCount = 36),
        ),
        TotalRecordCount = 2,
    )

    override suspend fun episodes(
        seriesId: String,
        userId: String,
        seasonId: String?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(
                Id = "ep-1",
                Name = "初入京都",
                IndexNumber = 1,
                ParentIndexNumber = 1,
                SeriesId = "series-1",
                SeriesPrimaryImageTag = "poster-tag",
                SeriesThumbImageTag = "thumb-tag",
                RunTimeTicks = 27_000_000_000L,
                UserData = UserDataDto(Played = true),
            ),
            ItemDto(
                Id = "ep-2",
                Name = "夜宴",
                IndexNumber = 2,
                ParentIndexNumber = 1,
                SeriesId = "series-1",
                SeriesPrimaryImageTag = "poster-tag",
                SeriesThumbImageTag = "thumb-tag",
                RunTimeTicks = 27_000_000_000L,
                UserData = UserDataDto(Played = false, PlaybackPositionTicks = 1_000_000L),
            ),
        ),
        TotalRecordCount = 2,
    )

    override suspend fun addFavorite(uid: String, itemId: String): UserDataDto =
        UserDataDto(IsFavorite = true)

    override suspend fun removeFavorite(uid: String, itemId: String): UserDataDto =
        UserDataDto(IsFavorite = false)
}

/** 服务端不返回 id 的假 API，用于验证兜底 id。 */
private class NoIdApi : EmbyApiStub() {
    override suspend fun detail(uid: String, itemId: String, fields: String): ItemDto =
        ItemDto(Name = "无 id 条目", Type = "Movie")
}
