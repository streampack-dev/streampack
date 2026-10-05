/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import dev.streampack.core.integration.TickListener
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs [OutgoingLinksService] every [OutgoingLinksProperties.interval], off the tick thread: the
 * pass fetches other sites, which mustn't hold up the other listeners. One pass at a time; a tick
 * that comes while one runs is passed over.
 */
@Component
class OutgoingLinksTickListener(
    private val service: OutgoingLinksService,
    private val properties: OutgoingLinksProperties,
) : TickListener {
    private val logger = LoggerFactory.getLogger(OutgoingLinksTickListener::class.java)
    private val running = AtomicBoolean(false)
    private var lastRunAt: Instant? = null

    override fun onTick(now: Instant) {
        if (!properties.enabled) return
        val previous = lastRunAt
        if (previous != null && Duration.between(previous, now) < properties.interval) return
        if (!running.compareAndSet(false, true)) return
        lastRunAt = now
        Thread.ofVirtual().name("outgoing-links").start {
            try {
                val handled = service.runBatch(now)
                if (handled > 0) logger.debug("Handled the outgoing links of {} post(s)", handled)
            } catch (e: Exception) {
                logger.warn("Outgoing links pass failed: {}", e.message)
            } finally {
                running.set(false)
            }
        }
    }
}
