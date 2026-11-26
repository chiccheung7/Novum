package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.remote.emby.UserDataUpdateDto
import com.novum.tv.data.session.Session
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings
import com.novum.tv.testing.NoopNovumLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonPlaybackProgressTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun resettingPlayedStatePersistsZeroPositionAndPreservesOtherUserData() = runTest(dispatcher) {
        for (played in listOf(true, false)) {
            val original = UserDataDto(
                PlaybackPositionTicks = 2_700_000_000L,
                Played = played,
                IsFavorite = true,
                PlayCount = 4,
                PlayedPercentage = 35.0,
                Rating = 8.5,
                LastPlayedDate = "2026-11-26T12:00:00Z",
            )
            var posted: UserDataUpdateDto? = null
            val calls = mutableListOf<String>()
            val api = object : EmbyApiStub() {
                override suspend fun markPlayed(uid: String, itemId: String): UserDataDto {
                    calls += "played:$uid:$itemId"
                    return original
                }
                override suspend fun markUnplayed(uid: String, itemId: String): UserDataDto {
                    calls += "unplayed:$uid:$itemId"
                    return original
                }
                override suspend fun updateUserData(uid: String, itemId: String, body: UserDataUpdateDto) {
                    calls += "reset:$uid:$itemId"
                    posted = body
                }
            }

            val result = repository(api).setPlayedAndResetProgress("episode", played)

            assertEquals(played, (result as NovumResult.Success).value)
            assertEquals(UserDataUpdateDto(
                PlaybackPositionTicks = 0L,
                Played = played,
                PlayedPercentage = 0.0,
                IsFavorite = true,
                PlayCount = 4,
                Rating = 8.5,
                LastPlayedDate = original.LastPlayedDate,
            ), posted)
            assertEquals(listOf("${if (played) "played" else "unplayed"}:user:episode", "reset:user:episode"), calls)
        }
    }

    @Test
    fun progressResetFailureIsReportedInsteadOfPretendingTheBatchSucceeded() = runTest(dispatcher) {
        val api = object : EmbyApiStub() {
            override suspend fun markPlayed(uid: String, itemId: String) = UserDataDto(Played = true)
            override suspend fun updateUserData(uid: String, itemId: String, body: UserDataUpdateDto) {
                error("server rejected reset")
            }
        }

        assertTrue(repository(api).setPlayedAndResetProgress("episode", true) is NovumResult.Failure)
    }

    @Test
    fun staleServerProgressIsIgnoredForWatchedEpisodesButKeptForUnfinishedOnes() {
        val dto = ItemDto(Id = "episode", UserData = UserDataDto(Played = true, PlaybackPositionTicks = 5_400_000_000L))

        assertEquals(0L, dto.toEpisode("https://fixture.example", 0, FakeMediaStrings)?.progressMs)
        assertEquals(540_000L, dto.copy(UserData = dto.UserData?.copy(Played = false))
            .toEpisode("https://fixture.example", 0, FakeMediaStrings)?.progressMs)
    }

    private fun repository(api: EmbyApi): DefaultMediaRepository = DefaultMediaRepository(
        clients = object : EmbyApiProvider {
            override fun api(baseUrl: String): EmbyApi = api
        },
        sessionHolder = SessionHolder().apply {
            update(Session("https://fixture.example", "fixture-token", "user", "Fixture", "Fixture", "device"))
        },
        dispatchers = dispatchers,
        strings = FakeMediaStrings,
        logger = NoopNovumLogger,
    )
}
