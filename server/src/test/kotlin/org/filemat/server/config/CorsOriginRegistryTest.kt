package org.filemat.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class CorsOriginRegistryTest {

    @Test
    fun `unknown origin is not allowed until remembered`() {
        val origin = "https://cors-allowlist-test.example"
        assertFalse(CorsOriginRegistry.isAllowed(origin))
        CorsOriginRegistry.remember(origin)
        assertTrue(CorsOriginRegistry.isAllowed(origin))
        assertTrue(CorsOriginRegistry.isAllowed("$origin/"))
        assertFalse(CorsOriginRegistry.isAllowed("https://evil.example"))
    }

    @Test
    fun `same origin includes scheme host and port`() {
        val request = httpsRequest("example.com", 5000)

        assertTrue(CorsOriginRegistry.isSameOrigin(request, "https://example.com:5000"))
        assertFalse(CorsOriginRegistry.isSameOrigin(request, "https://example.com:3000"))
        assertFalse(CorsOriginRegistry.isSameOrigin(request, "http://example.com:5000"))
        assertFalse(CorsOriginRegistry.isSameOrigin(request, "https://evil.example:5000"))
    }

    @Test
    fun `default https port matches an Origin without a port`() {
        val request = httpsRequest("example.com", 443)
        assertTrue(CorsOriginRegistry.isSameOrigin(request, "https://example.com"))
        assertFalse(CorsOriginRegistry.isSameOrigin(request, "https://example.com:8443"))
    }

    @Test
    fun `canonical origin omits default ports`() {
        assertEquals("https://example.com", CorsOriginRegistry.canonicalOrigin("https://example.com:443"))
        assertEquals("http://example.com", CorsOriginRegistry.canonicalOrigin("http://example.com:80"))
        assertEquals("https://example.com:8443", CorsOriginRegistry.canonicalOrigin("https://example.com:8443"))
    }

    private fun httpsRequest(host: String, port: Int): MockHttpServletRequest {
        val request = MockHttpServletRequest()
        request.scheme = "https"
        request.serverName = host
        request.serverPort = port
        request.isSecure = true
        val hostHeader = if (port == 443) host else "$host:$port"
        request.addHeader("Host", hostHeader)
        return request
    }
}
