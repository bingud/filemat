package org.filemat.server.config.filter

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.filemat.server.config.CorsOriginRegistry
import org.filemat.server.config.Props
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CorsFilterTest {

    private val filter = CorsFilter()

    @Test
    fun `unknown origin is forbidden`() {
        val request = MockHttpServletRequest("POST", "/api/v1/file/delete-list")
        request.addHeader("Origin", "https://evil.example")
        request.serverName = "203.0.113.10"
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(response.getHeader("Access-Control-Allow-Origin"))
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `allowlisted origin receives CORS headers on the content host`() {
        val origin = "https://cors-filter-allow.example"
        CorsOriginRegistry.bind("cors-filter-token", UlidCreator.getUlid(), origin)

        val request = MockHttpServletRequest("GET", "/api/v1/file/content")
        request.addHeader("Origin", origin)
        request.serverName = "203.0.113.10"
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, chain)

        assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `content cookie from another session is forbidden`() {
        val victimOrigin = "https://victim-spa.example"
        val attackerOrigin = "https://attacker-spa.example"
        CorsOriginRegistry.bind("victim-token", UlidCreator.getUlid(), victimOrigin)
        CorsOriginRegistry.bind("attacker-token", UlidCreator.getUlid(), attackerOrigin)

        val request = MockHttpServletRequest("GET", "/api/v1/file/content")
        request.addHeader("Origin", attackerOrigin)
        request.serverName = "203.0.113.10"
        request.setCookies(Cookie(Props.Cookies.authToken, "victim-token"))
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(response.getHeader("Access-Control-Allow-Origin"))
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `content cookie is allowed only for its own origin`() {
        val origin = "https://own-spa.example"
        CorsOriginRegistry.bind("own-token", UlidCreator.getUlid(), origin)

        val request = MockHttpServletRequest("GET", "/api/v1/file/content")
        request.addHeader("Origin", origin)
        request.serverName = "203.0.113.10"
        request.setCookies(Cookie(Props.Cookies.authToken, "own-token"))
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, chain)

        assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `OPTIONS from an unknown origin is forbidden`() {
        val request = MockHttpServletRequest("OPTIONS", "/api/v1/state/select")
        request.addHeader("Origin", "https://unknown-cors.example")
        request.serverName = "203.0.113.10"
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `same-origin request is not blocked`() {
        val origin = "https://spa-cors-remember.example"
        val appRequest = MockHttpServletRequest("POST", "/api/v1/state/select")
        appRequest.addHeader("Origin", origin)
        appRequest.scheme = "https"
        appRequest.serverName = "spa-cors-remember.example"
        appRequest.serverPort = 443
        appRequest.addHeader("Host", "spa-cors-remember.example")
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(appRequest, response, chain)

        assertNull(response.getHeader("Access-Control-Allow-Origin"))
        verify { chain.doFilter(any(), any()) }
    }
}
