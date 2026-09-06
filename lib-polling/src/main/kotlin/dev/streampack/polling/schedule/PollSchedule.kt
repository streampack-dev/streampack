/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.schedule

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** When a source should next be polled. Pure arithmetic, shared by every due-batch poller. */
object PollSchedule {
    /** Jitter added after a success, as a fraction of the interval, so batches drift apart. */
    const val JITTER_FRACTION: Double = 0.1

    /** One interval from [now], plus up to [JITTER_FRACTION] of it. */
    fun afterSuccess(
        now: Instant,
        interval: Duration,
        jitter: Double = JITTER_FRACTION,
        random: Random = Random.Default,
    ): Instant {
        val jitterMillis = (interval.toMillis() * jitter * random.nextDouble()).toLong()
        return now.plus(interval).plusMillis(jitterMillis)
    }

    /**
     * Exponential backoff: the interval doubled for each failure after the first, capped at
     * [maxBackoff]. A source that keeps failing is polled ever less often, never every tick.
     */
    fun afterFailure(
        now: Instant,
        interval: Duration,
        failures: Int,
        maxBackoff: Duration,
    ): Instant {
        val exponent = (failures - 1).coerceIn(0, 30)
        val multiplier = 1L shl exponent
        val backoffMillis = interval.toMillis().saturatingTimes(multiplier)
        val bounded = minOf(backoffMillis, maxBackoff.toMillis())
        return now.plusMillis(bounded)
    }

    /** A random instant inside the next [interval], for spreading a set of sources at once. */
    fun spread(now: Instant, interval: Duration, random: Random = Random.Default): Instant =
        now.plusMillis((interval.toMillis() * random.nextDouble()).toLong())

    private fun Long.saturatingTimes(other: Long): Long =
        if (this != 0L && other > Long.MAX_VALUE / this) Long.MAX_VALUE else this * other
}
