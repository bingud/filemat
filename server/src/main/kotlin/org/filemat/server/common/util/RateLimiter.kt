package org.filemat.server.common.util

import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess


enum class RateLimitId {
    LOGIN,
    LOGIN_AUTHED,
    VERIFY_SETUP_CODE,
    SETUP,
    SHARED_FILE_LOGIN,
}

private data class RateLimitConfig(
    val id: RateLimitId,
    val maxTokens: Long,
    val refillIntervalMillis: Long
) {
    val refillIntervalSeconds: Long = refillIntervalMillis / 1_000
}

data class RateLimitResult(
    val isAllowed: Boolean,
    val millisUntilRefill: Long,
)
data class TimedBucket(
    val bucket: Bucket,
    var lastRequestDate: Long,
)


object RateLimiter {
    //                                      Endpoint ID                    UserID  Bucket
    private val buckets = ConcurrentHashMap<RateLimitId, ConcurrentHashMap<String, TimedBucket>>()
    private val configs = HashMap<RateLimitId, RateLimitConfig>()

    init {
        configureBucket(RateLimitId.LOGIN, 8, 30_000)
        configureBucket(RateLimitId.LOGIN_AUTHED, 8, 30_000)
        configureBucket(RateLimitId.VERIFY_SETUP_CODE, 8, 30_000)
        configureBucket(RateLimitId.SETUP, 8, 30_000)
        configureBucket(RateLimitId.SHARED_FILE_LOGIN, 8, 20_000)

        // Ensure all IDs are included in the configuration
        val configKeys = configs.keys.toList()
        val bucketKeys = buckets.keys.toList()
        RateLimitId.entries.forEach { id ->
            if (!configKeys.contains(id) || !bucketKeys.contains(id)) {
                println("**** \nRate limit configuration is missing for endpoint ID $id")
                exitProcess(1)
            }
        }

        loop_removeUnusedBuckets()
    }

    private fun loop_removeUnusedBuckets() = globalCoroutineScope.launch {
        while (true) {
//            delay(90_000)
            delay(1000)
            val now = unixNow()

            // Loop over all endpoints
            buckets.forEach { id: RateLimitId, userBuckets: ConcurrentHashMap<String, TimedBucket> ->
                val configuration = configs[id]!!

                // Loop over all buckets for this endpoint
                userBuckets.iterate { identifier: String, timedBucket: TimedBucket, remove: Function0<Unit> ->
                    val bucket = timedBucket.bucket
                    val hasMaxTokens = bucket.availableTokens == configuration.maxTokens

                    val secondsSinceLastRequest = now - timedBucket.lastRequestDate
                    val hasOverflownToken = secondsSinceLastRequest > configuration.refillIntervalSeconds

                    // If bucket has maximum tokens and has overflown at least one more token
                    if (hasMaxTokens && hasOverflownToken) {
                        remove()
                    }
                }
            }
        }
    }


    /**
     * Try to consume a token by UserID or IP address.
     */
    fun consume(endpointId: RateLimitId, identifier: String): RateLimitResult {
        val now = unixNow()
        val timedBucket = buckets[endpointId]!!.computeIfAbsent(identifier) {
            createBucket(id = endpointId, lastRequestDate = now)
        }
        val bucket = timedBucket.bucket
        timedBucket.lastRequestDate = now

        val result = bucket.tryConsumeAndReturnRemaining(1)
        val millisUntilRefill = result.nanosToWaitForRefill / 1_000_000
        return RateLimitResult(result.isConsumed, millisUntilRefill)
    }

    /**
     * Create a bucket instance
     */
    private fun createBucket(id: RateLimitId, lastRequestDate: Long): TimedBucket {
        val config = configs[id]!!
        val bucket = Bucket.builder().addLimit {
            it.capacity(config.maxTokens)
                .refillIntervally(1, Duration.ofMillis(config.refillIntervalMillis))
        }.build()

        val timedBucket = TimedBucket(bucket = bucket, lastRequestDate = lastRequestDate)
        return timedBucket
    }

    /**
     * Create a config for a rate limited endpoint
     */
    private fun configureBucket(id: RateLimitId, maxTokens: Long, refillIntervalMillis: Long) {
        configs.put(
            id,
            RateLimitConfig(
                id = id,
                maxTokens = maxTokens,
                refillIntervalMillis = refillIntervalMillis,
            )
        )
        buckets.put(id, ConcurrentHashMap())
    }
}

