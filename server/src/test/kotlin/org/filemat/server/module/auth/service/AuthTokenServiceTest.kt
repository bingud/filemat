package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.repository.AuthTokenRepository
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AuthTokenServiceTest {

    private val repository = mockk<AuthTokenRepository>(relaxed = true)
    private val service = AuthTokenService(mockk<LogService>(relaxed = true), repository)
    private val userId = UlidCreator.getUlid()

    @Nested
    inner class CreateToken {

        @Test
        fun `stores a long random token for the user`() {
            val result = service.createToken(userId, "Firefox", UserAction.LOGIN)

            assertSuccess(result)
            val token = result.value
            assertEquals(128, token.authToken.length)
            assertEquals(userId, token.userId)
            verify(exactly = 1) { repository.insertToken(token.authToken, userId.toString(), token.createdDate, "Firefox", token.maxAge) }
        }

        @Test
        fun `every login gets a different token`() {
            val tokens = (1..20).map { service.createToken(userId, null, null).value.authToken }

            assertEquals(20, tokens.toSet().size)
        }

        @Test
        fun `token lasts one year`() {
            assertEquals(365L * 24 * 3600 + 6 * 3600, service.createToken(userId, null, null).value.maxAge)
        }

        @Test
        fun `database failure is an error and yields no token`() {
            every { repository.insertToken(any(), any(), any(), any(), any()) } throws RuntimeException("locked")

            assertError(service.createToken(userId, null, null))
        }
    }

    @Nested
    inner class GetToken {

        @Test
        fun `valid token is returned`() {
            val stored = AuthToken("abc", userId, 1, "ua", 100)
            every { repository.getToken("abc", any()) } returns stored

            assertEquals(stored, service.getToken("abc").value)
        }

        @Test
        fun `unknown or expired token is not found`() {
            every { repository.getToken(any(), any()) } returns null

            assertNotFound(service.getToken("missing"))
        }

        @Test
        fun `database failure is an error, not a missing token`() {
            every { repository.getToken(any(), any()) } throws RuntimeException("locked")

            assertError(service.getToken("abc"))
        }
    }

    @Nested
    inner class Removal {

        @Test
        fun `removing all sessions of a user`() {
            assertSuccess(service.removeTokensByUserId(userId))

            verify(exactly = 1) { repository.removeTokensByUserId(userId.toString()) }
            verify(exactly = 0) { repository.removeTokensByUserIdWithExclusion(any(), any()) }
        }

        @Test
        fun `removing other sessions keeps the excluded one`() {
            assertSuccess(service.removeTokensByUserId(userId, excludedToken = "keep"))

            verify(exactly = 1) { repository.removeTokensByUserIdWithExclusion(userId.toString(), "keep") }
            verify(exactly = 0) { repository.removeTokensByUserId(any()) }
        }

        @Test
        fun `database failure on removal is an error`() {
            every { repository.removeTokensByUserId(any()) } throws RuntimeException("locked")

            assertError(service.removeTokensByUserId(userId))
        }

        @Test
        fun `logging out deletes the token`() {
            assertSuccess(service.deleteToken("abc", UserAction.LOGOUT_USER_SESSIONS))

            verify(exactly = 1) { repository.deleteToken("abc") }
        }

        @Test
        fun `database failure on logout is an error`() {
            every { repository.deleteToken(any()) } throws RuntimeException("locked")

            assertError(service.deleteToken("abc", UserAction.LOGOUT_USER_SESSIONS))
        }
    }

    @Test
    fun `auth cookie cannot be read by scripts or sent over plain http`() {
        val cookie = service.createCookie("abc", 3600)

        assertEquals(Props.Cookies.authToken, cookie.name)
        assertEquals("abc", cookie.value)
        assertTrue(cookie.secure)
        assertTrue(cookie.isHttpOnly)
        assertEquals("/", cookie.path)
        assertEquals(3600, cookie.maxAge)
        assertEquals("Lax", cookie.getAttribute("SameSite"))
    }
}
