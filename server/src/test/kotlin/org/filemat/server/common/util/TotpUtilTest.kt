package org.filemat.server.common.util

import com.atlassian.onetime.model.TOTPSecret
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verifies the 2FA code check: the current code and one step either side are accepted, nothing else. */
class TotpUtilTest {

    private val secret = TOTPSecret.fromBase32EncodedString("JBSWY3DPEHPK3PXP")
    private val otherSecret = TOTPSecret.fromBase32EncodedString("GEZDGNBVGY3TQOJQ")

    private fun codes(secret: TOTPSecret, past: Int, future: Int) =
        TotpUtil.generator.generate(secret, delaySteps = past, futureSteps = future).map { it.value }.toSet()

    @Test
    fun `current code is accepted`() {
        val current = TotpUtil.generator.generateCurrent(secret).value

        assertTrue(TotpUtil.verify(secret, current))
    }

    @Test
    fun `codes one step before and after the current one are accepted for clock drift`() {
        val allowed = codes(secret, past = 1, future = 1)

        assertTrue(allowed.size >= 2)
        allowed.forEach { assertTrue(TotpUtil.verify(secret, it), "Expected $it to be accepted") }
    }

    @Test
    fun `codes two or more steps away are rejected`() {
        val tooFar = codes(secret, past = 3, future = 3) - codes(secret, past = 1, future = 1)

        assertTrue(tooFar.isNotEmpty())
        tooFar.forEach { assertFalse(TotpUtil.verify(secret, it), "Expected $it to be rejected") }
    }

    @Test
    fun `code generated for another secret is rejected`() {
        val foreign = codes(otherSecret, past = 1, future = 1) - codes(secret, past = 1, future = 1)

        assertTrue(foreign.isNotEmpty())
        foreign.forEach { assertFalse(TotpUtil.verify(secret, it)) }
    }

    @Test
    fun `malformed codes are rejected`() {
        listOf("", " ", "12345", "1234567", "abcdef").forEach {
            assertFalse(TotpUtil.verify(secret, it), "Expected '$it' to be rejected")
        }
    }
}
