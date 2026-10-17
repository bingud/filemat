package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.model.UserRole
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.service.UserService
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests how a token or user ID becomes a [org.filemat.server.module.auth.model.Principal], what is cached in memory,
 * and that logging out really drops the cached sessions. Every request is authenticated through this class.
 */
class AuthServiceTest {

    private val authTokenService = mockk<AuthTokenService>()
    private val userRoleService = mockk<UserRoleService>()
    private val userService = mockk<UserService>()
    private val logService = mockk<LogService>(relaxed = true)

    private val service = AuthService(authTokenService, userRoleService, userService, logService)

    private val roleA = UlidCreator.getUlid()
    private val roleB = UlidCreator.getUlid()

    private fun user(id: Ulid = UlidCreator.getUlid(), banned: Boolean = false) = User(
        userId = id,
        email = "alice@example.com",
        username = "alice",
        password = "hash",
        mfaTotpSecret = null,
        mfaTotpStatus = true,
        mfaTotpCodes = null,
        mfaTotpRequired = true,
        createdDate = 0,
        lastLoginDate = null,
        isBanned = banned,
        homeFolderPath = "/home/alice",
    )

    private fun token(value: String, userId: Ulid, createdDate: Long = unixNow(), maxAge: Long = 3600) =
        AuthToken(value, userId, createdDate, "ua", maxAge)

    /** Makes [user] loadable by ID and holding [roleA] and [roleB]. */
    private fun existing(user: User = user()): User {
        every { userService.getUserByUserId(user.userId, any()) } returns Result.ok(user)
        every { userRoleService.getRolesByUserId(user.userId) } returns
            Result.ok(listOf(roleA, roleB).map { UserRole(it, user.userId, 0) })
        return user
    }

    /** Makes the database return a valid auth token [value] for [user]. */
    private fun sessionOf(user: User, value: String = "token-${UlidCreator.getUlid()}"): String {
        every { authTokenService.getToken(value) } returns Result.ok(token(value, user.userId))
        return value
    }

    @Nested
    inner class CreatingAPrincipal {

        @Test
        fun `copies identity, flags and home folder from the user`() {
            val user = existing()

            val principal = service.createPrincipalFromUser(user, cacheInMemory = false).also { assertSuccess(it) }.value

            assertEquals(user.userId, principal.userId)
            assertEquals("alice", principal.username)
            assertEquals("alice@example.com", principal.email)
            assertTrue(principal.mfaTotpStatus)
            assertTrue(principal.mfaTotpRequired)
            assertEquals("/home/alice", principal.homeFolderPath)
        }

        @Test
        fun `carries the banned flag`() {
            val user = existing(user(banned = true))

            assertTrue(service.createPrincipalFromUser(user, false).value.isBanned)
        }

        @Test
        fun `holds the ids of the user's roles`() {
            val user = existing()

            assertEquals(listOf(roleA, roleB), service.createPrincipalFromUser(user, false).value.roles)
        }

        @Test
        fun `failure to load the roles is an error and nothing is cached`() {
            val user = existing()
            every { userRoleService.getRolesByUserId(user.userId) } returns Result.error("db down")

            assertError(service.createPrincipalFromUser(user, true))

            assertError(service.getPrincipalByUserId(user.userId, true))
        }
    }

    @Nested
    inner class PrincipalByUserId {

        @Test
        fun `unknown user is not found`() {
            val id = UlidCreator.getUlid()
            every { userService.getUserByUserId(id, any()) } returns Result.notFound()

            assertNotFound(service.getPrincipalByUserId(id, true))
        }

        @Test
        fun `database failure is an error`() {
            val id = UlidCreator.getUlid()
            every { userService.getUserByUserId(id, any()) } returns Result.error("db down")

            assertError(service.getPrincipalByUserId(id, true))
        }

        @Test
        fun `cached principal is served from memory`() {
            val user = existing()

            val first = service.getPrincipalByUserId(user.userId, cacheInMemory = true).value
            val second = service.getPrincipalByUserId(user.userId, cacheInMemory = true).value

            assertSame(first, second)
            verify(exactly = 1) { userService.getUserByUserId(user.userId, any()) }
        }

        @Test
        fun `principal is reloaded every time when caching is off`() {
            val user = existing()

            service.getPrincipalByUserId(user.userId, cacheInMemory = false)
            service.getPrincipalByUserId(user.userId, cacheInMemory = false)

            verify(exactly = 2) { userService.getUserByUserId(user.userId, any()) }
        }

        @Test
        fun `removing a principal from memory forces a reload from the database`() {
            val user = existing()
            service.getPrincipalByUserId(user.userId, true)

            service.removePrincipalFromMemory(user.userId)
            service.getPrincipalByUserId(user.userId, true)

            verify(exactly = 2) { userService.getUserByUserId(user.userId, any()) }
        }
    }

    @Nested
    inner class PrincipalByToken {

        @Test
        fun `valid token resolves to the owner`() {
            val user = existing()
            val token = sessionOf(user)

            val result = service.getPrincipalByToken(token, cacheInMemory = true)

            assertSuccess(result)
            assertEquals(user.userId, result.value.userId)
        }

        @Test
        fun `unknown token is not found`() {
            every { authTokenService.getToken("nope") } returns Result.notFound()

            assertNotFound(service.getPrincipalByToken("nope", true))
        }

        @Test
        fun `database failure while loading the token is an error`() {
            every { authTokenService.getToken("t") } returns Result.error("db down")

            assertError(service.getPrincipalByToken("t", true))
        }

        @Test
        fun `token whose user no longer exists is not found`() {
            val id = UlidCreator.getUlid()
            every { authTokenService.getToken("t") } returns Result.ok(token("t", id))
            every { userService.getUserByUserId(id, any()) } returns Result.notFound()

            assertNotFound(service.getPrincipalByToken("t", true))
        }

        @Test
        fun `token is remembered so the database is asked once`() {
            val user = existing()
            val token = sessionOf(user)

            service.getPrincipalByToken(token, true)
            service.getPrincipalByToken(token, true)

            verify(exactly = 1) { authTokenService.getToken(token) }
        }

        @Test
        fun `token remembered in memory stops working once it expires`() {
            val user = existing()
            // The database layer hands out a token that has already run out.
            every { authTokenService.getToken("old") } returns Result.ok(token("old", user.userId, createdDate = unixNow() - 100, maxAge = 50))
            service.getPrincipalByToken("old", true)

            every { authTokenService.getToken("old") } returns Result.notFound()

            assertNotFound(service.getPrincipalByToken("old", true))
        }

        @Test
        fun `tokens are checked against their own user`() {
            val alice = existing()
            val bob = existing(user().copy(username = "bob"))
            val aliceToken = sessionOf(alice)
            val bobToken = sessionOf(bob)

            assertEquals(alice.userId, service.getPrincipalByToken(aliceToken, true).value.userId)
            assertEquals(bob.userId, service.getPrincipalByToken(bobToken, true).value.userId)
        }
    }

    @Nested
    inner class ChangingCachedPrincipals {

        @Test
        fun `updatePrincipal replaces the cached principal`() {
            val user = existing()
            val original = service.getPrincipalByUserId(user.userId, true).value

            service.updatePrincipal(user.userId) { it.copy(isBanned = true) }

            val updated = service.getPrincipalByUserId(user.userId, true).value
            assertTrue(updated.isBanned)
            assertFalse(original.isBanned)
        }

        @Test
        fun `updatePrincipal does nothing for a user that is not cached`() {
            val user = existing()
            var called = false

            service.updatePrincipal(user.userId) { called = true; it }

            assertFalse(called)
        }

        @Test
        fun `removeRoleFromAllPrincipals strips that role from every cached principal`() {
            val alice = existing()
            val bob = existing(user())
            service.getPrincipalByUserId(alice.userId, true)
            service.getPrincipalByUserId(bob.userId, true)

            service.removeRoleFromAllPrincipals(roleA)

            assertEquals(listOf(roleB), service.getPrincipalByUserId(alice.userId, true).value.roles)
            assertEquals(listOf(roleB), service.getPrincipalByUserId(bob.userId, true).value.roles)
        }
    }

    @Nested
    inner class LoggingOut {

        @Test
        fun `logging out deletes the token and forgets it in memory`() {
            val user = existing()
            val token = sessionOf(user)
            service.getPrincipalByToken(token, true)
            every { authTokenService.deleteToken(token, any()) } returns Result.ok()
            every { authTokenService.getToken(token) } returns Result.notFound()

            assertSuccess(service.logoutUser(token))

            verify(exactly = 1) { authTokenService.deleteToken(token, any()) }
            assertNotFound(service.getPrincipalByToken(token, true))
        }

        @Test
        fun `failure to delete the token is reported and the session stays remembered`() {
            val user = existing()
            val token = sessionOf(user)
            service.getPrincipalByToken(token, true)
            every { authTokenService.deleteToken(token, any()) } returns Result.error("db down")

            assertError(service.logoutUser(token))

            assertSuccess(service.getPrincipalByToken(token, true))
        }

        @Test
        fun `logging out a user drops all of their remembered tokens`() {
            val user = existing()
            val tokens = List(3) { sessionOf(user) }
            tokens.forEach { service.getPrincipalByToken(it, true) }
            every { authTokenService.removeTokensByUserId(user.userId, null) } returns Result.ok()
            tokens.forEach { every { authTokenService.getToken(it) } returns Result.notFound() }

            assertSuccess(service.logoutUserByUserId(user.userId))

            tokens.forEach { assertNotFound(service.getPrincipalByToken(it, true)) }
        }

        @Test
        fun `the excluded token keeps working`() {
            val user = existing()
            val current = sessionOf(user)
            val other = sessionOf(user)
            service.getPrincipalByToken(current, true)
            service.getPrincipalByToken(other, true)
            every { authTokenService.removeTokensByUserId(user.userId, current) } returns Result.ok()
            every { authTokenService.getToken(current) } returns Result.notFound()
            every { authTokenService.getToken(other) } returns Result.notFound()

            assertSuccess(service.logoutUserByUserId(user.userId, excludedToken = current))

            assertSuccess(service.getPrincipalByToken(current, true))
            assertNotFound(service.getPrincipalByToken(other, true))
        }

        @Test
        fun `other users stay logged in`() {
            val alice = existing()
            val bob = existing(user())
            val aliceToken = sessionOf(alice)
            val bobToken = sessionOf(bob)
            service.getPrincipalByToken(aliceToken, true)
            service.getPrincipalByToken(bobToken, true)
            every { authTokenService.removeTokensByUserId(alice.userId, null) } returns Result.ok()

            service.logoutUserByUserId(alice.userId)

            assertSuccess(service.getPrincipalByToken(bobToken, true))
            verify(exactly = 1) { authTokenService.getToken(bobToken) }
        }

        @Test
        fun `database failure is logged and returned and sessions stay remembered`() {
            val user = existing()
            val token = sessionOf(user)
            service.getPrincipalByToken(token, true)
            every { authTokenService.removeTokensByUserId(user.userId, null) } returns Result.error("db down")

            assertError(service.logoutUserByUserId(user.userId))

            verify(exactly = 1) { logService.error(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { authTokenService.getToken(token) }
        }
    }
}
