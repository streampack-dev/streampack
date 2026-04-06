/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import kotlin.math.max
import kotlin.math.min
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.feature.sending.QueueProcessingThreadSender

/**
 * Queue-aware sender that adapts per-message delay based on current backlog.
 * - Starts at min delay
 * - Ramps up by [rampUpFactor] while backlog exists
 * - Ramps down by [rampDownFactor] when queue drains
 * - Clamps to [minDelayMs]..[maxDelayMs]
 */
class AdaptiveDelaySender(
    client: Client,
    name: String,
    private val minDelayMs: Int,
    private val maxDelayMs: Int,
    private val rampUpFactor: Double,
    private val rampDownFactor: Double,
) : QueueProcessingThreadSender(client, name) {
    private var lastSentAtMs: Long = System.currentTimeMillis()
    private var currentDelayMs: Double = minDelayMs.toDouble()

    public override fun checkReady(element: String): Boolean {
        while (true) {
            val now = System.currentTimeMillis()
            val waitMs = currentDelayMs.toLong() - (now - lastSentAtMs)
            if (waitMs <= 0) {
                lastSentAtMs = now
                stepDelay(getQueue().isNotEmpty())
                return true
            }
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                interrupt()
                return false
            }
        }
    }

    internal fun currentDelayValue(): Double = currentDelayMs

    internal fun stepDelay(hasBacklog: Boolean) {
        val next =
            if (hasBacklog) currentDelayMs * rampUpFactor else currentDelayMs * rampDownFactor

        currentDelayMs = min(maxDelayMs.toDouble(), max(minDelayMs.toDouble(), next))
    }

    companion object {
        fun getSupplier(
            minDelayMs: Int,
            maxDelayMs: Int,
            rampUpFactor: Double,
            rampDownFactor: Double,
        ): (Client.WithManagement) -> AdaptiveDelaySender {
            return { withManagement ->
                AdaptiveDelaySender(
                    withManagement.client,
                    withManagement.name,
                    minDelayMs,
                    maxDelayMs,
                    rampUpFactor,
                    rampDownFactor,
                )
            }
        }
    }
}
