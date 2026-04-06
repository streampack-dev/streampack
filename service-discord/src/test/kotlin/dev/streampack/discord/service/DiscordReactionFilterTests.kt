/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.service

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Tests the reaction relay filtering logic used by the Discord adapter */
class DiscordReactionFilterTests {

    private lateinit var cache: MutableMap<String, LastMessage>

    @BeforeEach
    fun setup() {
        cache = mutableMapOf()
    }

    /** Helper that replicates the adapter's shouldRelayReaction logic against the cache */
    private fun shouldRelay(channelKey: String, messageId: String): Boolean {
        val tracked = cache[channelKey] ?: return false
        if (tracked.messageId != messageId) return false
        return tracked.reactionCount.incrementAndGet() <= DiscordAdapter.MAX_REACTIONS_PER_MESSAGE
    }

    @Test
    fun `reaction on last message is relayed`() {
        cache["chan-1"] = LastMessage("msg-100", AtomicInteger(0))
        assertTrue(shouldRelay("chan-1", "msg-100"))
    }

    @Test
    fun `reaction on older message is silently dropped`() {
        cache["chan-1"] = LastMessage("msg-100", AtomicInteger(0))
        assertFalse(shouldRelay("chan-1", "msg-99"))
    }

    @Test
    fun `reaction on unknown channel is silently dropped`() {
        assertFalse(shouldRelay("unknown-channel", "msg-100"))
    }

    @Test
    fun `first five reactions are relayed`() {
        cache["chan-1"] = LastMessage("msg-100", AtomicInteger(0))
        repeat(5) { i ->
            assertTrue(shouldRelay("chan-1", "msg-100"), "Reaction ${i + 1} should be relayed")
        }
    }

    @Test
    fun `sixth reaction on same message is dropped`() {
        cache["chan-1"] = LastMessage("msg-100", AtomicInteger(0))
        repeat(5) { shouldRelay("chan-1", "msg-100") }
        assertFalse(shouldRelay("chan-1", "msg-100"))
    }

    @Test
    fun `new message resets reaction count`() {
        cache["chan-1"] = LastMessage("msg-100", AtomicInteger(0))
        repeat(5) { shouldRelay("chan-1", "msg-100") }

        // New message arrives, resetting the counter
        cache["chan-1"] = LastMessage("msg-101", AtomicInteger(0))
        assertTrue(shouldRelay("chan-1", "msg-101"))
        assertEquals(1, cache["chan-1"]!!.reactionCount.get())
    }

    @Test
    fun `channels are tracked independently`() {
        cache["chan-1"] = LastMessage("msg-100", AtomicInteger(0))
        cache["chan-2"] = LastMessage("msg-200", AtomicInteger(0))

        repeat(5) { shouldRelay("chan-1", "msg-100") }
        assertFalse(shouldRelay("chan-1", "msg-100"))

        // chan-2 is unaffected
        assertTrue(shouldRelay("chan-2", "msg-200"))
    }
}
