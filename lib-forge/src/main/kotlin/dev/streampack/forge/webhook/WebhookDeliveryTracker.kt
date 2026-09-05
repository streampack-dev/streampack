/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.webhook

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Remembers recent webhook delivery ids so redelivered events are not fanned out twice. */
open class WebhookDeliveryTracker(private val dedupeTtl: Duration) {
    private val seenDeliveries = ConcurrentHashMap<String, Instant>()

    fun isDuplicate(deliveryId: String): Boolean {
        val now = Instant.now()
        evictExpired(now)
        val previous = seenDeliveries.putIfAbsent(deliveryId, now)
        return previous != null
    }

    private fun evictExpired(now: Instant) {
        val cutoff = now.minus(dedupeTtl)
        seenDeliveries.entries.removeIf { it.value.isBefore(cutoff) }
    }
}
