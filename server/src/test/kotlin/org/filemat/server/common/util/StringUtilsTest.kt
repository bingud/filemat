package org.filemat.server.common.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.security.SecureRandom

class StringUtilsTest {

    /**
     * StringUtils.randomString/randomLetterString back every security token the app issues:
     * the session auth token, login tokens, MFA backup codes, OTPs, and file-share tokens
     * (see AuthTokenService, LoginService, MfaService, SensitiveAuthService, FileShareService).
     * Before the fix they were generated with kotlin.random.Random, a fast PRNG that is not
     * cryptographically secure: given the same seed it always produces the same output, which
     * a security token generator must never do. This test demonstrates that property on the
     * exact generator that used to back these tokens, then confirms StringUtils now draws from
     * SecureRandom instead.
     */
    @Test
    fun `kotlin random, the generator previously used for tokens, is deterministic given the same seed`() {
        val a = kotlin.random.Random(42)
        val b = kotlin.random.Random(42)

        val sequenceA = (1..32).map { a.nextInt(0, 62) }
        val sequenceB = (1..32).map { b.nextInt(0, 62) }

        // Two generators seeded the same way produce an identical stream: the hallmark of a
        // PRNG, not a cryptographically secure one.
        assertEquals(sequenceA, sequenceB)
    }

    @Test
    fun `randomString now draws from SecureRandom, not kotlin random`() {
        val field: Field = StringUtils::class.java.getDeclaredField("secureRandom")
        field.isAccessible = true
        val source = field.get(StringUtils)

        assertTrue(source is SecureRandom, "StringUtils must generate tokens from SecureRandom")
    }

    @Test
    fun `randomString still returns a string of the requested length from the expected character pool`() {
        val result = StringUtils.randomString(32)
        assertEquals(32, result.length)
        assertTrue(result.all { it.isLetterOrDigit() })
    }

    @Test
    fun `randomString does not repeat across calls`() {
        val first = StringUtils.randomString(32)
        val second = StringUtils.randomString(32)
        assertNotEquals(first, second)
    }
}
