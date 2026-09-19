package org.filemat.server.module.auth.controller

import com.github.f4b6a3.ulid.Ulid
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.AuthTokenService
import org.filemat.server.module.auth.service.ContentSessionService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.service.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder

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

        val mintRequest = MockHttpServletRequest()
        mintRequest.addHeader("Origin", "https://example.com")
        mintRequest.setCookies(Cookie(Props.Cookies.authToken, token.authToken))
        val ticket = controller.mintContentSessionTicketMapping(mintRequest).body!!
        val secondTicket = controller.mintContentSessionTicketMapping(mintRequest).body!!

        val mismatch = MockHttpServletRequest()
        mismatch.addHeader("Origin", "https://evil.example")
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
    fun `content session cookie is not Secure on HTTP`() {
        every { authTokenService.getToken(token.authToken) } returns Result.ok(token)

        val mintRequest = MockHttpServletRequest()
        mintRequest.addHeader("Origin", "http://example.com")
        mintRequest.setCookies(Cookie(Props.Cookies.authToken, token.authToken))
        val ticket = controller.mintContentSessionTicketMapping(mintRequest).body!!

        val request = MockHttpServletRequest()
        request.addHeader("Origin", "http://example.com")
        request.isSecure = false
        val response = MockHttpServletResponse()
        controller.contentSessionMapping(request, response, ticket)

        val cookie = response.getHeader("Set-Cookie")!!
        assertTrue(cookie.contains("SameSite=None"))
        assertFalse(cookie.contains("Secure"))
        assertFalse(cookie.contains("Partitioned"))
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
}
