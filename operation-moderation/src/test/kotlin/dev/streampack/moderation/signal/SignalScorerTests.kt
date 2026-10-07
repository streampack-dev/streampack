/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.signal

import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.model.Signal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What one line scores on its own (#150): behaviour, not vocabulary. */
class SignalScorerTests {
    private val properties = ModerationProperties(blockedHosts = listOf("bad.example"))
    private val scorer = SignalScorer(properties)
    private val threshold = properties.threshold

    private fun score(line: String, others: Set<String> = setOf("bob")) =
        scorer.score(line, "alice", others)

    @Test
    fun `swearing at code scores low`() {
        val signals = score("this fucking build is shit again")
        assertEquals(mapOf(Signal.PROFANITY to properties.weights.profanity), signals)
        assertTrue(signals.values.sum() < 1.0)
        assertTrue(score("ugh, I'm such an idiot, forgot the semicolon").values.sum() <= 1.0)
    }

    @Test
    fun `the same words aimed at a mentioned user score high`() {
        val atCode = score("this fucking build is shit again").values.sum()
        val atBob = score("bob: this fucking build is shit, like you").values.sum()
        assertTrue(atBob >= 10 * atCode, "aimed $atBob vs. at code $atCode")
        assertEquals(
            setOf(Signal.AIMED_HOSTILITY),
            score("bob, you're a fucking idiot").keys,
        )
        assertEquals(setOf(Signal.AIMED_HOSTILITY), score("@carol shit take, moron").keys)
    }

    @Test
    fun `second person on its own aims it`() {
        assertEquals(setOf(Signal.AIMED_HOSTILITY), score("fuck you").keys, "no one named")
        assertEquals(
            setOf(Signal.PROFANITY),
            score("damn this compiler bug, can you believe it").keys,
            "a 'you' far from the swearing isn't aimed",
        )
    }

    @Test
    fun `a slur scores high on its own`() {
        val signals = score("what a f4ggot")
        assertTrue(Signal.SLUR in signals.keys)
        assertTrue(signals.values.sum() >= threshold)
    }

    @Test
    fun `threats need a target`() {
        assertEquals(setOf(Signal.THREAT), score("i'm going to kill you").keys)
        assertEquals(setOf(Signal.THREAT), score("kys").keys)
        assertTrue(score("i'll kill this process and restart it").isEmpty())
        assertTrue(score("I'll find your bug tomorrow").isEmpty())
    }

    @Test
    fun `personal details count more alongside someone's name`() {
        assertEquals(
            properties.weights.personalDetails,
            score("mail me at alice@example.com").values.sum(),
        )
        assertEquals(
            properties.weights.personalDetailsAboutSomeone,
            score("bob lives at 1234 Elm Grove Street, call him on 555-123-4567").values.sum(),
        )
        assertTrue(score("released 2026-10-07 15:09:57 as 1.2.3").isEmpty())
    }

    @Test
    fun `links to blocked hosts count`() {
        assertEquals(setOf(Signal.BLOCKED_LINK), score("see https://www.bad.example/x").keys)
        assertTrue(score("see https://notbad.example/x").isEmpty())
    }

    @Test
    fun `words are words`() {
        assertTrue(score("the piston and the assessment").isEmpty())
        assertEquals(setOf(Signal.PROFANITY), score("fuuuuuck").keys)
        assertTrue(score("hello there").isEmpty())
    }
}
