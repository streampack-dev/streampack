/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.schedule

import dev.streampack.core.integration.TickListener
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/** The outcome of polling one source. [Success] carries the source as the poll left it. */
sealed interface PollResult<out T> {
    data class Success<T>(val source: T) : PollResult<T>

    data class Failure(val reason: String?) : PollResult<Nothing>
}

/**
 * Tick-driven polling that spreads work over time instead of sweeping every source at once.
 *
 * Every [schedulerInterval] the service takes at most [batchSize] sources that are due, oldest due
 * first, polls each, and lets the subclass advance that source's next poll time: one interval out
 * on success, backed off on failure (see [PollSchedule]). A source that shares a due time with many
 * others is simply picked up on a later tick, so sources never re-align into one large sweep.
 *
 * Subclasses own persistence: which sources are due, how to poll one, and how to record the next
 * poll time. Exceptions from [poll] count as failures and never stop the rest of the batch.
 */
abstract class DueBatchPollingService<T>(
    private val schedulerInterval: Duration,
    private val batchSize: Int,
) : TickListener {
    private val logger = LoggerFactory.getLogger(javaClass)

    /* The first tick after startup waits a short grace period so protocol adapters can connect */
    private var lastTick: Instant =
        Instant.now().minus(schedulerInterval).plusSeconds(STARTUP_GRACE_SECONDS)

    override fun onTick(now: Instant) {
        if (Duration.between(lastTick, now) < schedulerInterval) return
        lastTick = now
        try {
            pollDue(now)
        } catch (e: Exception) {
            logger.warn("Due-batch poll failed: {}", e.message)
        }
    }

    /** Poll the sources due at [now], up to the batch size. Returns how many were attempted. */
    open fun pollDue(now: Instant = Instant.now()): Int {
        val due = findDue(now, batchSize)
        if (due.isEmpty()) return 0
        logger.debug("Polling {} due source(s) (batch size {})", due.size, batchSize)
        for (source in due) {
            val result =
                try {
                    poll(source)
                } catch (e: Exception) {
                    PollResult.Failure(e.message ?: e.javaClass.simpleName)
                }
            when (result) {
                is PollResult.Success -> scheduleAfterSuccess(result.source, now)
                is PollResult.Failure -> {
                    logger.warn("Failed to poll {}: {}", describe(source), result.reason)
                    scheduleAfterFailure(source, now, result.reason)
                }
            }
        }
        return due.size
    }

    /** The sources due at or before [now], oldest due first, at most [limit] of them. */
    protected abstract fun findDue(now: Instant, limit: Int): List<T>

    /** Poll one source. Return the source as the poll left it so scheduling builds on it. */
    protected abstract fun poll(source: T): PollResult<T>

    /** Record the next poll time after a success and clear any failure count. */
    protected abstract fun scheduleAfterSuccess(source: T, now: Instant)

    /** Record a backed-off next poll time after a failure. */
    protected abstract fun scheduleAfterFailure(source: T, now: Instant, reason: String?)

    /** How [source] is named in logs. */
    protected abstract fun describe(source: T): String

    companion object {
        const val STARTUP_GRACE_SECONDS: Long = 30
    }
}
