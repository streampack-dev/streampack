/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kitteh.irc.client.library.Client
import org.mockito.Mockito
import org.slf4j.LoggerFactory

class AdaptiveDelaySenderTests {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val testMinDelayMs = 1
    private val testMaxDelayMs = 10
    private val testRampUpFactor = 2.0
    private val testRampDownFactor = 0.5

    @Test
    fun `delay ramps up when queue has backlog`() {
        val sender =
            AdaptiveDelaySender(
                client = Mockito.mock(Client::class.java),
                name = "test",
                minDelayMs = testMinDelayMs,
                maxDelayMs = testMaxDelayMs,
                rampUpFactor = testRampUpFactor,
                rampDownFactor = testRampDownFactor,
            )
        val before = sender.currentDelayValue()
        sender.stepDelay(hasBacklog = true)
        val after = sender.currentDelayValue()
        logger.info("ramp-up expectation: after({}) > before({})", after, before)
        assertTrue(after > before, "Expected ramp-up with backlog; before=$before, after=$after")
    }

    @Test
    fun `delay ramps down when queue is empty`() {
        val sender =
            AdaptiveDelaySender(
                client = Mockito.mock(Client::class.java),
                name = "test",
                minDelayMs = testMinDelayMs,
                maxDelayMs = testMaxDelayMs,
                rampUpFactor = testRampUpFactor,
                rampDownFactor = testRampDownFactor,
            )
        sender.stepDelay(hasBacklog = true)
        val elevated = sender.currentDelayValue()
        sender.stepDelay(hasBacklog = false)
        val lowered = sender.currentDelayValue()
        logger.info("ramp-down expectation: lowered({}) < elevated({})", lowered, elevated)
        assertTrue(
            lowered < elevated,
            "Expected ramp-down with empty queue; elevated=$elevated, lowered=$lowered",
        )
        assertTrue(lowered >= testMinDelayMs.toDouble(), "Expected floor clamp; lowered=$lowered")
    }

    @Test
    fun `delay remains bounded by configured floor and ceiling`() {
        val sender =
            AdaptiveDelaySender(
                client = Mockito.mock(Client::class.java),
                name = "test",
                minDelayMs = testMinDelayMs,
                maxDelayMs = testMaxDelayMs,
                rampUpFactor = testRampUpFactor,
                rampDownFactor = testRampDownFactor,
            )

        repeat(12) { sender.stepDelay(hasBacklog = true) }
        val maxObserved = sender.currentDelayValue()
        logger.info(
            "ceiling expectation: value({}) <= max({})",
            maxObserved,
            testMaxDelayMs.toDouble(),
        )
        assertTrue(
            maxObserved <= testMaxDelayMs.toDouble(),
            "Expected ceiling clamp; observed=$maxObserved",
        )

        repeat(12) { sender.stepDelay(hasBacklog = false) }
        val minObserved = sender.currentDelayValue()
        logger.info(
            "floor expectation: value({}) >= min({})",
            minObserved,
            testMinDelayMs.toDouble(),
        )
        assertTrue(
            minObserved >= testMinDelayMs.toDouble(),
            "Expected floor clamp; observed=$minObserved",
        )
    }

    @Test
    fun `checkReady returns true`() {
        val sender =
            AdaptiveDelaySender(
                client = Mockito.mock(Client::class.java),
                name = "test",
                minDelayMs = testMinDelayMs,
                maxDelayMs = testMaxDelayMs,
                rampUpFactor = testRampUpFactor,
                rampDownFactor = testRampDownFactor,
            )
        assertTrue(sender.checkReady("line"), "Expected checkReady to return true")
    }
}
