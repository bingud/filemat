package org.filemat.server.common.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the rate limiter with a fake clock: how many requests are allowed,
 * when callers are blocked, when the window refills, and that keys do not affect each other.
 */
class RateLimiterTest {

    private var nowMillis = 1_000_000_000L
    private val limiter = RateLimiter(clockMillis = { nowMillis })

    @AfterEach
    fun tearDown() {
        limiter.shutdown()
    }

    private fun consumeAll(id: RateLimitId, key: String, times: Int) =
        List(times) { limiter.consume(id, key) }

    @Test
    fun `allows requests up to the limit`() {
        val results = consumeAll(RateLimitId.LOGIN, "ip-1", 8)

        assertTrue(results.all { it.isAllowed })
    }

    @Test
    fun `blocks the request after the limit and reports the wait time`() {
        consumeAll(RateLimitId.LOGIN, "ip-1", 8)

        val blocked = limiter.consume(RateLimitId.LOGIN, "ip-1")

        assertFalse(blocked.isAllowed)
        assertEquals(30_000, blocked.millisUntilRefill)
    }

    @Test
    fun `share login uses a 15 second refill`() {
        consumeAll(RateLimitId.SHARED_FILE_LOGIN_ANON, "ip-1", 8)

        val blocked = limiter.consume(RateLimitId.SHARED_FILE_LOGIN_ANON, "ip-1")

        assertFalse(blocked.isAllowed)
        assertEquals(15_000, blocked.millisUntilRefill)
    }

    @Test
    fun `one token is refilled after the refill interval`() {
        consumeAll(RateLimitId.LOGIN, "ip-1", 8)
        assertFalse(limiter.consume(RateLimitId.LOGIN, "ip-1").isAllowed)

        nowMillis += 29_999
        assertFalse(limiter.consume(RateLimitId.LOGIN, "ip-1").isAllowed)

        nowMillis += 1
        assertTrue(limiter.consume(RateLimitId.LOGIN, "ip-1").isAllowed)
        assertFalse(limiter.consume(RateLimitId.LOGIN, "ip-1").isAllowed)
    }

    @Test
    fun `waiting for the full window restores the whole limit`() {
        consumeAll(RateLimitId.SHARED_FILE_LOGIN, "ip-1", 8)

        nowMillis += 8 * 15_000

        assertTrue(consumeAll(RateLimitId.SHARED_FILE_LOGIN, "ip-1", 8).all { it.isAllowed })
        assertFalse(limiter.consume(RateLimitId.SHARED_FILE_LOGIN, "ip-1").isAllowed)
    }

    @Test
    fun `different keys have independent limits`() {
        consumeAll(RateLimitId.LOGIN, "ip-1", 8)

        assertFalse(limiter.consume(RateLimitId.LOGIN, "ip-1").isAllowed)
        assertTrue(limiter.consume(RateLimitId.LOGIN, "ip-2").isAllowed)
    }

    @Test
    fun `different endpoints have independent limits for the same key`() {
        consumeAll(RateLimitId.LOGIN, "ip-1", 8)

        assertFalse(limiter.consume(RateLimitId.LOGIN, "ip-1").isAllowed)
        assertTrue(limiter.consume(RateLimitId.SETUP, "ip-1").isAllowed)
    }

    @Test
    fun `limiter instances do not share state`() {
        val other = RateLimiter(clockMillis = { nowMillis })
        try {
            consumeAll(RateLimitId.LOGIN, "ip-1", 8)

            assertTrue(other.consume(RateLimitId.LOGIN, "ip-1").isAllowed)
        } finally {
            other.shutdown()
        }
    }
}
