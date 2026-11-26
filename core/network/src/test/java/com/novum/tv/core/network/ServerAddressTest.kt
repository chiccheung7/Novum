package com.novum.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器地址规范化测试。
 *
 * 覆盖真实用户可能输入的所有形态，避免登录页因少了协议或端口而误报"连不上"。
 */
class ServerAddressTest {

    @Test
    fun addsHttpSchemeAndDefaultPort() {
        assertEquals(
            listOf("http://192.168.5.26:8096", "https://192.168.5.26:443"),
            ServerAddress.candidates("192.168.5.26"),
        )
    }

    @Test
    fun keepsExplicitSchemeAndPort() {
        assertEquals(
            listOf("http://tv.local:8096"),
            ServerAddress.candidates("http://tv.local:8096"),
        )
    }

    @Test
    fun stripsTrailingSlash() {
        assertEquals(
            listOf("https://demo.example.com:443"),
            ServerAddress.candidates("https://demo.example.com/"),
        )
    }

    @Test
    fun keepsReverseProxySubPath() {
        assertEquals(
            listOf("https://demo.example.com:443/emby"),
            ServerAddress.candidates("https://demo.example.com/emby"),
        )
        assertEquals(
            listOf("http://tv.local:8096/emby", "https://tv.local:443/emby"),
            ServerAddress.candidates("tv.local/emby"),
        )
    }

    @Test
    fun keepsCustomPortForBothSchemes() {
        assertEquals(
            listOf("http://192.168.5.26:9000", "https://192.168.5.26:9000"),
            ServerAddress.candidates("192.168.5.26:9000"),
        )
    }

    @Test
    fun rejectsBlankInput() {
        assertTrue(ServerAddress.candidates("   ").isEmpty())
    }

    @Test
    fun baseUrlAlwaysEndsWithSlash() {
        assertEquals("http://host:8096/", ServerAddress.toBaseUrl("http://host:8096"))
        assertEquals("http://host:8096/", ServerAddress.toBaseUrl("http://host:8096/"))
        assertEquals(
            "https://host:443/proxy/emby/?route=one#section",
            ServerAddress.toBaseUrl("https://host:443/proxy/emby?route=one#section"),
        )
        assertEquals(
            "https://host:443/proxy/emby/?route=one#section",
            ServerAddress.toBaseUrl("https://host:443/proxy/emby/?route=one#section"),
        )
    }

    @Test
    fun resolvesMediaUrlsAgainstReverseProxyBasePath() {
        val server = "https://media.example/emby"
        assertEquals("https://media.example/emby/Videos/one", ServerAddress.resolveUrl(server, "Videos/one"))
        assertEquals("https://media.example/emby/Videos/one", ServerAddress.resolveUrl(server, "/Videos/one"))
        assertEquals("https://media.example/emby/Videos/one", ServerAddress.resolveUrl(server, "/emby/Videos/one"))
        assertEquals("https://media.example/emby/emby2/Videos/one", ServerAddress.resolveUrl(server, "/emby2/Videos/one"))
        assertEquals(
            "https://media.example/proxy/emby/Videos/one?api_key=signed",
            ServerAddress.resolveUrl("https://media.example/proxy/emby", "/Videos/one?api_key=signed"),
        )
        assertEquals(
            "https://media.example/proxy/emby/Videos/one",
            ServerAddress.resolveUrl("https://media.example/proxy/emby", "/proxy/emby/Videos/one"),
        )
        assertEquals("https://cdn.example/Videos/one", ServerAddress.resolveUrl(server, "//cdn.example/Videos/one"))
        assertEquals("https://cdn.example/Videos/one", ServerAddress.resolveUrl(server, "https://cdn.example/Videos/one"))
    }

    @Test
    fun explicitSchemeDecidesCleartext() {
        assertTrue(ServerAddress.usesCleartext("http://demo.example.com"))
        assertFalse(ServerAddress.usesCleartext("https://demo.example.com"))
        assertFalse(ServerAddress.usesCleartext("HTTPS://demo.example.com"))
    }

    @Test
    fun privateHostWithoutSchemeIsCleartext() {
        // 家庭 Emby 的典型输入：裸 IP，必然走明文 HTTP
        assertTrue(ServerAddress.usesCleartext("192.168.5.26"))
        assertTrue(ServerAddress.usesCleartext("10.0.0.8:8096"))
        assertTrue(ServerAddress.usesCleartext("172.16.0.2"))
        assertTrue(ServerAddress.usesCleartext("localhost:8096"))
    }

    @Test
    fun portAloneDoesNotGuaranteeHttps() {
        assertTrue(ServerAddress.usesCleartext("demo.example.com:8096"))
        // 未指定协议时，443 端口仍有 HTTP 候选，端口号不能保证传输加密。
        assertTrue(ServerAddress.usesCleartext("demo.example.com:443"))
        assertFalse(ServerAddress.usesCleartext("https://demo.example.com:443"))
    }

    @Test
    fun bareDomainRequiresCleartextConfirmation() {
        assertTrue(ServerAddress.usesCleartext("demo.example.com"))
        assertTrue(ServerAddress.usesCleartext("demo.example.com/emby"))
        assertTrue(ServerAddress.usesCleartext("203.0.113.8"))
        assertFalse(ServerAddress.usesCleartext(""))
        assertFalse(ServerAddress.usesCleartext("   "))
    }
}
