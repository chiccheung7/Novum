package com.novum.tv.data.remote.emby

import com.novum.tv.data.session.Session
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real HTTP requests are restricted to ephemeral loopback servers with synthetic credentials. */
class EmbyClientFactoryTest {
    @Test
    fun cachedClientDoesNotSendTheNextSessionsTokenToItsOldServer() = runBlocking {
        RecordingServer().use { oldServer ->
            RecordingServer().use { newServer ->
                val holder = SessionHolder().apply { update(session(oldServer.url, "old-fixture-token")) }
                val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
                val cached = factory.api(oldServer.url)
                cached.publicInfo()

                holder.update(session(newServer.url, "new-fixture-token"))
                cached.publicInfo()
                factory.api(newServer.url).publicInfo()

                assertEquals("old-fixture-token", oldServer.requests[0].token)
                assertNull(oldServer.requests[1].token)
                assertEquals("new-fixture-token", newServer.requests.single().token)
            }
        }
    }

    @Test
    fun boundApiKeepsItsCapturedAccountEvenWhenCallsStartAfterASwitch() = runBlocking {
        RecordingServer().use { server ->
            val holder = SessionHolder().apply {
                update(session(server.url, "first-fixture-token").copy(deviceId = "first-fixture-device"))
            }
            val captured = holder.capture()
            val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
            holder.update(session(server.url, "second-fixture-token").copy(deviceId = "second-fixture-device"))
            val bound = factory.api(server.url, captured)

            bound.publicInfo()
            factory.api(server.url).publicInfo()
            bound.publicInfo()

            assertEquals(
                listOf("first-fixture-token", "second-fixture-token", "first-fixture-token"),
                server.requests.map { it.token },
            )
            assertTrue(server.requests[0].clientDescription.orEmpty().contains("DeviceId=\"first-fixture-device\""))
            assertTrue(server.requests[1].clientDescription.orEmpty().contains("DeviceId=\"second-fixture-device\""))
        }
    }

    @Test
    fun boundApiStillDropsAuthenticationWhenRedirectedToTheNewSessionsOrigin() = runBlocking {
        RecordingServer().use { destination ->
            RecordingServer(redirectTo = "${destination.url}/System/Info/Public").use { origin ->
                val holder = SessionHolder().apply { update(session(origin.url, "first-fixture-token")) }
                val captured = holder.capture()
                val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
                val bound = factory.api(origin.url, captured)
                holder.update(session(destination.url, "second-fixture-token"))

                assertEquals("Fixture", bound.publicInfo().ServerName)

                assertEquals("first-fixture-token", origin.requests.single().token)
                assertNull(destination.requests.single().token)
            }
        }
    }

    @Test
    fun redirectToAnotherOriginDropsTokenButKeepsTheClientDescription() = runBlocking {
        RecordingServer().use { destination ->
            RecordingServer(redirectTo = "${destination.url}/System/Info/Public").use { origin ->
                val holder = SessionHolder().apply { update(session(origin.url, "fixture-account-secret")) }
                val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)

                assertEquals("Fixture", factory.api(origin.url).publicInfo().ServerName)

                assertEquals("fixture-account-secret", origin.requests.single().token)
                val redirected = destination.requests.single()
                assertNull(redirected.token)
                assertNull(redirected.authorization)
                assertTrue(redirected.clientDescription.orEmpty().contains("Client=\"Novum\""))
                assertFalse(redirected.clientDescription.orEmpty().contains("fixture-account-secret"))
            }
        }
    }

    @Test
    fun sameOriginRedirectKeepsTheStartingAccountUntilTheNextCall() = runBlocking {
        val gate = RedirectGate()
        RecordingServer(
            redirectTo = "/redirected",
            redirectOnlyPath = "/System/Info/Public",
            beforeRedirect = gate::pause,
        ).use { server ->
            val holder = SessionHolder().apply { update(session(server.url, "first-fixture-token")) }
            val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
            val cached = factory.api(server.url)
            val pending = async(Dispatchers.IO) { cached.publicInfo() }
            try {
                assertTrue("First request did not reach the loopback gate", gate.awaitRequest())
                holder.update(session(server.url, "second-fixture-token"))
            } finally {
                gate.proceed()
            }

            assertEquals("Fixture", pending.await().ServerName)
            cached.publicInfo()

            assertEquals(
                listOf(
                    "first-fixture-token",
                    "first-fixture-token",
                    "second-fixture-token",
                    "second-fixture-token",
                ),
                server.requests.map { it.token },
            )
        }
    }

    @Test
    fun redirectBackToStartingOriginKeepsTheStartingAccountAfterASwitch() = runBlocking {
        val gate = RedirectGate()
        RecordingServer(
            redirectOnlyPath = "/System/Info/Public",
            beforeRedirect = gate::pause,
        ).use { origin ->
            RecordingServer(redirectTo = "${origin.url}/redirected").use { intermediate ->
                origin.redirectTo = "${intermediate.url}/redirected"
                val holder = SessionHolder().apply { update(session(origin.url, "first-fixture-token")) }
                val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
                val pending = async(Dispatchers.IO) { factory.api(origin.url).publicInfo() }
                try {
                    assertTrue("First request did not reach the loopback gate", gate.awaitRequest())
                    holder.update(session(origin.url, "second-fixture-token"))
                } finally {
                    gate.proceed()
                }

                assertEquals("Fixture", pending.await().ServerName)
                assertEquals(listOf("first-fixture-token", "first-fixture-token"), origin.requests.map { it.token })
                assertNull(intermediate.requests.single().token)
            }
        }
    }

    @Test
    fun redirectToNewSessionOriginDoesNotAcquireItsTokenMidCall() = runBlocking {
        val gate = RedirectGate()
        RecordingServer().use { destination ->
            RecordingServer(
                redirectTo = "${destination.url}/System/Info/Public",
                beforeRedirect = gate::pause,
            ).use { origin ->
                val holder = SessionHolder().apply { update(session(origin.url, "first-fixture-token")) }
                val factory = EmbyClientFactory(Json { ignoreUnknownKeys = true }, holder)
                val pending = async(Dispatchers.IO) { factory.api(origin.url).publicInfo() }
                try {
                    assertTrue("First request did not reach the loopback gate", gate.awaitRequest())
                    holder.update(session(destination.url, "second-fixture-token"))
                } finally {
                    gate.proceed()
                }

                assertEquals("Fixture", pending.await().ServerName)
                assertEquals("first-fixture-token", origin.requests.single().token)
                assertNull(destination.requests.single().token)
            }
        }
    }

    @Test
    fun imageAuthenticationKeepsTheStartingAccountUntilTheNextCall() = runBlocking {
        val gate = RedirectGate()
        RecordingServer(
            redirectTo = "/redirected",
            redirectOnlyPath = "/image",
            beforeRedirect = gate::pause,
        ).use { server ->
            val holder = SessionHolder().apply { update(session(server.url, "first-fixture-token")) }
            val client = OkHttpClient.Builder().addEmbySessionAuthentication(holder).build()
            val request = Request.Builder()
                .url("${server.url}/image")
                .header("X-Emby-Token", "untrusted-fixture-token")
                .build()
            val pending = async(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code }
            }
            try {
                assertTrue("First image did not reach the loopback gate", gate.awaitRequest())
                holder.update(session(server.url, "second-fixture-token"))
            } finally {
                gate.proceed()
            }

            assertEquals(200, pending.await())
            client.newCall(request).execute().use { assertEquals(200, it.code) }

            assertEquals(
                listOf(
                    "first-fixture-token",
                    "first-fixture-token",
                    "second-fixture-token",
                    "second-fixture-token",
                ),
                server.requests.map { it.token },
            )
            assertTrue(server.requests.all { it.clientDescription == null })
        }
    }

    @Test
    fun imageRedirectToNewSessionOriginDoesNotAcquireItsTokenMidCall() = runBlocking {
        val gate = RedirectGate()
        RecordingServer().use { destination ->
            RecordingServer(
                redirectTo = "${destination.url}/image",
                beforeRedirect = gate::pause,
            ).use { origin ->
                val holder = SessionHolder().apply { update(session(origin.url, "first-fixture-token")) }
                val client = OkHttpClient.Builder().addEmbySessionAuthentication(holder).build()
                val request = Request.Builder().url("${origin.url}/image").build()
                val pending = async(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
                try {
                    assertTrue("First image did not reach the loopback gate", gate.awaitRequest())
                    holder.update(session(destination.url, "second-fixture-token"))
                } finally {
                    gate.proceed()
                }

                assertEquals(200, pending.await())
                assertEquals("first-fixture-token", origin.requests.single().token)
                assertNull(destination.requests.single().token)
            }
        }
    }

    @Test
    fun foreignInitialImageCannotAcquireATokenByRedirectingToTheCurrentServer() {
        RecordingServer().use { destination ->
            RecordingServer(redirectTo = "${destination.url}/image").use { origin ->
                val holder = SessionHolder().apply { update(session(destination.url, "fixture-account-secret")) }
                val client = OkHttpClient.Builder().addEmbySessionAuthentication(holder).build()
                val request = Request.Builder()
                    .url("${origin.url}/image")
                    .header("X-Emby-Token", "untrusted-fixture-token")
                    .build()

                client.newCall(request).execute().use { assertEquals(200, it.code) }

                assertNull(origin.requests.single().token)
                assertNull(destination.requests.single().token)
            }
        }
    }

    private fun session(server: String, token: String) = Session(
        server = server,
        token = token,
        userId = "fixture-user",
        userName = "Fixture",
        serverName = "Fixture server",
        deviceId = "fixture-device",
    )
}

private class RecordingServer(
    @Volatile var redirectTo: String? = null,
    private val redirectOnlyPath: String? = null,
    private val beforeRedirect: () -> Unit = {},
) : AutoCloseable {
    data class Request(val token: String?, val authorization: String?, val clientDescription: String?)

    val requests = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> respond(exchange) }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private fun respond(exchange: HttpExchange) {
        try {
            requests += Request(
                token = exchange.requestHeaders.getFirst("X-Emby-Token"),
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                clientDescription = exchange.requestHeaders.getFirst("X-Emby-Authorization"),
            )
            val redirect = redirectTo
            if (redirect != null && (redirectOnlyPath == null || exchange.requestURI.path == redirectOnlyPath)) {
                beforeRedirect()
                exchange.responseHeaders.add("Location", redirect)
                exchange.sendResponseHeaders(302, -1L)
            } else {
                val response = "{\"ServerName\":\"Fixture\"}".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        } finally {
            exchange.close()
        }
    }

    override fun close() = server.stop(0)
}

private class RedirectGate {
    private val requested = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun awaitRequest(): Boolean = requested.await(10, TimeUnit.SECONDS)

    fun pause() {
        requested.countDown()
        check(released.await(10, TimeUnit.SECONDS)) { "Loopback redirect was not released" }
    }

    fun proceed() = released.countDown()
}
