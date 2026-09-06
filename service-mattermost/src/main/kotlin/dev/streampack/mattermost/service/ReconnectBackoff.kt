/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import java.time.Duration

/** Reconnect delay for a Mattermost socket: the base delay doubled per failed attempt, capped. */
object ReconnectBackoff {
    fun delay(attempt: Int, base: Duration, max: Duration): Duration {
        val exponent = (attempt - 1).coerceIn(0, 30)
        val millis = base.toMillis().saturatingTimes(1L shl exponent)
        return Duration.ofMillis(minOf(millis, max.toMillis()))
    }

    private fun Long.saturatingTimes(other: Long): Long =
        if (this != 0L && other > Long.MAX_VALUE / this) Long.MAX_VALUE else this * other
}
