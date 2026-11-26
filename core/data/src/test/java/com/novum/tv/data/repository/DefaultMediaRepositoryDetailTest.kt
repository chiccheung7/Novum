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
        // 季 id 用于详情页默认季定位（网关可能不给 ParentIndexNumber）
        assertEquals("season-1", episodes.first().seasonId)
        assertTrue(episodes.first().played)
        assertEquals(0L, episodes.first().progressMs)
        assertEquals(45, episodes.first().runtimeMinutes)
    }

    @Test
    fun seasonCastMergesEpisodePeopleByFrequency() = runTest(dispatcher) {
        val repository = repository(DetailApi())

        val cast = (repository.seasonCast("series-1", "season-1") as NovumResult.Success).value

        // 张若昀两集都在 → 排第一；李沁/客串同频次，按首次出现顺序
        assertEquals(listOf("张若昀", "李沁", "王启年"), cast.map { it.name })
        assertEquals("范闲", cast.first().role)
        assertEquals("客串", cast.last().role)
    }

    @Test
    fun seasonCastReturnsEmptyWhenPeopleMissing() = runTest(dispatcher) {
        val repository = repository(NoPeopleApi())

        val cast = (repository.seasonCast("series-1", "season-1") as NovumResult.Success).value

        assertTrue("季级无演员时应为空，由页面回落剧集级", cast.isEmpty())
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
            episodes.all { episode ->
                episode.seriesWideUrl?.contains("/Items/series-1/Images/Thumb") == true &&
                    episode.seriesWideUrl.contains("tag=thumb-tag")
            },
        )
    }

    @Test
    fun seriesNextUpFiltersOtherSeriesReturnedByGateway() = runTest(dispatcher) {
        // 网关忽略 SeriesId，把全库 NextUp 首条（别的剧）也返回：必须客户端过滤，
        // 否则详情页会拿别的剧的分集当定位锚点，默认季错到第一季。
        val repository = repository(WrongSeriesNextUpApi())

        val hint = (repository.seriesNextUp("series-1") as NovumResult.Success).value

        assertEquals("mine-ep", hint?.id)
        assertEquals(2, hint?.seasonIndex)
        assertEquals("season-2", hint?.seasonId)
    }

    @Test
    fun seriesNextUpFallsBackToSeriesResume() = runTest(dispatcher) {
        // NextUp 只给了别的剧集 → 回查该剧续播分集作为定位锚点
        val repository = repository(ResumeFallbackApi())

        val hint = (repository.seriesNextUp("series-1") as NovumResult.Success).value

        assertEquals("resume-ep", hint?.id)
        assertEquals("season-2", hint?.seasonId)
    }

    @Test
    fun seriesNextUpAcceptsSoleCandidateWithoutSeriesId() = runTest(dispatcher) {
        // 极简网关完全不下发 SeriesId：无从核对，但整批都缺该字段时首条就是本剧的，
        // 采信它比直接返回 null（详情页退回第一季）更符合实际
        val repository = repository(NoSeriesIdNextUpApi())

        val hint = (repository.seriesNextUp("series-1") as NovumResult.Success).value

        assertEquals("no-series-id-ep", hint?.id)
        assertEquals("season-1", hint?.seasonId)
    }

    /**
     * 回归：服务端把同一集下发两次（多版本/虚拟集）时，选集列表必须按 id 去重。
     *
     * 不去重会让详情页/抽屉的分集 LazyRow 出现重复 key，实测直接闪退。
     */
    @Test
    fun episodesDedupeRepeatedIds() = runTest(dispatcher) {
        val repository = repository(DuplicateEpisodesApi())

        val episodes = (repository.episodes(seriesId = "series-1", seasonId = "season-1") as NovumResult.Success).value

        assertEquals(1, episodes.size)
        assertEquals("ep-1", episodes.single().id)
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
        val clients = FixedDetailProvider(api)
        return DefaultMediaRepository(
            clients = clients,
            sessionHolder = holder,
            dispatchers = dispatchers,
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
        limit: Int?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(
                Id = "ep-1",
                Name = "初入京都",
                IndexNumber = 1,
                ParentIndexNumber = 1,
                SeriesId = "series-1",
                SeasonId = "season-1",
                SeriesPrimaryImageTag = "poster-tag",
                SeriesThumbImageTag = "thumb-tag",
                RunTimeTicks = 27_000_000_000L,
                People = listOf(
                    PersonDto(Name = "张若昀", Role = "范闲", Type = "Actor", Id = "p1", PrimaryImageTag = "t1"),
                    PersonDto(Name = "李沁", Role = "林婉儿", Type = "Actor", Id = "p2"),
                ),
                UserData = UserDataDto(Played = true),
            ),
            ItemDto(
                Id = "ep-2",
                Name = "夜宴",
                IndexNumber = 2,
                ParentIndexNumber = 1,
                SeriesId = "series-1",
                SeasonId = "season-1",
                SeriesPrimaryImageTag = "poster-tag",
                SeriesThumbImageTag = "thumb-tag",
                RunTimeTicks = 27_000_000_000L,
                People = listOf(
                    PersonDto(Name = "张若昀", Role = "范闲", Type = "Actor", Id = "p1"),
                    PersonDto(Name = "王启年", Role = "客串", Type = "GuestStar", Id = "p4"),
                ),
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

/** NextUp 忽略 SeriesId：首条是别的剧集，第二条才是本剧。 */
private class WrongSeriesNextUpApi : EmbyApiStub() {
    override suspend fun nextUp(
        uid: String,
        limit: Int,
        fields: String,
        seriesId: String?,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(Id = "other-ep", SeriesId = "series-2", ParentIndexNumber = 1, IndexNumber = 1),
            ItemDto(
                Id = "mine-ep",
                SeriesId = "series-1",
                SeasonId = "season-2",
                ParentIndexNumber = 2,
                IndexNumber = 5,
            ),
        ),
    )
}

/** 极简网关：分集响应里没有 SeriesId 字段（无从核对归属）。 */
private class NoSeriesIdNextUpApi : EmbyApiStub() {
    override suspend fun nextUp(
        uid: String,
        limit: Int,
        fields: String,
        seriesId: String?,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(
                Id = "no-series-id-ep",
                SeasonId = "season-1",
                ParentIndexNumber = 1,
                IndexNumber = 2,
            ),
        ),
    )
}

/** NextUp 只返回别的剧集；本剧的进度要从 Resume 回查。 */
private class ResumeFallbackApi : EmbyApiStub() {
    override suspend fun nextUp(
        uid: String,
        limit: Int,
        fields: String,
        seriesId: String?,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(ItemDto(Id = "other-ep", SeriesId = "series-2")),
    )

    override suspend fun resume(
        uid: String,
        limit: Int,
        parentId: String?,
        mediaTypes: String,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(
                Id = "resume-ep",
                SeriesId = "series-1",
                SeasonId = "season-2",
                ParentIndexNumber = 2,
                IndexNumber = 4,
            ),
        ),
    )
}

/** 分集没有 People（服务端只在剧集级挂演职员）。 */
private class NoPeopleApi : EmbyApiStub() {
    override suspend fun episodes(
        seriesId: String,
        userId: String,
        seasonId: String?,
        limit: Int?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(Id = "ep-1", SeriesId = "series-1", SeasonId = "season-1", ParentIndexNumber = 1, IndexNumber = 1),
        ),
    )
}

/** 同一分集重复下发：用于验证选集列表按 id 去重。 */
private class DuplicateEpisodesApi : EmbyApiStub() {
    override suspend fun detail(
        itemId: String,
        userId: String,
        fields: String,
    ): ItemDto = ItemDto(Id = itemId, Name = "人生一串", Type = "Series")

    override suspend fun episodes(
        seriesId: String,
        userId: String,
        seasonId: String?,
        limit: Int?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto(
        Items = listOf(
            ItemDto(
                Id = "ep-1",
                Name = "无肉不欢",
                IndexNumber = 1,
                ParentIndexNumber = 1,
                SeriesId = seriesId,
                SeasonId = seasonId,
            ),
            ItemDto(
                Id = "ep-1",
                Name = "无肉不欢",
                IndexNumber = 1,
                ParentIndexNumber = 1,
                SeriesId = seriesId,
                SeasonId = seasonId,
            ),
        ),
        TotalRecordCount = 2,
    )
}
