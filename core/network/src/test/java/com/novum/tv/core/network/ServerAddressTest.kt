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
    fun explicitPortIsCleartextUnlessHttpsPort() {
        assertTrue(ServerAddress.usesCleartext("demo.example.com:8096"))
        // 443 是 HTTPS 默认端口，按加密处理
        assertFalse(ServerAddress.usesCleartext("demo.example.com:443"))
    }

    @Test
    fun bareDomainIsNotReportedAsCleartext() {
        // 裸域名会先试 HTTP 再兜底 HTTPS，避免每次登录都弹确认框
        assertFalse(ServerAddress.usesCleartext("demo.example.com"))
        assertFalse(ServerAddress.usesCleartext(""))
    }
}
