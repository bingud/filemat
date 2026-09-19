package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentSessionServiceTest {

    private val service = ContentSessionService()

    private fun token(): AuthToken = AuthToken(
        authToken = "session-token-value",
        userId = Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
        createdDate = unixNow(),
        userAgent = "test",
        maxAge = 86_400,
    )

    @Test
    fun `ticket is single use`() {
        val minted = service.mintTicket(token(), "https://example.com")
        assertTrue(minted.isSuccessful)

        val first = service.consumeTicket(minted.value)
        val second = service.consumeTicket(minted.value)

        assertTrue(first.isSuccessful)
        assertEquals("session-token-value", first.value.authToken)
        assertEquals("https://example.com", first.value.origin)
        assertTrue(second.rejected)
    }

    @Test
    fun `unknown ticket is rejected`() {
        val result = service.consumeTicket("missing")
        assertTrue(result.rejected)
    }

    @Test
    fun `cookie header is Secure only on HTTPS`() {
        val secure = service.buildSetCookieHeader("abc", 3600, true)
        assertTrue(secure.contains("Secure"))
        assertFalse(secure.contains("Partitioned"))
        assertTrue(secure.contains("SameSite=None"))
        assertTrue(secure.contains("${Props.Cookies.authToken}=abc"))

        val insecure = service.buildSetCookieHeader("abc", 3600, false)
        assertFalse(insecure.contains("Secure"))
        assertFalse(insecure.contains("Partitioned"))
        assertTrue(insecure.contains("SameSite=None"))
    }

    @Test
    fun `cookie max age is capped by the session`() {
        val shortSession = token().copy(maxAge = 30, createdDate = unixNow())
        assertEquals(30, service.cookieMaxAgeSeconds(shortSession))
    }
}
