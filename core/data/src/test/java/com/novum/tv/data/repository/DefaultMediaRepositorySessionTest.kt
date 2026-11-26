package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.EmbyClientFactory
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.SessionSnapshot
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.session.Session
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings
import com.novum.tv.testing.NoopNovumLogger
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositorySessionTest {
    @Test
    fun currentAccountWritesKeepAllFourOperationsAndTheirConfirmedStates() = runTest {
        val holder = SessionHolder().apply { update(session("first-user", "first-fixture-token")) }
        val api = MutationRecordingApi()
        val repository = repository(holder, fixed(api), StandardTestDispatcher(testScheduler, "media-io"))

        assertEquals(NovumResult.Success(true), repository.setFavorite("item", true))
        assertEquals(NovumResult.Success(false), repository.setFavorite("item", false))
        assertEquals(NovumResult.Success(true), repository.setPlayed("item", true))
        assertEquals(NovumResult.Success(false), repository.setPlayed("item", false))
        assertEquals(
            listOf(
                "favorite:first-user:item",
                "unfavorite:first-user:item",
                "played:first-user:item",
                "unplayed:first-user:item",
            ),
            api.mutations,
        )
    }

    @Test
    fun queuedFavoriteChangesAreNotReassignedToTheNextAccount() = runTest {
        for (favorite in listOf(true, false)) {
            val first = session("first-user", "first-fixture-token")
            val holder = SessionHolder().apply { update(first) }
            val api = MutationRecordingApi()
            val repository = repository(holder, fixed(api), StandardTestDispatcher(testScheduler, "media-io"))
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                repository.setFavorite("first-account-item", favorite)
            }

            holder.update(session("second-user", "second-fixture-token"))

            assertUnauthorized(pending.await())
            assertEquals(emptyList<String>(), api.mutations)
        }
    }

    @Test
    fun queuedPlayedChangesAreNotReassignedToTheNextAccount() = runTest {
        for (played in listOf(true, false)) {
            val holder = SessionHolder().apply { update(session("first-user", "first-fixture-token")) }
            val api = MutationRecordingApi()
            val repository = repository(holder, fixed(api), StandardTestDispatcher(testScheduler, "media-io"))
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                repository.setPlayed("first-account-item", played)
            }

            holder.update(session("second-user", "second-fixture-token"))

            assertUnauthorized(pending.await())
            assertEquals(emptyList<String>(), api.mutations)
        }
    }

    @Test
    fun libraryResponseFromThePreviousAccountIsDiscardedAfterASwitch() = runTest {
        val holder = SessionHolder().apply { update(session("first-user", "first-fixture-token")) }
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val api = object : EmbyApiStub() {
            override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> {
                entered.complete(Unit)
                released.await()
                return QueryResultDto(Items = listOf(ItemDto(Id = "first-library", Name = "First account library")))
            }
        }
        val repository = repository(holder, fixed(api), StandardTestDispatcher(testScheduler, "media-io"))
        val pending = async { repository.libraries() }
        entered.await()

        holder.update(session("second-user", "second-fixture-token"))
        released.complete(Unit)

        assertUnauthorized(pending.await())
    }

    @Test
    fun favoriteRequestNeverPairsThePreviousUsersPathWithTheNextAccountsToken() = runBlocking {
        MediaMutationServer().use { server ->
            val first = session("first-user", "first-fixture-token").copy(server = server.url)
            val next = session("second-user", "second-fixture-token").copy(server = server.url)
            val holder = SessionHolder().apply { update(first) }
            val clients = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
            val switching = object : EmbyApiProvider {
                override fun api(baseUrl: String): EmbyApi {
                    holder.update(next)
                    return clients.api(baseUrl)
                }

                override fun api(baseUrl: String, session: SessionSnapshot): EmbyApi {
                    holder.update(next)
                    return clients.api(baseUrl, session)
                }
            }
            val repository = repository(holder, switching, Dispatchers.IO)

            val result = repository.setFavorite("first-account-item", true)

            val request = server.requests.single()
            assertEquals("/Users/first-user/FavoriteItems/first-account-item", request.path)
            assertEquals("first-fixture-token", request.token)
            assertUnauthorized(result)
        }
    }

    private fun repository(
        holder: SessionHolder,
        clients: EmbyApiProvider,
        dispatcher: CoroutineDispatcher,
    ) = DefaultMediaRepository(
        clients = clients,
        sessionHolder = holder,
        dispatchers = object : DispatcherProvider {
            override val default = dispatcher
            override val io = dispatcher
            override val main = dispatcher
        },
        strings = FakeMediaStrings,
        logger = NoopNovumLogger,
    )

    private fun fixed(api: EmbyApi) = object : EmbyApiProvider {
        override fun api(baseUrl: String): EmbyApi = api
    }

    private fun session(userId: String, token: String) = Session(
        server = "https://media.example",
        token = token,
        userId = userId,
        userName = "Fixture",
        serverName = "Fixture server",
        deviceId = "fixture-device",
    )

    private fun assertUnauthorized(result: NovumResult<*>) {
        assertTrue("A previous account's result must not become current", result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Unauthorized)
    }
}

private class MutationRecordingApi : EmbyApiStub() {
    val mutations = mutableListOf<String>()

    override suspend fun addFavorite(uid: String, itemId: String): UserDataDto {
        mutations += "favorite:$uid:$itemId"
        return UserDataDto(IsFavorite = true)
    }

    override suspend fun removeFavorite(uid: String, itemId: String): UserDataDto {
        mutations += "unfavorite:$uid:$itemId"
        return UserDataDto(IsFavorite = false)
    }

    override suspend fun markPlayed(uid: String, itemId: String): UserDataDto {
        mutations += "played:$uid:$itemId"
        return UserDataDto(Played = true)
    }

    override suspend fun markUnplayed(uid: String, itemId: String): UserDataDto {
        mutations += "unplayed:$uid:$itemId"
        return UserDataDto(Played = false)
    }
}

/** Synthetic credentials and ephemeral loopback only; never calls a configured media server. */
private class MediaMutationServer : AutoCloseable {
    data class Request(val path: String, val token: String?)

    val requests = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                requests += Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("X-Emby-Token"))
                val body = "{\"IsFavorite\":true}".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
            }
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
