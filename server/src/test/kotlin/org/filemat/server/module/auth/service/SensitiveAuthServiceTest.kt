package org.filemat.server.module.auth.service

import io.mockk.mockk
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.log.service.LogService
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SensitiveAuthServiceTest {

    private val service = SensitiveAuthService(mockk<LogService>(relaxed = true))

    @Test
    fun `verifyOtp succeeds on first use of a valid code`() {
        val otp = "ABC123VALIDCODE01"
        val expirationDate = unixNow() + 120
        service.tokens.put(otp, expirationDate)

        val result = service.verifyOtp(otp)

        assertSuccess(result)
        assertEquals(expirationDate, result.value)
    }

    @Test
    fun `verifyOtp allows the same code until it expires`() {
        val otp = "REUSECODE0000001"
        val expirationDate = unixNow() + 120
        service.tokens.put(otp, expirationDate)

        val first = service.verifyOtp(otp)
        val second = service.verifyOtp(otp)

        assertSuccess(first)
        assertSuccess(second)
        assertEquals(expirationDate, second.value)
    }

    @Test
    fun `verifyOtp rejects an unknown code`() {
        val result = service.verifyOtp("UNKNOWNCODE00001")

        assertRejected(result)
        assertEquals("Code is invalid.", result.error)
    }

    @Test
    fun `verifyOtp rejects an expired code`() {
        val otp = "EXPIREDCODE00001"
        service.tokens.put(otp, unixNow() - 1)

        val result = service.verifyOtp(otp)

        assertRejected(result)
        assertEquals("Code is invalid.", result.error)
        assertRejected(service.verifyOtp(otp))
    }

    @Test
    fun `verifyOtp does not invalidate unrelated codes`() {
        val first = "FIRSTCODE0000001"
        val other = "OTHERCODE0000001"
        val otherExpiration = unixNow() + 120
        service.tokens.put(first, unixNow() + 120)
        service.tokens.put(other, otherExpiration)

        assertSuccess(service.verifyOtp(first))

        val otherResult = service.verifyOtp(other)
        assertSuccess(otherResult)
        assertEquals(otherExpiration, otherResult.value)
    }
}
