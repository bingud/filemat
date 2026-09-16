package org.filemat.server.module.auth.service

import io.mockk.mockk
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.log.service.LogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SensitiveAuthServiceTest {

    private val service = SensitiveAuthService(mockk<LogService>(relaxed = true))

    @Test
    fun `verifyOtp succeeds on first use of a valid code`() {
        val otp = "ABC123VALIDCODE01"
        val expirationDate = unixNow() + 120
        service.tokens.put(otp, expirationDate)

        val result = service.verifyOtp(otp)

        assertTrue(result.isSuccessful)
        assertEquals(expirationDate, result.value)
    }

    @Test
    fun `verifyOtp rejects the same code on second use`() {
        val otp = "REUSECODE0000001"
        service.tokens.put(otp, unixNow() + 120)

        val first = service.verifyOtp(otp)
        val second = service.verifyOtp(otp)

        assertTrue(first.isSuccessful)
        assertTrue(second.rejected)
        assertEquals("Code is invalid.", second.error)
    }

    @Test
    fun `verifyOtp rejects an unknown code`() {
        val result = service.verifyOtp("UNKNOWNCODE00001")

        assertTrue(result.rejected)
        assertEquals("Code is invalid.", result.error)
    }

    @Test
    fun `verifyOtp rejects an expired code`() {
        val otp = "EXPIREDCODE00001"
        service.tokens.put(otp, unixNow() - 1)

        val result = service.verifyOtp(otp)

        assertTrue(result.rejected)
        assertEquals("Code is invalid.", result.error)
        assertTrue(service.verifyOtp(otp).rejected)
    }

    @Test
    fun `verifyOtp does not consume unrelated codes`() {
        val consumed = "CONSUMEDCODE0001"
        val other = "OTHERCODE0000001"
        val otherExpiration = unixNow() + 120
        service.tokens.put(consumed, unixNow() + 120)
        service.tokens.put(other, otherExpiration)

        assertTrue(service.verifyOtp(consumed).isSuccessful)

        val otherResult = service.verifyOtp(other)
        assertTrue(otherResult.isSuccessful)
        assertEquals(otherExpiration, otherResult.value)
    }

    @Test
    fun `concurrent verifyOtp of the same code succeeds only once`() {
        val otp = "CONCURRENTCODE001"
        service.tokens.put(otp, unixNow() + 120)

        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val results = executor.invokeAll(List(threadCount) {
                Callable { service.verifyOtp(otp) }
            }).map { it.get() }

            assertEquals(1, results.count { it.isSuccessful })
            assertEquals(threadCount - 1, results.count { it.rejected })
        } finally {
            executor.shutdownNow()
        }
    }
}
