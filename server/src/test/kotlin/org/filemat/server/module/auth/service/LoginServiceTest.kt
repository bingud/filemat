package org.filemat.server.module.auth.service

import com.atlassian.onetime.model.TOTPSecret
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.TotpUtil
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.service.UserService
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import java.net.URI

/**
 * Tests the login flow: who gets an auth session, what each caller is told, and the rate limits.
 * Rate limiting is keyed by IP, so each test uses its own IP unless it is testing the limit.
 */
class LoginServiceTest {

    private val userService = mockk<UserService>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val authTokenService = mockk<AuthTokenService>()
    private val authService = mockk<AuthService>()
    private val mfaService = mockk<MfaService>()
    private val logService = mockk<LogService>(relaxed = true)

    private val service = LoginService(userService, passwordEncoder, authTokenService, logService, authService, mfaService)

    private val response = MockHttpServletResponse()
    private val ip = "test-ip-${UlidCreator.getUlid()}"
    private val totpSecret = "JBSWY3DPEHPK3PXP"

    private fun user(
        mfaStatus: Boolean = false,
        mfaRequired: Boolean = false,
        banned: Boolean = false,
    ) = User(
        userId = UlidCreator.getUlid(),
        email = "alice@example.com",
        username = "alice",
        password = "stored-hash",
        mfaTotpSecret = if (mfaStatus) totpSecret else null,
        mfaTotpStatus = mfaStatus,
        mfaTotpCodes = null,
        mfaTotpRequired = mfaRequired,
        createdDate = 0,
        lastLoginDate = null,
        isBanned = banned,
        homeFolderPath = null,
    )

    /** Makes [user] the account found for "alice" / "alice@example.com", with "correct-password" as its password. */
    private fun existing(user: User = user()): User {
        every { userService.getUserByUsername("alice", any()) } returns Result.ok(user)
        every { userService.getUserByEmail("alice@example.com", any()) } returns Result.ok(user)
        every { userService.getUserByUserId(user.userId, any()) } returns Result.ok(user)
        every { passwordEncoder.matches("correct-password", "stored-hash") } returns true
        every { passwordEncoder.matches(neq("correct-password"), any()) } returns false
        every { authTokenService.createToken(user.userId, any(), any()) } answers {
            Result.ok(AuthToken("session-${user.userId}", user.userId, 1, "ua", 3600))
        }
        every { authTokenService.createCookie(any(), any()) } answers { Cookie(Props.Cookies.authToken, firstArg()) }
        return user
    }

    private fun login(
        username: String? = "alice",
        password: String? = "correct-password",
        ip: String = this.ip,
        totp: String? = null,
        mfaCodes: List<String>? = null,
    ): ResponseEntity<String> = service.login(
        existingPrincipal = null,
        username = username,
        password = password,
        totp = totp,
        mfaCodes = mfaCodes,
        ip = ip,
        meta = emptyMap(),
        userAgent = "ua",
        now = 1234,
        response = response,
    )

    private fun ResponseEntity<String>.errorCode() = Regex("\"error\":\"([^\"]*)\"").find(body.orEmpty())?.groupValues?.get(1)

    private fun assertNoSessionCreated() {
        verify(exactly = 0) { authTokenService.createToken(any(), any(), any()) }
        assertNull(response.getCookie(Props.Cookies.authToken))
    }

    @Nested
    inner class PasswordLogin {

        @Test
        fun `correct credentials create a session cookie and record the login date`() {
            val user = existing()

            val result = login()

            assertEquals(200, result.statusCode.value())
            assertNotNull(response.getCookie(Props.Cookies.authToken))
            verify(exactly = 1) { userService.setLastLoginDate(user.userId, 1234) }
        }

        @Test
        fun `email address can be used instead of a username`() {
            existing()

            assertEquals(200, login(username = "alice@example.com").statusCode.value())
        }

        @Test
        fun `wrong password is rejected without a session`() {
            existing()

            val result = login(password = "wrong-password")

            assertEquals(401, result.statusCode.value())
            assertNoSessionCreated()
        }

        @Test
        fun `unknown account gets the same answer as a wrong password`() {
            existing()
            every { userService.getUserByUsername("ghost", any()) } returns Result.notFound()

            val unknown = login(username = "ghost", ip = "ip-a-${UlidCreator.getUlid()}")
            val wrongPassword = login(password = "wrong-password", ip = "ip-b-${UlidCreator.getUlid()}")

            assertEquals(wrongPassword.statusCode, unknown.statusCode)
            assertEquals(wrongPassword.body, unknown.body)
        }

        @Test
        fun `banned user is refused even with the correct password`() {
            existing(user(banned = true))

            val result = login()

            assertEquals(400, result.statusCode.value())
            assertEquals("banned", result.errorCode())
            assertNoSessionCreated()
        }

        @Test
        fun `banned status is not revealed when the password is wrong`() {
            existing(user(banned = true))

            assertEquals("incorrect-password", login(password = "wrong-password").errorCode())
        }

        @Test
        fun `database failure while looking up the user is an internal error`() {
            existing()
            every { userService.getUserByUsername("alice", any()) } returns Result.error("db down")

            assertEquals(500, login().statusCode.value())
            assertNoSessionCreated()
        }

        @Test
        fun `failure to save the session is an internal error and sets no cookie`() {
            val user = existing()
            every { authTokenService.createToken(user.userId, any(), any()) } returns Result.error("db down")

            assertEquals(500, login().statusCode.value())
            assertNull(response.getCookie(Props.Cookies.authToken))
        }

        @Test
        fun `invalid input is rejected before any user lookup`() {
            existing()
            val ips = generateSequence { "ip-${UlidCreator.getUlid()}" }.iterator()

            assertEquals("username-invalid", login(username = null, ip = ips.next()).errorCode())
            assertEquals("username-invalid", login(username = "bad name!", ip = ips.next()).errorCode())
            assertEquals("email-invalid", login(username = "not an@email", ip = ips.next()).errorCode())
            assertEquals("password-invalid", login(password = null, ip = ips.next()).errorCode())
            assertEquals("password-invalid", login(password = "abc", ip = ips.next()).errorCode())

            verify(exactly = 0) { userService.getUserByUsername(any(), any()) }
            verify(exactly = 0) { userService.getUserByEmail(any(), any()) }
        }
    }

    @Nested
    inner class RateLimit {

        @Test
        fun `ninth attempt from the same address within the window is refused`() {
            existing()

            val statuses = (1..9).map { login(password = "wrong-password").statusCode.value() }

            assertEquals(List(8) { 401 }, statuses.take(8))
            assertEquals(429, statuses.last())
        }

        @Test
        fun `blocked address is refused even with the correct password`() {
            existing()
            repeat(8) { login(password = "wrong-password") }

            assertEquals(429, login().statusCode.value())
            assertNoSessionCreated()
        }

        @Test
        fun `limit is per address`() {
            existing()
            repeat(9) { login(password = "wrong-password") }

            assertEquals(200, login(ip = "other-ip-${UlidCreator.getUlid()}").statusCode.value())
        }
    }

    @Nested
    inner class TwoFactorLogin {

        @Test
        fun `user with 2FA gets a temporary login cookie instead of a session`() {
            existing(user(mfaStatus = true))

            val result = login()

            assertEquals("mfa-totp", result.body)
            val temp = response.getCookie(Props.Cookies.tempLoginToken)
            assertNotNull(temp)
            assertTrue(temp!!.isHttpOnly && temp.secure)
            assertNoSessionCreated()
        }

        @Test
        fun `correct code exchanges the temporary token for a session exactly once`() {
            val user = existing(user(mfaStatus = true))
            login()
            val loginToken = response.getCookie(Props.Cookies.tempLoginToken)!!.value
            val code = TotpUtil.generator.generateCurrent(TOTPSecret.fromBase32EncodedString(totpSecret)).value

            val first = service.verifyTotpMfa(loginToken, code, "ua", response)
            val replay = service.verifyTotpMfa(loginToken, code, "ua", response)

            assertEquals(200, first.statusCode.value())
            assertNotNull(response.getCookie(Props.Cookies.authToken))
            assertEquals(401, replay.statusCode.value())
            verify(exactly = 1) { authTokenService.createToken(user.userId, any(), any()) }
        }

        @Test
        fun `wrong code creates no session`() {
            existing(user(mfaStatus = true))
            login()
            val loginToken = response.getCookie(Props.Cookies.tempLoginToken)!!.value

            val result = service.verifyTotpMfa(loginToken, "000000", "ua", response)

            assertEquals(400, result.statusCode.value())
            assertEquals("invalid-totp", result.errorCode())
            assertNoSessionCreated()
        }

        @Test
        fun `unknown or expired temporary token is refused`() {
            val result = service.verifyTotpMfa(StringUtils.randomString(128), "123456", "ua", response)

            assertEquals(401, result.statusCode.value())
            assertEquals("login-expired", result.errorCode())
        }

        @Test
        fun `code guessing is rate limited per user`() {
            existing(user(mfaStatus = true))
            login()
            val loginToken = response.getCookie(Props.Cookies.tempLoginToken)!!.value

            val statuses = (1..9).map { service.verifyTotpMfa(loginToken, "000000", "ua", response).statusCode.value() }

            assertEquals(429, statuses.last())
            assertNoSessionCreated()
        }
    }

    @Nested
    inner class EnforcedTwoFactorSetup {

        @Test
        fun `user who must enable 2FA gets setup details and no session`() {
            val user = existing(user(mfaRequired = true))
            every { authService.createPrincipalFromUser(user, any()) } returns Result.ok(testPrincipal(userId = user.userId))
            every { mfaService.enable_generateSecret(any()) } returns MfaService.MfaCredentials(
                secretObject = TOTPSecret.fromBase32EncodedString(totpSecret),
                urlObject = URI("otpauth://totp/filemat"),
                secret = totpSecret,
                url = "otpauth://totp/filemat",
                codes = listOf("backup-1"),
            )

            val result = login()

            assertEquals(403, result.statusCode.value())
            assertEquals("mfa-enforced", result.errorCode())
            assertNoSessionCreated()
        }

        @Test
        fun `confirming the new 2FA code with backup codes logs the user in`() {
            val user = existing(user(mfaRequired = true))
            every { mfaService.enable_confirmSecret(any(), "123456", listOf("backup-1")) } returns Result.ok()

            val result = login(totp = "123456", mfaCodes = listOf("backup-1"))

            assertEquals(200, result.statusCode.value())
            assertNotNull(response.getCookie(Props.Cookies.authToken))
            verify(exactly = 1) { authTokenService.createToken(user.userId, any(), any()) }
        }

        @Test
        fun `rejected confirmation does not log the user in`() {
            existing(user(mfaRequired = true))
            every { mfaService.enable_confirmSecret(any(), any(), any()) } returns Result.reject("2FA code is incorrect.")

            val result = login(totp = "000000", mfaCodes = listOf("backup-1"))

            assertEquals(400, result.statusCode.value())
            assertNoSessionCreated()
        }
    }
}
