package com.novum.tv.ui.screen.login

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginServerInputTest {
    @Test
    fun startsWithHttpsAndPort443WithoutAnAddress() {
        val input = LoginServerInput()

        assertEquals("https", input.protocol)
        assertEquals("", input.address)
        assertEquals("443", input.port)
        assertEquals("", input.url)
    }

    @Test
    fun bareHostUsesHttpsAndItsDefaultPort() {
        val input = LoginServerInput().withAddress("media.example")

        assertEquals(LoginServerInput(address = "media.example"), input)
        assertEquals("https://media.example:443", input.url)
    }

    @Test
    fun fullHttpUrlSelectsHttpAndPort8080() {
        val input = LoginServerInput().withAddress("http://tv.local")

        assertEquals(LoginServerInput("http", "tv.local", "8080"), input)
        assertEquals("http://tv.local:8080", input.url)
    }

    @Test
    fun fullHttpsUrlWithoutPortReplacesAnExistingCustomPort() {
        val input = LoginServerInput("http", "old.local", "9000")
            .withAddress("HTTPS://media.example/emby")

        assertEquals(LoginServerInput("https", "media.example/emby", "443"), input)
        assertEquals("https://media.example:443/emby", input.url)
    }

    @Test
    fun fullUrlKeepsExplicitPortProxyPathQueryAndFragment() {
        val input = LoginServerInput().withAddress(" http://tv.local:8096/proxy/emby?name=one%20two#section ")

        assertEquals(LoginServerInput("http", "tv.local/proxy/emby?name=one%20two#section", "8096"), input)
        assertEquals("http://tv.local:8096/proxy/emby?name=one%20two#section", input.url)
    }

    @Test
    fun bareHostAndPortAreSplitWithoutChangingProtocol() {
        val input = LoginServerInput().withAddress("192.168.1.10:9000/emby")

        assertEquals(LoginServerInput("https", "192.168.1.10/emby", "9000"), input)
        assertEquals("https://192.168.1.10:9000/emby", input.url)
    }

    @Test
    fun ordinaryAddressEditsKeepTheSeparatelyChosenPort() {
        val input = LoginServerInput("http", "first.local", "9000").withAddress("second.local/emby")

        assertEquals(LoginServerInput("http", "second.local/emby", "9000"), input)
    }

    @Test
    fun bracketedIpv6WithAnExplicitPortKeepsTheProxySuffix() {
        val input = LoginServerInput().withAddress("https://[2001:db8::1]:8920/emby?x=y")

        assertEquals(LoginServerInput("https", "[2001:db8::1]/emby?x=y", "8920"), input)
        assertEquals("https://[2001:db8::1]:8920/emby?x=y", input.url)
    }

    @Test
    fun bareIpv6IsBracketedWithoutMistakingTheLastGroupForAPort() {
        val input = LoginServerInput().withAddress("2001:db8::1")

        assertEquals("[2001:db8::1]", input.address)
        assertEquals("443", input.port)
        assertEquals("https://[2001:db8::1]:443", input.url)
    }

    @Test
    fun queryWithoutAPathGetsACanonicalRootSeparator() {
        val input = LoginServerInput().withAddress("https://media.example?name=one#two")

        assertEquals("media.example?name=one#two", input.address)
        assertEquals("https://media.example:443/?name=one#two", input.url)
    }

    @Test
    fun malformedExplicitPortsArePreservedForFieldValidation() {
        assertEquals("", LoginServerInput().withAddress("http://tv.local:").port)
        assertEquals("wrong", LoginServerInput().withAddress("tv.local:wrong").port)
        assertEquals("70000", LoginServerInput().withAddress("tv.local:70000").port)
    }

    @Test
    fun clearingAddressKeepsTheOtherTwoFields() {
        val input = LoginServerInput("http", "tv.local", "9000").withAddress("")

        assertEquals(LoginServerInput("http", "", "9000"), input)
        assertEquals("", input.url)
    }
}
