package com.novum.tv.data.line

import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.NoopNovumLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineProbeSessionTest {
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

    private fun session(user: String) = Session("https://media.example", "$user-token", user, user, "", "device")

    private fun provider(api: EmbyApi) = object : EmbyApiProvider {
        override fun api(baseUrl: String): EmbyApi = api
    }
}
