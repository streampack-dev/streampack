/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.review

import dev.streampack.core.integration.TickListener
import dev.streampack.moderation.config.ModerationProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs [ModerationReviewService] every [ModerationProperties.reviewInterval], off the tick thread,
 * since a review may wait on the model. One review at a time; a tick that comes while one runs is
 * passed over. The first tick only starts the clock: nobody has a score yet.
 */
@Component
class ModerationReviewTickListener(
    private val service: ModerationReviewService,
    private val properties: ModerationProperties,
) : TickListener {
    private val logger = LoggerFactory.getLogger(ModerationReviewTickListener::class.java)
    private val running = AtomicBoolean(false)
    private var lastRunAt: Instant? = null

    override fun onTick(now: Instant) {
        if (!properties.enabled) return
        val previous = lastRunAt
        if (previous == null) {
            lastRunAt = now
            return
        }
        if (Duration.between(previous, now) < properties.reviewInterval) return
        if (!running.compareAndSet(false, true)) return
        lastRunAt = now
        Thread.ofVirtual().name("moderation-review").start {
            try {
                service.review(now)
            } catch (e: Exception) {
                logger.warn("Moderation review failed: {}", e.message)
            } finally {
                running.set(false)
            }
        }
    }
}
