/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.model.ThrottlePolicy
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Token bucket rate limiter keyed by arbitrary strings (typically "OperationName:provenanceUri").
 * Each bucket starts full and replenishes tokens at a steady rate derived from the policy.
 */
@Service
class ThrottleService {
    private val logger = LoggerFactory.getLogger(ThrottleService::class.java)
    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    /** Attempts to acquire a token. Returns true if the request is allowed, false if throttled. */
    fun tryAcquire(key: String, policy: ThrottlePolicy): Boolean {
        val bucket = buckets.computeIfAbsent(key) { TokenBucket(policy) }
        return bucket.tryAcquire()
    }

    /** Clears all tracked buckets. Intended for testing. */
    fun clear() {
        buckets.clear()
    }

    /**
     * Token bucket with smooth replenishment. Tracks tokens as a double to allow fractional
     * accumulation between requests.
     */
    private class TokenBucket(private val policy: ThrottlePolicy) {
        private var tokens: Double = policy.maxRequests.toDouble()
        private var lastRefillNanos: Long = System.nanoTime()
        private val refillRatePerNano: Double =
            policy.maxRequests.toDouble() / policy.window.toNanos()

        @Synchronized
        fun tryAcquire(): Boolean {
            refill()
            if (tokens >= 1.0) {
                tokens -= 1.0
                return true
            }
            return false
        }

        private fun refill() {
            val now = System.nanoTime()
            val elapsed = now - lastRefillNanos
            tokens = minOf(policy.maxRequests.toDouble(), tokens + elapsed * refillRatePerNano)
            lastRefillNanos = now
        }
    }
}
