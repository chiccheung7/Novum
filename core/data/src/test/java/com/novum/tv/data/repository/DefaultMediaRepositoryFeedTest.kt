package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.session.Session
import com.novum.tv.model.HomeFeed
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings
import com.novum.tv.testing.NoopNovumLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全库追新是 Hero 的唯一来源；内容行排序、续播与失败隔离保持各自契约。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryFeedTest {
    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()
    private val originalSeedProvider = _heroSeedProvider
    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @After
    fun restoreHeroSeedProvider() {
        _heroSeedProvider = originalSeedProvider
    }

    @Test
    fun decoratedMixedSourceMovesFirstAndKeepsOtherRowsAndResumeInTheirOriginalOrder() = runTest(dispatcher) {
        val feed = feed(FeedApi())

        // 名称允许装饰 emoji，混合库不必具有 movies/tvshows CollectionType。
        assertEquals(listOf(SOURCE_LIBRARY_ID, MOVIE_LIBRARY_ID, TV_LIBRARY_ID), feed.latest.map { it.libraryId })
        assertEquals(listOf("近期热播", "电影库", "剧集库"), feed.latest.map { it.libraryName })
        assertTrue(feed.libraries.any { it.id == SOURCE_LIBRARY_ID && it.kind == null })
        assertFalse(feed.libraries.any { it.id == MUSIC_LIBRARY_ID })
        assertEquals(listOf("source-one", "source-two"), feed.latest.first().items.map { it.id })

        val resume = feed.resume!!.items.single()
        assertEquals("ep-9", resume.id)
        assertEquals("续播剧集", resume.title)
        assertEquals("S01E09", resume.subtitle)
        assertEquals(0.5f, resume.progress!!, 0.001f)
        assertFalse(resume.isNextUp)
        assertNull(feed.nextUp)
    }

    @Test
    fun heroRandomlySamplesOnlyTheSourcesLatestEighteenWithoutGlobalRankingRequests() = runTest(dispatcher) {
        val source = (1..25).map { candidate("source-$it") }
        val api = FeedApi(sourceItems = source)
        val feed = feed(api)
        val availableIds = source.take(18).map { it.Id }.toSet()

        assertEquals(HERO_LIMIT, feed.hero.size)
        assertTrue(feed.hero.all { it.id in availableIds })
        assertEquals(18, feed.latest.first().items.size)
        assertEquals(18, api.latestCalls.single { it.parentId == SOURCE_LIBRARY_ID }.limit)
        assertEquals(
            setOf(MOVIE_LIBRARY_ID, SOURCE_LIBRARY_ID, TV_LIBRARY_ID, EMPTY_LIBRARY_ID),
            api.latestCalls.map { it.parentId }.toSet(),
        )
        assertEquals(api.latestCalls.size, api.latestCalls.map { it.parentId }.distinct().size)
        val enrichment = api.itemsCalls.single()
        assertEquals(feed.hero.map { it.id }.toSet(), enrichment.ids!!.split(',').toSet())
        assertEquals(HERO_LIMIT, enrichment.limit)
        assertNull(enrichment.includeItemTypes)
        assertNull(enrichment.minPremiereDate)
        assertNull(enrichment.minDateCreated)
    }

    @Test
    fun heroDoesNotRankOrFilterTheSourceByRatingReleaseDateOrOtherLibraries() = runTest(dispatcher) {
        val source = listOf(
            candidate("old-low-rated-movie").copy(
                PremiereDate = "1988-01-01T00:00:00.000Z",
                ProductionYear = 1988,
                CommunityRating = 1f,
            ),
            candidate("old-series").copy(
                Type = "Series",
                DateCreated = "1988-01-01T00:00:00.000Z",
                DateLastMediaAdded = "1988-01-01T00:00:00.000Z",
            ),
        )
        val api = FeedApi(sourceItems = source)

        val feed = feed(api)

        assertEquals(source.map { it.Id }.toSet(), feed.hero.map { it.id }.toSet())
        assertEquals("剧集", feed.hero.single { it.id == "old-series" }.subtitle)
        assertTrue(api.itemsCalls.all { it.ids != null })
        assertTrue(feed.hero.none { it.id.startsWith("other-") })
    }

    @Test
    fun heroRotationIsDeterministicPerSeed() = runTest(dispatcher) {
        val repository = repository(FeedApi(sourceItems = (1..18).map { candidate("source-$it") }))
        repository.heroSeedProvider = { 7L }

        val first = (repository.feed() as NovumResult.Success).value.hero.map { it.id }
        val second = (repository.feed() as NovumResult.Success).value.hero.map { it.id }

        assertEquals(HERO_LIMIT, first.size)
        assertEquals(first, second)
    }

    @Test
    fun heroRotationChangesAcrossSeeds() = runTest(dispatcher) {
        val repository = repository(FeedApi(sourceItems = (1..18).map { candidate("source-$it") }))
        repository.heroSeedProvider = { 1L }
        val first = (repository.feed() as NovumResult.Success).value.hero.map { it.id }
        repository.heroSeedProvider = { 2L }
        val second = (repository.feed() as NovumResult.Success).value.hero.map { it.id }

        assertTrue(first != second)
        assertEquals(HERO_LIMIT, first.distinct().size)
        assertEquals(HERO_LIMIT, second.distinct().size)
    }

    @Test
    fun heroFiltersMissingIdsBackgroundsAndResumeEntriesBeforeSampling() = runTest(dispatcher) {
        val api = FeedApi(
            sourceItems = listOf(
                candidate("displayable"),
                candidate("no-background").copy(BackdropImageTags = emptyList()),
                candidate("blank-first-background").copy(BackdropImageTags = listOf(" ", "valid-later-tag")),
                candidate(null),
                candidate(""),
                candidate(" "),
                resumeEpisode(),
            ),
        )

        val feed = feed(api)

        assertEquals(listOf("displayable"), feed.hero.map { it.id })
        assertEquals("displayable", api.itemsCalls.single().ids)
        assertEquals(listOf("ep-9"), feed.resume!!.items.map { it.id })
    }

    @Test
    fun heroDeduplicatesTheSourceAndKeepsTheMoreCompleteDto() = runTest(dispatcher) {
        val api = FeedApi(
            sourceItems = listOf(
                candidate("same-item", "Sparse title"),
                candidate("same-item", "Complete title").copy(
                    CommunityRating = 8.5f,
                    OfficialRating = "PG-13",
                    Overview = "Complete overview",
                    ProductionYear = 2000,
                ),
            ),
        )

        val hero = feed(api).hero.single()

        assertEquals("same-item", hero.id)
        assertEquals("Complete title", hero.title)
        assertEquals(8.5f, hero.rating!!, 0.001f)
        assertEquals("Complete overview", hero.overview)
        assertEquals("same-item", api.itemsCalls.single().ids)
    }

    @Test
    fun heroRefetchesTheSelectedIdsWithFullMetadata() = runTest(dispatcher) {
        val api = FeedApi(
            sourceItems = listOf(candidate("source-one")),
            enrichedItems = listOf(
                candidate("source-one").copy(
                    OfficialRating = "PG-13",
                    Genres = listOf("动作", "科幻"),
                    MediaStreams = listOf(
                        MediaStreamDto(Type = "Video", Codec = "hevc", Height = 2160, VideoRange = "HDR"),
                        MediaStreamDto(Type = "Audio", Codec = "eac3"),
                    ),
                ),
            ),
        )

        val hero = feed(api).hero.single()

        assertEquals("PG-13", hero.officialRating)
        assertEquals(listOf("动作", "科幻"), hero.genres)
        assertEquals("HEVC", hero.videoCodec)
        assertEquals("4K", hero.resolution)
        assertEquals("EAC3", hero.audioFormat)
        assertEquals(listOf("HDR10"), hero.videoFormats)
        val request = api.itemsCalls.single()
        assertEquals("source-one", request.ids)
        assertEquals(1, request.limit)
        assertTrue(request.fields.split(',').containsAll(listOf("MediaStreams", "OfficialRating")))
    }

    @Test
    fun enrichmentCannotInjectOtherLibraryItemsOrRemoveTheSourceBackground() = runTest(dispatcher) {
        val api = FeedApi(
            sourceItems = listOf(candidate("source-one")),
            enrichedItems = listOf(
                candidate("other-injected"),
                candidate("source-one", "Enriched title").copy(
                    OfficialRating = "PG-13",
                    BackdropImageTags = emptyList(),
                ),
            ),
        )

        val hero = feed(api).hero.single()

        assertEquals("source-one", hero.id)
        assertEquals("Enriched title", hero.title)
        assertEquals("PG-13", hero.officialRating)
        assertTrue(hero.backdropUrl.orEmpty().contains("source-one"))
        assertTrue(hero.backdropUrl.orEmpty().contains("tag=source-backdrop"))
    }

    @Test
    fun enrichmentFailureKeepsSourceDtosAndOtherRows() = runTest(dispatcher) {
        val api = FeedApi(sourceItems = listOf(candidate("source-one", "Source title")), failEnrichment = true)

        val feed = feed(api)

        assertEquals("Source title", feed.hero.single().title)
        assertEquals(listOf(SOURCE_LIBRARY_ID, MOVIE_LIBRARY_ID, TV_LIBRARY_ID), feed.latest.map { it.libraryId })
        assertEquals("ep-9", feed.resume!!.items.single().id)
    }

    @Test
    fun missingSourceLeavesHeroEmptyWithoutBorrowingOtherLibraries() = runTest(dispatcher) {
        val api = FeedApi(libraryItems = defaultLibraries().filterNot { it.Id == SOURCE_LIBRARY_ID })

        val feed = feed(api)

        assertTrue(feed.hero.isEmpty())
        assertEquals(listOf(MOVIE_LIBRARY_ID, TV_LIBRARY_ID), feed.latest.map { it.libraryId })
        assertTrue(api.itemsCalls.isEmpty())
        assertFalse(api.latestCalls.any { it.parentId == SOURCE_LIBRARY_ID })
    }

    @Test
    fun failedSourceLeavesHeroEmptyAndDoesNotHideOtherRowsOrResume() = runTest(dispatcher) {
        val api = FeedApi(failedLatest = setOf(SOURCE_LIBRARY_ID))

        val feed = feed(api)

        assertTrue(feed.hero.isEmpty())
        assertEquals(listOf(MOVIE_LIBRARY_ID, TV_LIBRARY_ID), feed.latest.map { it.libraryId })
        assertEquals("ep-9", feed.resume!!.items.single().id)
        assertTrue(api.itemsCalls.isEmpty())
    }

    @Test
    fun unsuitableSourceLeavesHeroEmptyButKeepsItsContentRow() = runTest(dispatcher) {
        val api = FeedApi(sourceItems = listOf(candidate("no-background").copy(BackdropImageTags = emptyList())))

        val feed = feed(api)

        assertTrue(feed.hero.isEmpty())
        assertEquals(SOURCE_LIBRARY_ID, feed.latest.first().libraryId)
        assertEquals("近期热播", feed.latest.first().libraryName)
        assertTrue(api.itemsCalls.isEmpty())
    }

    @Test
    fun emptySourceRowIsHiddenWithoutFallingBackToOtherLibraryHeroCandidates() = runTest(dispatcher) {
        val api = FeedApi(sourceItems = emptyList())

        val feed = feed(api)

        assertTrue(feed.hero.isEmpty())
        assertEquals(listOf(MOVIE_LIBRARY_ID, TV_LIBRARY_ID), feed.latest.map { it.libraryId })
        assertTrue(api.itemsCalls.isEmpty())
    }

    @Test
    fun anotherLibraryFailureDoesNotHideTheSourceRowOrHero() = runTest(dispatcher) {
        val feed = feed(FeedApi(failedLatest = setOf(MOVIE_LIBRARY_ID)))

        assertEquals(setOf("source-one", "source-two"), feed.hero.map { it.id }.toSet())
        assertEquals(listOf(SOURCE_LIBRARY_ID, TV_LIBRARY_ID), feed.latest.map { it.libraryId })
    }

    @Test
    fun emptyServerProducesEmptyFeed() = runTest(dispatcher) {
        val feed = feed(FeedApi(libraryItems = emptyList(), resumeItems = emptyList()))

        assertTrue(feed.isEmpty)
        assertNull(feed.resume)
        assertNull(feed.nextUp)
    }

    @Test
    fun resumeRowAbsorbsNextUpEntriesWithoutDuplicatesOrFinishedEntries() = runTest(dispatcher) {
        val api = FeedApi(
            nextUpItems = listOf(
                resumeEpisode(),
                resumeEpisode().copy(Id = "ep-10", IndexNumber = 10),
                episode("ep-8-1", "series-8", 1),
                episode("ep-8-2", "series-8", 2),
                episode("finished", "series-finished", 1).copy(UserData = UserDataDto(Played = true)),
                episode("complete", "series-complete", 1).copy(UserData = UserDataDto(PlayedPercentage = 100.0)),
            ),
        )

        val feed = feed(api)

        val resume = feed.resume!!
        assertEquals(listOf("ep-9", "ep-8-1"), resume.items.map { it.id })
        assertEquals(listOf(false, true), resume.items.map { it.isNextUp })
        assertNull(feed.nextUp)
    }

    @Test
    fun resumeRowKeepsOnlyTheMostRecentEpisodeOfEachSeries() = runTest(dispatcher) {
        val api = FeedApi(
            resumeItems = listOf(
                episode("recent-episode", "series-one", 2),
                episode("older-episode", "series-one", 1),
                candidate("movie-one"),
                episode("other-episode", "series-two", 1),
            ),
        )

        val feed = feed(api)

        assertEquals(
            listOf("recent-episode", "movie-one", "other-episode"),
            feed.resume!!.items.map { it.id },
        )
    }

    @Test
    fun feedFromThePreviousAccountIsDiscardedWhenSourceLatestFinishesAfterASwitch() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val holder = activeHolder()
        val api = FeedApi(beforeLatest = { libraryId ->
            if (libraryId == SOURCE_LIBRARY_ID) {
                entered.complete(Unit)
                released.await()
            }
        })
        val repository = repository(api, holder)
        val pending = async { repository.feed() }
        entered.await()

        holder.update(fixtureSession().copy(userId = "other-user", token = "other-fixture-token"))
        released.complete(Unit)

        val result = pending.await()
        assertTrue(result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Unauthorized)
    }

    private suspend fun feed(api: EmbyApi): HomeFeed =
        (repository(api).feed() as NovumResult.Success).value

    private fun repository(api: EmbyApi, holder: SessionHolder = activeHolder()): DefaultMediaRepository {
        val clients = object : EmbyApiProvider {
            override fun api(baseUrl: String): EmbyApi = api
        }
        return DefaultMediaRepository(
            clients = clients,
            sessionHolder = holder,
            dispatchers = dispatchers,
            strings = FakeMediaStrings,
            logger = NoopNovumLogger,
        ).apply { heroSeedProvider = { 42L } }
    }
}

private const val SOURCE_LIBRARY_ID = "source-library"
private const val MOVIE_LIBRARY_ID = "movie-library"
private const val TV_LIBRARY_ID = "tv-library"
private const val EMPTY_LIBRARY_ID = "empty-library"
private const val MUSIC_LIBRARY_ID = "music-library"

private fun defaultLibraries(): List<ItemDto> = listOf(
    ItemDto(Id = MOVIE_LIBRARY_ID, Name = "电影库", CollectionType = "movies"),
    ItemDto(Id = SOURCE_LIBRARY_ID, Name = "🔥 全库追新 🎬", CollectionType = null),
    ItemDto(Id = TV_LIBRARY_ID, Name = "剧集库", CollectionType = "tvshows"),
    ItemDto(Id = EMPTY_LIBRARY_ID, Name = "空库", CollectionType = "movies"),
    ItemDto(Id = MUSIC_LIBRARY_ID, Name = "音乐库", CollectionType = "music"),
)

private fun candidate(id: String?, title: String = id.orEmpty()): ItemDto = ItemDto(
    Id = id,
    Name = title,
    Type = "Movie",
    BackdropImageTags = listOf("source-backdrop"),
)

private fun episode(id: String, seriesId: String, index: Int): ItemDto = ItemDto(
    Id = id,
    Name = "第 $index 集",
    Type = "Episode",
    SeriesId = seriesId,
    SeriesName = "续播剧集",
    ParentIndexNumber = 1,
    IndexNumber = index,
    RunTimeTicks = 1_000L,
    BackdropImageTags = listOf("episode-backdrop"),
)

private fun resumeEpisode(): ItemDto =
    episode("ep-9", "series-9", 9).copy(UserData = UserDataDto(PlaybackPositionTicks = 500L))

private fun fixtureSession() = Session(
    server = "https://media.example",
    token = "fixture-token",
    userId = "fixture-user",
    userName = "Fixture",
    serverName = "Fixture server",
    deviceId = "fixture-device",
)

private fun activeHolder(): SessionHolder = SessionHolder().apply { update(fixtureSession()) }

/** In-memory fixtures record queries so obsolete cross-library ranking cannot hide behind fallback. */
private class FeedApi(
    private val libraryItems: List<ItemDto> = defaultLibraries(),
    private val sourceItems: List<ItemDto> = listOf(
        candidate("source-one"),
        candidate("source-two").copy(Type = "Series"),
    ),
    private val resumeItems: List<ItemDto> = listOf(resumeEpisode()),
    private val nextUpItems: List<ItemDto> = emptyList(),
    private val failedLatest: Set<String> = emptySet(),
    private val enrichedItems: List<ItemDto> = emptyList(),
    private val failEnrichment: Boolean = false,
    private val beforeLatest: suspend (String) -> Unit = {},
) : EmbyApiStub() {
    data class LatestCall(val parentId: String, val limit: Int)
    data class ItemsCall(
        val ids: String?,
        val limit: Int,
        val includeItemTypes: String?,
        val minPremiereDate: String?,
        val minDateCreated: String?,
        val fields: String,
    )

    val latestCalls = mutableListOf<LatestCall>()
    val itemsCalls = mutableListOf<ItemsCall>()

    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto(Items = libraryItems, TotalRecordCount = libraryItems.size)

    override suspend fun resume(uid: String, limit: Int, parentId: String?, mediaTypes: String, fields: String) =
        QueryResultDto(Items = resumeItems, TotalRecordCount = resumeItems.size)

    override suspend fun nextUp(uid: String, limit: Int, fields: String, seriesId: String?) =
        QueryResultDto(Items = nextUpItems, TotalRecordCount = nextUpItems.size)

    override suspend fun latest(uid: String, parentId: String, limit: Int, fields: String): List<ItemDto> {
        latestCalls += LatestCall(parentId, limit)
        beforeLatest(parentId)
        if (parentId in failedLatest) error("Fixture library request failed")
        return when (parentId) {
            SOURCE_LIBRARY_ID -> sourceItems
            MOVIE_LIBRARY_ID -> listOf(candidate("other-movie").copy(CommunityRating = 10f))
            TV_LIBRARY_ID -> listOf(candidate("other-series").copy(Type = "Series"))
            else -> emptyList()
        }.take(limit)
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
    ): QueryResultDto<ItemDto> {
        itemsCalls += ItemsCall(ids, limit, includeItemTypes, minPremiereDate, minDateCreated, fields)
        if (failEnrichment) error("Fixture enrichment failed")
        return QueryResultDto(Items = enrichedItems, TotalRecordCount = enrichedItems.size)
    }
}
