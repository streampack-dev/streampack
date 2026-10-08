/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.signal

import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.model.Signal
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scores over time (#150): they decay, repetition adds up, and crossing marks for review. */
class ModerationScoresTests {
    private val properties = ModerationProperties()
    private val scores = ModerationScores(properties)
    private val uri = "irc://libera/%23java"
    private val t0 = Instant.parse("2026-10-07T12:00:00Z")

    private fun speaker(nick: String) = ModerationScores.Speaker(uri, nick, "irc", "libera", null)

    @Test
    fun `scores decay`() {
        scores.record(speaker("bob"), "hi all", t0)
        scores.record(speaker("alice"), "bob: you're a fucking idiot", t0)
        val start = scores.scoreOf(uri, "alice", t0)
        assertEquals(properties.weights.aimedHostility, start, 0.001)
        val halfLater = scores.scoreOf(uri, "alice", t0.plus(properties.halfLife))
        assertEquals(start / 2, halfLater, 0.01)
        val muchLater = scores.scoreOf(uri, "alice", t0.plus(Duration.ofHours(6)))
        assertTrue(muchLater < 0.01, "$muchLater")
    }

    @Test
    fun `repetition adds up`() {
        val first = scores.record(speaker("alice"), "BUY NOW", t0)
        assertTrue(first.signals.isEmpty())
        val second = scores.record(speaker("alice"), "buy now!", t0.plusSeconds(20))
        assertEquals(1.0, second.signals[Signal.REPETITION])
        val third = scores.record(speaker("alice"), "Buy  now", t0.plusSeconds(40))
        assertEquals(2.0, third.signals[Signal.REPETITION])
        assertTrue(third.score > second.score)
    }

    @Test
    fun `commands to the bot are not repetition or flood`() {
        val commands =
            (1..8).map {
                scores.record(
                    speaker("alice"),
                    "sentiment",
                    t0.plusMillis(it * 100L),
                    addressed = true,
                )
            }
        assertTrue(commands.all { it.signals.isEmpty() }, "${commands.map { it.signals }}")
        // Nor do they make a later ordinary line look repeated
        val said = scores.record(speaker("alice"), "sentiment", t0.plusSeconds(30))
        assertTrue(said.signals.isEmpty(), "${said.signals}")
    }

    @Test
    fun `a command still counts for what it says`() {
        scores.record(speaker("bob"), "hi", t0)
        val aimed =
            scores.record(
                speaker("alice"),
                "tell bob you moron",
                t0.plusSeconds(1),
                addressed = true,
            )
        assertEquals(properties.weights.aimedHostility, aimed.signals[Signal.AIMED_HOSTILITY])
    }

    @Test
    fun `a flood counts`() {
        val lines =
            (1..8).map { scores.record(speaker("alice"), "line $it", t0.plusMillis(it * 100L)) }
        assertFalse(Signal.FLOOD in lines[5].signals)
        assertTrue(Signal.FLOOD in lines[6].signals)
    }

    @Test
    fun `crossing the threshold marks for one review`() {
        scores.record(speaker("bob"), "hi", t0)
        val once = scores.record(speaker("alice"), "bob you idiot", t0.plusSeconds(1))
        assertFalse(once.marked)
        val twice = scores.record(speaker("alice"), "bob shut up you moron", t0.plusSeconds(60))
        assertTrue(twice.marked)
        scores.record(speaker("carol"), "this damn build", t0.plusSeconds(61))

        val taken = scores.takeForReview(t0.plusSeconds(120))
        assertEquals(listOf("alice"), taken.map { it.speaker.sender })
        assertEquals(2, taken.single().signals[Signal.AIMED_HOSTILITY])
        assertTrue("bob you idiot" in taken.single().signalLines)
        assertTrue(scores.takeForReview(t0.plusSeconds(180)).isEmpty(), "taken once")
    }

    @Test
    fun `a strong line is kept over weak ones, however many came first`() {
        scores.record(speaker("bob"), "hi", t0)
        // Hundreds of flood lines fill the list of signal lines
        repeat(300) { scores.record(speaker("alice"), "flood $it", t0.plusMillis(it * 10L)) }
        scores.record(speaker("alice"), "bob shut up you moron", t0.plusSeconds(5))

        val lines = scores.takeForReview(t0.plusSeconds(10)).single().signalLines
        val insult = lines["bob shut up you moron"]
        assertTrue(insult != null && insult >= properties.weights.aimedHostility, "$insult")
        assertEquals(insult, lines.values.max())
    }
}
