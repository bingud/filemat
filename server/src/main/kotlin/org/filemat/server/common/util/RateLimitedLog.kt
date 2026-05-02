package org.filemat.server.common.util

import java.time.Duration

/**
 * Runs [log] at most once per [key] within [cooldownMs]. Call [log] like any other code that uses [org.filemat.server.module.log.service.LogService].
 */
object RateLimitedLog {
    private val lock = Any()
    private val lastAt = HashMap<String, Long>()

    private val defaultCooldownMs: Long = Duration.ofMinutes(5).toMillis().coerceAtLeast(0L)

    fun ifDue(key: String, cooldownMs: Long = defaultCooldownMs, log: () -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = lastAt[key] ?: 0L
            if (now - last < cooldownMs) return
            lastAt[key] = now
        }
        log()
    }
}
