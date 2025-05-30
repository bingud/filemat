package org.filemat.server.common.util

import io.github.bucket4j.Bucket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess


enum class RateLimitId {
    LOGIN,
    LOGIN_AUTHED,
    VERIFY_SETUP_CODE,
    SETUP,
}

private data class RateLimitConfig(
    val id: RateLimitId,
    val maxTokens: Long,
    val refillSecondsInterval: Long
)

data class RateLimitResult(
    val isAllowed: Boolean,
    val millisUntilRefill: Long,
)


object RateLimiter {
    //                                      Endpoint ID                    UserID  Bucket
    private val buckets = ConcurrentHashMap<RateLimitId, ConcurrentHashMap<String, Bucket>>()
    private val configs = HashMap<RateLimitId, RateLimitConfig>()

    init {
        configureBucket(RateLimitId.LOGIN, 8, 30)
        configureBucket(RateLimitId.LOGIN_AUTHED, 8, 30)
        configureBucket(RateLimitId.VERIFY_SETUP_CODE, 8, 30)
        configureBucket(RateLimitId.SETUP, 8, 30)

        // Ensure all IDs are included in the configuration
        val configKeys = configs.keys.toList()
        val bucketKeys = buckets.keys.toList()
        RateLimitId.entries.forEach { id ->
            if (!configKeys.contains(id) || !bucketKeys.contains(id)) {
                println("**** \nRate limit configuration is missing for endpoint ID $id")
                exitProcess(1)
            }
        }
    }


    /**
     * Try to consume a token by UserID or IP address.
     */
    fun consume(endpointId: RateLimitId, identifier: String): RateLimitResult {
        val bucket = buckets[endpointId]!!.computeIfAbsent(identifier) { createBucket(endpointId) }
        val result = bucket.tryConsumeAndReturnRemaining(1)
        val millisUntilRefill = result.nanosToWaitForRefill / 1_000_000
        return RateLimitResult(result.isConsumed, millisUntilRefill)
    }

    /**
     * Create a bucket instance
     */
    private fun createBucket(id: RateLimitId): Bucket {
        val config = configs[id]!!
        return Bucket.builder().addLimit {
            it.capacity(config.maxTokens)
                .refillIntervally(1, Duration.ofSeconds(config.refillSecondsInterval))
        }.build()
    }

    /**
     * Create a config for a rate limited endpoint
     */
    private fun configureBucket(id: RateLimitId, maxTokens: Long, refillSecondsInterval: Long) {
        configs.put(
            id,
            RateLimitConfig(
                id = id,
                maxTokens = maxTokens,
                refillSecondsInterval = refillSecondsInterval,
            )
        )
        buckets.put(id, ConcurrentHashMap())
    }
}

