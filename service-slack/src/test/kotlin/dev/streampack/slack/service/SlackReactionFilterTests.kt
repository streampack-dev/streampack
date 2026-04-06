/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Tests the reaction relay filtering logic used by the Slack adapter */
class SlackReactionFilterTests {

    private lateinit var cache: MutableMap<String, LastSlackMessage>

    @BeforeEach
    fun setup() {
        cache = mutableMapOf()
    }

    /** Helper that replicates the adapter's shouldRelayReaction logic against the cache */
    private fun shouldRelay(channelKey: String, messageTs: String): Boolean {
        val tracked = cache[channelKey] ?: return false
        if (tracked.messageTs != messageTs) return false
        return tracked.reactionCount.incrementAndGet() <= SlackAdapter.MAX_REACTIONS_PER_MESSAGE
    }

    @Test
    fun `reaction on last message is relayed`() {
        cache["C0123"] = LastSlackMessage("1234567890.000100", AtomicInteger(0))
        assertTrue(shouldRelay("C0123", "1234567890.000100"))
    }

    @Test
    fun `reaction on older message is silently dropped`() {
        cache["C0123"] = LastSlackMessage("1234567890.000100", AtomicInteger(0))
        assertFalse(shouldRelay("C0123", "1234567890.000099"))
    }

    @Test
    fun `reaction on unknown channel is silently dropped`() {
        assertFalse(shouldRelay("C-unknown", "1234567890.000100"))
    }

    @Test
    fun `first five reactions are relayed`() {
        cache["C0123"] = LastSlackMessage("1234567890.000100", AtomicInteger(0))
        repeat(5) { i ->
            assertTrue(
                shouldRelay("C0123", "1234567890.000100"),
                "Reaction ${i + 1} should be relayed",
            )
        }
    }

    @Test
    fun `sixth reaction on same message is dropped`() {
        cache["C0123"] = LastSlackMessage("1234567890.000100", AtomicInteger(0))
        repeat(5) { shouldRelay("C0123", "1234567890.000100") }
        assertFalse(shouldRelay("C0123", "1234567890.000100"))
    }

    @Test
    fun `new message resets reaction count`() {
        cache["C0123"] = LastSlackMessage("1234567890.000100", AtomicInteger(0))
        repeat(5) { shouldRelay("C0123", "1234567890.000100") }

        // New message arrives, resetting the counter
        cache["C0123"] = LastSlackMessage("1234567890.000200", AtomicInteger(0))
        assertTrue(shouldRelay("C0123", "1234567890.000200"))
        assertEquals(1, cache["C0123"]!!.reactionCount.get())
    }

    @Test
    fun `channels are tracked independently`() {
        cache["C0123"] = LastSlackMessage("1234567890.000100", AtomicInteger(0))
        cache["C0456"] = LastSlackMessage("1234567890.000200", AtomicInteger(0))

        repeat(5) { shouldRelay("C0123", "1234567890.000100") }
        assertFalse(shouldRelay("C0123", "1234567890.000100"))

        // C0456 is unaffected
        assertTrue(shouldRelay("C0456", "1234567890.000200"))
    }
}
