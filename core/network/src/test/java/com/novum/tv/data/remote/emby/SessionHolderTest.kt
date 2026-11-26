package com.novum.tv.data.remote.emby

import com.novum.tv.data.session.Session
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHolderTest {
    @Test
    fun snapshotAndTaggedRequestDescriptionsNeverExposeTheToken() {
        val token = "fixture-account-secret"
        val snapshot = holder("https://media.example", token).capture()
        val request = Request.Builder()
            .url("https://media.example/Items/one")
            .tag(SessionSnapshot::class.java, snapshot)
            .build()

        assertEquals("SessionSnapshot(redacted)", snapshot.toString())
        assertFalse(snapshot.toString().contains(token))
        assertFalse(request.toString().contains(token))
    }

    @Test
    fun capturedSessionRetainsItsWholeIdentityAfterAReplacement() {
        val first = session("https://first.example", "first-fixture-token").copy(
            deviceId = "first-fixture-device",
            userName = "First fixture",
        )
        val holder = SessionHolder().apply { update(first) }
        val captured = holder.capture()
        val capturedNamespace = captured.playbackPrefsNamespace()
        assertSame(captured, holder.capture())

        holder.update(session("https://second.example", "second-fixture-token"))

        assertNotSame(captured, holder.capture())
        assertEquals(first.server, captured.server)
        assertEquals(first.token, captured.token)
        assertEquals(first.userId, captured.userId)
        assertEquals(first.userName, captured.userName)
        assertEquals(first.deviceId, captured.deviceId)
        assertEquals("first-fixture-token", captured.tokenForUrl("https://first.example/Items/one"))
        assertNull(captured.tokenForUrl("https://second.example/Items/one"))
        assertTrue(captured.authorizationHeader().contains("DeviceId=\"first-fixture-device\""))
        assertEquals(capturedNamespace, captured.playbackPrefsNamespace())
        assertNotEquals(capturedNamespace, holder.playbackPrefsNamespace())
    }

    @Test
    fun reapplyingAnIdenticalSessionStillReplacesItsIdentity() {
        val session = session("https://media.example", "fixture-token")
        val holder = SessionHolder().apply { update(session) }
        val first = holder.capture()

        holder.update(session)

        assertNotSame(first, holder.capture())
        assertEquals(first, holder.capture())
    }

    @Test
    fun displayNameFollowsTheCurrentSessionAndClearsOnLogout() {
        val holder = SessionHolder()
        assertEquals("", holder.userName)

        holder.update(session("https://first.example", "first-token").copy(userName = "First"))
        assertEquals("First", holder.userName)
        holder.update(session("https://second.example", "second-token").copy(userName = "Second"))
        assertEquals("Second", holder.userName)
        holder.update(null)
        assertEquals("", holder.userName)
    }

    @Test
    fun sharesTokenOnlyWithinTheCurrentCanonicalOrigin() {
        val holder = holder("https://Media.Example:443/emby", "fixture-token")

        assertEquals("fixture-token", holder.tokenForUrl("https://media.example/emby/Items/one"))
        assertEquals("fixture-token", holder.tokenForUrl("https://media.example:443/Items/two"))
        assertNull(holder.tokenForUrl("http://media.example/Items/one"))
        assertNull(holder.tokenForUrl("https://media.example:8443/Items/one"))
        assertNull(holder.tokenForUrl("https://media.example.other/Items/one"))
        assertNull(holder.tokenForUrl("https://media.example@other.example/Items/one"))
    }

    @Test
    fun switchingSessionsNeverProvidesTheNewTokenToTheOldServer() {
        val holder = holder("https://old.example", "old-fixture-token")
        holder.update(session("https://new.example", "new-fixture-token"))

        assertNull(holder.tokenForUrl("https://old.example/Items/one"))
        assertEquals("new-fixture-token", holder.tokenForUrl("https://new.example/Items/one"))
        holder.update(null)
        assertNull(holder.tokenForUrl("https://new.example/Items/one"))
    }

    @Test
    fun invalidRequestOrSessionUrlsFailClosed() {
        val holder = holder("https://media.example", "fixture-token")
        listOf("", "/Items/one", "not a URL", "https://media.example:invalid", "file:///Items/one").forEach { url ->
            assertNull("Unexpected token for $url", holder.tokenForUrl(url))
        }
        holder.update(session("invalid-server", "fixture-token"))
        assertNull(holder.tokenForUrl("https://media.example/Items/one"))
    }

    @Test
    fun clientDescriptionDoesNotEmbedAccountSecrets() {
        val holder = holder("https://media.example", "fixture-account-secret")

        assertEquals(false, holder.authorizationHeader().contains("fixture-account-secret"))
    }

    @Test
    fun preferenceNamespaceIsCanonicalAndSurvivesTokenRotation() {
        val holder = holder(
            "https://fixture-login:fixture-password@Media.Example:443/emby/?api_key=fixture-query#fragment",
            "old-fixture-token",
        )
        val namespace = holder.playbackPrefsNamespace()
        holder.update(session("https://media.example/emby", "new-fixture-token"))

        assertEquals(namespace, holder.playbackPrefsNamespace())
        assertTrue(namespace?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    @Test
    fun preferenceNamespaceSeparatesServersUsersAndReverseProxyPaths() {
        val original = session("https://media.example/emby", "fixture-token")
        val holder = SessionHolder().apply { update(original) }
        val namespace = holder.playbackPrefsNamespace()

        listOf(
            original.copy(server = "https://other.example/emby"),
            original.copy(server = "http://media.example/emby"),
            original.copy(server = "https://media.example:8443/emby"),
            original.copy(server = "https://media.example/other"),
            original.copy(server = "https://media.example/Emby"),
            original.copy(userId = "other-fixture-user"),
        ).forEach { different ->
            holder.update(different)
            assertNotEquals(namespace, holder.playbackPrefsNamespace())
        }
    }

    @Test
    fun preferenceNamespaceRequiresAValidActiveSession() {
        val holder = SessionHolder()
        assertNull(holder.playbackPrefsNamespace())
        listOf(
            session("invalid-server", "fixture-token"),
            session("https://media.example", ""),
            session("https://media.example", "fixture-token").copy(userId = ""),
        ).forEach { incomplete ->
            holder.update(incomplete)
            assertNull(holder.playbackPrefsNamespace())
        }
        holder.update(session("https://media.example", "fixture-token"))
        holder.update(null)
        assertNull(holder.playbackPrefsNamespace())
    }

    private fun holder(server: String, token: String) = SessionHolder().apply { update(session(server, token)) }

    private fun session(server: String, token: String) = Session(
        server = server,
        token = token,
        userId = "fixture-user",
        userName = "Fixture",
        serverName = "Fixture server",
        deviceId = "fixture-device",
    )
}
