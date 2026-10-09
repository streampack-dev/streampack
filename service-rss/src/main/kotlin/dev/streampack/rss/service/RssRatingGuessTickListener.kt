/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import dev.streampack.core.integration.TickListener
import dev.streampack.rss.config.RssProperties
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs a guess pass ([RssRatingGuessService]) every `streampack.rss.rating.guess-interval` (a day
 * by default) while the guess is on, off the tick thread, since it waits on the model. The first
 * pass comes [FIRST_PASS_DELAY] after startup, so a restart doesn't push a day's items out of the
 * lookback; items already guessed are never sent again, so that costs nothing extra. A pass that's
 * still running when the next is due (or an admin's manual one) is left to finish.
 */
@Component
class RssRatingGuessTickListener(
    private val service: RssRatingGuessService,
    private val properties: RssProperties,
) : TickListener {
    private val logger = LoggerFactory.getLogger(RssRatingGuessTickListener::class.java)

    @Volatile private var nextRunAt: Instant? = null

    override fun onTick(now: Instant) {
        if (!service.enabled) return
        val due = nextRunAt ?: now.plus(properties.rating.firstGuessDelay).also { nextRunAt = it }
        if (now < due) return
        nextRunAt = now.plus(properties.rating.guessInterval)
        Thread.ofVirtual().name("rss-rating-guess").start {
            try {
                service.run(now)
            } catch (e: RssRatingGuessService.BusyException) {
                logger.info("RSS rating guess: a pass is running already")
            } catch (e: Exception) {
                logger.warn("RSS rating guess failed: {}", e.message)
            }
        }
    }
}
