package org.filemat.server.common.util

import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess


enum class RateLimitId {
    LOGIN,
    LOGIN_AUTHED,
    VERIFY_SETUP_CODE,
    SETUP,
    SHARED_FILE_LOGIN,
    SHARED_FILE_LOGIN_ANON,
    SHARED_FILE_PROBE,
    SHARED_FILE_ACCESS,
    FILE_SEARCH,
    VERIFY_TOTP,
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


/**
 * Rate limiter keyed by endpoint ID and identifier (IP or user ID).
 *
 * [clockMillis] is the source of the current time in epoch milliseconds.
 * Tests can pass their own clock to control time.
 */
@Component
class RateLimiter(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val timeMeter = object : TimeMeter {
        override fun currentTimeNanos(): Long = clockMillis() * 1_000_000
        override fun isWallClockBased(): Boolean = true
    }

    //                                      Endpoint ID                    UserID  Bucket
    private val buckets = ConcurrentHashMap<RateLimitId, ConcurrentHashMap<String, TimedBucket>>()
    private val configs = HashMap<RateLimitId, RateLimitConfig>()

    init {
        configureBucket(RateLimitId.LOGIN, 8, 30_000)
        configureBucket(RateLimitId.LOGIN_AUTHED, 8, 30_000)
        configureBucket(RateLimitId.VERIFY_SETUP_CODE, 8, 30_000)
        configureBucket(RateLimitId.SETUP, 8, 30_000)
        configureBucket(RateLimitId.SHARED_FILE_LOGIN, 8, 15_000)
        configureBucket(RateLimitId.SHARED_FILE_LOGIN_ANON, 8, 15_000)
        // Share ID existence checks (per IP): 30 burst, then 1 every 2 seconds
        configureBucket(RateLimitId.SHARED_FILE_PROBE, 30, 2_000)
        // Any request using a share token (per IP): 200 burst, then 10 per second
        configureBucket(RateLimitId.SHARED_FILE_ACCESS, 300, 100)
        // Searches walk whole folder trees (per user): 30 burst, then 1 every 2 seconds
        configureBucket(RateLimitId.FILE_SEARCH, 30, 2_000)
        configureBucket(RateLimitId.VERIFY_TOTP, 8, 30_000)

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

    private val cleanupJob: Job = globalCoroutineScope.launch {
        while (true) {
//            delay(90_000)
            delay(1000)
            removeUnusedBuckets()
        }
    }

    @PreDestroy
    fun shutdown() {
        cleanupJob.cancel()
    }

    private fun nowSeconds() = clockMillis() / 1000

    /**
     * Remove buckets that are full and have not been used for longer than their refill interval.
     */
    internal fun removeUnusedBuckets() {
        val now = nowSeconds()

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


    /**
     * Try to consume a token by UserID or IP address.
     */
    fun consume(endpointId: RateLimitId, identifier: String): RateLimitResult {
        val now = nowSeconds()
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
        val bucket = Bucket.builder().withCustomTimePrecision(timeMeter).addLimit {
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

