package com.novum.tv.data.remote.danmu

import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.SessionSnapshot
import com.novum.tv.data.session.Session
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmuClientTest {
    @Test
    fun capturedPrefixKeepsItsServerAndTokenAfterSessionSwitch() {
        val holder = SessionHolder().apply { update(session("https://first.example/emby/", "first-token")) }
        val client = DanmuClient(Json, holder)
        val captured = holder.capture()

        holder.update(session("https://second.example", "second-token"))

        assertEquals("https://first.example/emby/api/v1/danmu/first-token/api/v2", client.danmuBaseUrl(captured))
        assertEquals("https://second.example/api/v1/danmu/second-token/api/v2", client.baseUrl())
        holder.update(null)
        assertNull(client.baseUrl())
    }

    @Test
    fun concurrentSwitchingNeverCombinesAnOriginWithTheOtherToken() {
        val first = session("https://first.example", "first-token")
        val second = session("https://second.example", "second-token")
        val holder = SessionHolder().apply { update(first) }
        val client = DanmuClient(Json, holder)
        val expected = setOf(
            "https://first.example/api/v1/danmu/first-token/api/v2",
            "https://second.example/api/v1/danmu/second-token/api/v2",
        )
        val running = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val switcher = thread(name = "fixture-session-switch") {
            started.countDown()
            while (running.get()) { holder.update(second); holder.update(first) }
        }
        try {
            started.await()
            repeat(10_000) { assertTrue("Mixed session prefix", client.baseUrl() in expected) }
        } finally {
            running.set(false)
            switcher.join()
        }
    }

    @Test
    fun tokensAreOpaquePathSegmentsAndMissingOrInvalidIdentitiesFailClosed() {
        val client = DanmuClient(Json, SessionHolder())
        assertEquals(
            "https://media.example/api/v1/danmu/fixture%2Ftoken%3F%23/api/v2",
            client.danmuBaseUrl(SessionSnapshot(server = "https://media.example", token = "fixture/token?#")),
        )
        assertNull(client.danmuBaseUrl(SessionSnapshot(server = "https://media.example")))
        assertNull(client.danmuBaseUrl(SessionSnapshot(token = "fixture-token")))
        assertNull(client.danmuBaseUrl(SessionSnapshot(server = "not a URL", token = "fixture-token")))
    }
}

private fun session(server: String, token: String) = Session(
    server = server,
    token = token,
    userId = "fixture-user",
    userName = "Fixture",
    serverName = "Fixture",
    deviceId = "fixture-device",
)
