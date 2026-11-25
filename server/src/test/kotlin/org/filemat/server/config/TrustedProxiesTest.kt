package org.filemat.server.config

import org.filemat.server.common.State
import org.filemat.server.common.util.isHttps
import org.filemat.server.common.util.realIp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest

class TrustedProxiesTest {

    @AfterEach
    fun reset() = TrustedProxies.configure(null)

    private fun request(remote: String, vararg forwardedFor: String) = MockHttpServletRequest().apply {
        remoteAddr = remote
        forwardedFor.forEach { addHeader("X-Forwarded-For", it) }
    }

    @Test
    fun `without trusted proxies X-Forwarded-For is ignored, so a made-up address gets no new rate limit bucket`() {
        assertEquals("203.0.113.9", request("203.0.113.9", "1.2.3.4").realIp())
        assertEquals("127.0.0.1", request("127.0.0.1", "1.2.3.4").realIp())
    }

    @Test
    fun `behind a trusted proxy the client is the right-most address that isn't a trusted proxy`() {
        TrustedProxies.configure("10.0.0.0/8, 192.168.1.5")

        // The client wrote 1.2.3.4 itself; the proxy appended the address it saw
        assertEquals("198.51.100.7", request("10.0.0.2", "1.2.3.4, 198.51.100.7").realIp())
        // Two trusted proxies
        assertEquals("198.51.100.7", request("10.0.0.2", "1.2.3.4, 198.51.100.7, 192.168.1.5").realIp())
        // Several header lines count in order
        assertEquals("198.51.100.7", request("10.0.0.2", "1.2.3.4", "198.51.100.7").realIp())
        // No header: the proxy itself
        assertEquals("10.0.0.2", request("10.0.0.2").realIp())
        // A connection that isn't from a trusted proxy keeps its own address
        assertEquals("198.51.100.8", request("198.51.100.8", "1.2.3.4").realIp())
    }

    @Test
    fun `X-Forwarded-Proto is only believed from a trusted proxy`() {
        val fromClient = request("198.51.100.8").apply { addHeader("X-Forwarded-Proto", "https") }
        assertFalse(fromClient.isHttps())

        TrustedProxies.configure("10.0.0.1")
        val fromProxy = request("10.0.0.1").apply { addHeader("X-Forwarded-Proto", "https") }
        assertTrue(fromProxy.isHttps())
        assertFalse(fromClient.isHttps())
    }

    @Test
    fun `IPv6 addresses and ranges`() {
        TrustedProxies.configure("fd00::/8,::1")

        assertTrue(TrustedProxies.isTrusted("fd12:3456::1"))
        assertTrue(TrustedProxies.isTrusted("0:0:0:0:0:0:0:1"))
        assertFalse(TrustedProxies.isTrusted("fe80::1"))
        assertFalse(TrustedProxies.isTrusted("10.0.0.1"))
        assertEquals("2001:db8::5", request("fd00::2", "2001:db8::5").realIp())
    }

    @Test
    fun `an IPv4-mapped IPv6 address matches its IPv4 range`() {
        TrustedProxies.configure("172.16.0.0/12")

        assertTrue(TrustedProxies.isTrusted("::ffff:172.17.0.1"))
        assertTrue(TrustedProxies.isTrusted("0:0:0:0:0:ffff:ac11:1"))
    }

    @Test
    fun `prefixes that don't end on a byte`() {
        TrustedProxies.configure("172.16.0.0/12")

        assertTrue(TrustedProxies.isTrusted("172.31.255.255"))
        assertFalse(TrustedProxies.isTrusted("172.32.0.0"))
        assertFalse(TrustedProxies.isTrusted("172.15.255.255"))
    }

    @Test
    fun `an entry that isn't an address or range stops the start`() {
        listOf("proxy.local", "10.0.0.0/33", "300.1.1.1", "10.0.0", "10.0.0.1/", "fd00::/129", "10.0.0.1:8080").forEach {
            assertThrows<IllegalArgumentException>(it) { TrustedProxies.configure(it) }
        }
        assertThrows<IllegalArgumentException> { State(env = mapOf("FM_TRUSTED_PROXIES" to "proxy.local")) }
    }

    @Test
    fun `addresses are parsed without a name lookup`() {
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), TrustedProxies.parseAddress("10.0.0.1"))
        assertNull(TrustedProxies.parseAddress("localhost"))
        assertNull(TrustedProxies.parseAddress("1.2.3.4.example"))
        assertNull(TrustedProxies.parseAddress("01234.1.1.1"))
        assertNull(TrustedProxies.parseAddress(""))
    }

    @Test
    fun `State reads FM_TRUSTED_PROXIES`() {
        State(env = mapOf("FM_TRUSTED_PROXIES" to "10.0.0.1"))
        assertTrue(TrustedProxies.isTrusted("10.0.0.1"))

        State(env = emptyMap())
        assertFalse(TrustedProxies.isTrusted("10.0.0.1"))
    }

    @Test
    fun `a long forwarded entry is cut`() {
        TrustedProxies.configure("10.0.0.1")
        assertEquals(64, request("10.0.0.1", "x".repeat(500)).realIp().length)
    }
}
