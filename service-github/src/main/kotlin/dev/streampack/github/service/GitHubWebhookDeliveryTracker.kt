/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.config.GitHubProperties
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

/** Tracks recently seen webhook delivery IDs to suppress duplicate re-deliveries. */
@Service
class GitHubWebhookDeliveryTracker(private val properties: GitHubProperties) {
    private val seenDeliveries = ConcurrentHashMap<String, Instant>()

    fun isDuplicate(deliveryId: String): Boolean {
        val now = Instant.now()
        evictExpired(now)
        val previous = seenDeliveries.putIfAbsent(deliveryId, now)
        return previous != null
    }

    private fun evictExpired(now: Instant) {
        val ttl = properties.deliveryDedupeTtl
        val cutoff = now.minus(ttl)
        seenDeliveries.entries.removeIf { it.value.isBefore(cutoff) }
    }
}
