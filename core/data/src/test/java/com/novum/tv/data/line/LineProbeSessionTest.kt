package com.novum.tv.data.line

import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.NoopNovumLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineProbeSessionTest {
    @Test
    fun unapprovedHttpLinesAreNeitherProbedNorReturnedForAutomaticSelection() = runTest {
        ProbeServer().use { server ->
            val holder = SessionHolder().apply { update(sessionAt(server.httpsUrl)) }
            val probe = lineProbe(holder, server.httpUrl)

            val lines = probe.lines(pinnedLine = server.httpUrl, pinnedLineCleartextConfirmed = false)

            assertEquals(listOf(server.httpsUrl), lines.map { it.baseUrl })
            assertTrue("The HTTP alternative must not receive a HEAD request", server.requests.isEmpty())
        }
    }

    @Test
    fun pickerCanShowUnapprovedHttpLineWithoutProbingItAndPinnedPermissionRestores() = runTest {
        ProbeServer().use { server ->
            val holder = SessionHolder().apply { update(sessionAt(server.httpsUrl)) }
            val probe = lineProbe(holder, server.httpUrl)

            val pickerLines = probe.lines(includeUnapprovedHttp = true)
            val unapproved = pickerLines.single { it.baseUrl == server.httpUrl }
            assertEquals(Long.MAX_VALUE, unapproved.latencyMs)
            assertTrue(server.requests.isEmpty())

            val accountKey = requireNotNull(holder.playbackPrefsNamespace())
            probe.invalidate()
            val permittedLines = probe.lines(
                pinnedLine = server.httpUrl,
                pinnedLineCleartextConfirmed = true,
            )

            assertEquals(server.httpUrl, permittedLines.first().baseUrl)
            assertTrue("The persisted pinned HTTP origin should be measurable", server.requests.isNotEmpty())
            assertEquals(accountKey, holder.playbackPrefsNamespace())
        }
    }

    @Test
    fun accountsOnTheSameServerDoNotShareDiscoveredLines() = runTest {
        val holder = SessionHolder().apply { update(session("first")) }
        var calls = 0
        val api = object : EmbyApiStub() {
            override suspend fun serverDomains(): JsonElement {
                calls++
                return JsonObject(emptyMap()) // Only the fallback server; no HEAD/network request.
            }
        }
        val probe = LineProbe(provider(api), holder, NoopNovumLogger)
        probe.lines()
        holder.update(session("second"))
        probe.lines()
        assertEquals("A new account must query its own domain list", 2, calls)
    }

    @Test
    fun linesArrivingAfterAnAccountSwitchAreNotPublishedToTheNewAccount() = runTest {
        val holder = SessionHolder().apply { update(session("first")) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = object : EmbyApiStub() {
            override suspend fun serverDomains(): JsonElement {
                entered.complete(Unit)
                release.await()
                return JsonObject(emptyMap())
            }
        }
        val probe = LineProbe(provider(api), holder, NoopNovumLogger)
        val oldRequest = async { probe.lines() }
        entered.await()
        holder.update(session("second"))
        release.complete(Unit)
        assertTrue(oldRequest.await().isEmpty())
    }

    private fun session(user: String) = sessionAt("https://media.example", user)

    private fun sessionAt(server: String, user: String = "user-a") =
        Session(server, "$user-token", user, user, "", "device")

    private fun lineProbe(holder: SessionHolder, httpUrl: String): LineProbe {
        val api = object : EmbyApiStub() {
            override suspend fun serverDomains(): JsonElement = Json.parseToJsonElement(
                """{"ok":true,"data":[{"name":"Untrusted HTTP","url":"$httpUrl"}]}""",
            )
        }
        return LineProbe(provider(api), holder, NoopNovumLogger)
    }

    private fun provider(api: EmbyApi) = object : EmbyApiProvider {
        override fun api(baseUrl: String): EmbyApi = api
    }
}

private class ProbeServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<String>()
    private val requestExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "line-probe-test-server").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        // The HTTPS fallback deliberately shares this HTTP endpoint. A stalled TLS
        // request must not block the independent, authorized HTTP HEAD probe.
        executor = requestExecutor
        createContext("/") { exchange -> respond(exchange) }
        start()
    }
    val httpUrl: String get() = "http://127.0.0.1:${server.address.port}/untrusted"
    val httpsUrl: String get() = httpUrl.replaceFirst("http://", "https://").substringBefore("/untrusted")

    private fun respond(exchange: HttpExchange) {
        try {
            requests += exchange.requestMethod
            exchange.sendResponseHeaders(204, -1L)
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        requestExecutor.shutdownNow()
    }
}
