package org.filemat.server.module.auth.controller

import com.github.f4b6a3.ulid.Ulid
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.CorsOriginRegistry
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.AuthTokenService
import org.filemat.server.module.auth.service.ContentSessionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AuthControllerContentSessionTest {

    private val authTokenService = mockk<AuthTokenService>()
    private val contentSessionService = ContentSessionService()
    private val controller = AuthController(
        authTokenService,
        mockk<AuthService>(relaxed = true),
        contentSessionService,
    )

    private val token = AuthToken(
        authToken = "session-token-value",
        userId = Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
        createdDate = unixNow(),
        userAgent = "test",
        maxAge = 86_400,
    )

    @Test
    fun `ticket is bound to the SPA origin`() {
        every { authTokenService.getToken(token.authToken) } returns Result.ok(token)

        val mintRequest = spaRequest("https://example.com")
        mintRequest.setCookies(Cookie(Props.Cookies.authToken, token.authToken))
        val ticket = controller.createContentSessionTicketMapping(mintRequest).body!!
        val secondTicket = controller.createContentSessionTicketMapping(mintRequest).body!!

        val mismatch = MockHttpServletRequest()
        mismatch.addHeader("Origin", "https://evil.example")
        mismatch.isSecure = true
        val mismatchResponse = MockHttpServletResponse()
        val mismatchResult = controller.contentSessionMapping(mismatch, mismatchResponse, ticket)
        assertEquals(401, mismatchResult.statusCode.value())

        val match = MockHttpServletRequest()
        match.addHeader("Origin", "https://example.com")
        match.isSecure = true
        val matchResponse = MockHttpServletResponse()
        val matchResult = controller.contentSessionMapping(match, matchResponse, secondTicket)
        assertEquals(200, matchResult.statusCode.value())
        val cookie = matchResponse.getHeader("Set-Cookie")!!
        assertTrue(cookie.contains("${Props.Cookies.authToken}=${token.authToken}"))
        assertTrue(cookie.contains("SameSite=None"))
        assertTrue(cookie.contains("Secure"))
        assertFalse(cookie.contains("Partitioned"))
        assertTrue(cookie.contains("Max-Age="))
    }

    @Test
    fun `content session cookie is not set on HTTP`() {
        every { authTokenService.getToken(token.authToken) } returns Result.ok(token)

        val mintRequest = spaRequest("https://example.com")
        mintRequest.setCookies(Cookie(Props.Cookies.authToken, token.authToken))
        val ticket = controller.createContentSessionTicketMapping(mintRequest).body!!

        val request = MockHttpServletRequest()
        request.addHeader("Origin", "https://example.com")
        request.isSecure = false
        val response = MockHttpServletResponse()
        val result = controller.contentSessionMapping(request, response, ticket)

        assertEquals(400, result.statusCode.value())
        assertNull(response.getHeader("Set-Cookie"))

        val httpsRequest = MockHttpServletRequest()
        httpsRequest.addHeader("Origin", "https://example.com")
        httpsRequest.isSecure = true
        val httpsResponse = MockHttpServletResponse()
        val httpsResult = controller.contentSessionMapping(httpsRequest, httpsResponse, ticket)

        assertEquals(200, httpsResult.statusCode.value())
        val cookie = httpsResponse.getHeader("Set-Cookie")!!
        assertTrue(cookie.contains("SameSite=None"))
        assertTrue(cookie.contains("Secure"))
        assertFalse(cookie.contains("Partitioned"))
    }

    @Test
    fun `successful ticket mint records the SPA origin for CORS`() {
        every { authTokenService.getToken(token.authToken) } returns Result.ok(token)

        val origin = "https://ticket-mint-cors.example"
        val mintRequest = spaRequest(origin)
        mintRequest.setCookies(Cookie(Props.Cookies.authToken, token.authToken))

        val result = controller.createContentSessionTicketMapping(mintRequest)

        assertEquals(200, result.statusCode.value())
        assertTrue(CorsOriginRegistry.isAllowed(origin))
    }

    @Test
    fun `unauthenticated ticket mint does not record Origin`() {
        val origin = "https://unauth-ticket-cors.example"
        val mintRequest = spaRequest(origin)

        val result = controller.createContentSessionTicketMapping(mintRequest)

        assertEquals(401, result.statusCode.value())
        assertFalse(CorsOriginRegistry.isAllowed(origin))
    }

    @Test
    fun `untrusted origin is not enrolled in the CORS allowlist`() {
        every { authTokenService.getToken(token.authToken) } returns Result.ok(token)

        val origin = "https://cors-poison.example"
        val request = MockHttpServletRequest()
        request.addHeader("Origin", origin)
        request.addHeader("Referer", "$origin/from-referer")
        request.setCookies(Cookie(Props.Cookies.authToken, token.authToken))

        val result = controller.createContentSessionTicketMapping(request)

        assertEquals(400, result.statusCode.value())
        assertFalse(CorsOriginRegistry.isAllowed(origin))
    }

    @Test
    fun `existing content cookie refreshes without a ticket`() {
        every { authTokenService.getToken(token.authToken) } returns Result.ok(token)

        val request = MockHttpServletRequest()
        request.addHeader("Origin", "https://example.com")
        request.isSecure = true
        request.setCookies(Cookie(Props.Cookies.authToken, token.authToken))
        val response = MockHttpServletResponse()

        val result = controller.contentSessionMapping(request, response, null)

        assertEquals(200, result.statusCode.value())
        assertTrue(response.getHeader("Set-Cookie")!!.contains("Max-Age="))
    }

    private fun spaRequest(origin: String): MockHttpServletRequest {
        val request = MockHttpServletRequest()
        request.addHeader("Origin", origin)
        val https = origin.startsWith("https://")
        val host = origin.substringAfter("://")
        request.scheme = if (https) "https" else "http"
        request.serverName = host
        request.serverPort = if (https) 443 else 80
        request.isSecure = https
        request.addHeader("Host", host)
        return request
    }
}
