/* Joseph B. Ottinger (C)2026 */
package dev.streampack.safecracker.model

import dev.streampack.core.json.JacksonMappers
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.convertValue

class SafecrackerGameStateTests {
    private val objectMapper = JacksonMappers.standard()

    @Test
    fun `feedback shows equals for correct digits`() {
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), Instant.now().epochSecond)
        assertEquals("= = = =", state.feedback(listOf(1, 2, 3, 4)))
    }

    @Test
    fun `feedback shows greater-than for digits too high`() {
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), Instant.now().epochSecond)
        assertEquals("> > > >", state.feedback(listOf(5, 6, 7, 8)))
    }

    @Test
    fun `feedback shows less-than for digits too low`() {
        val state = SafecrackerGameState(listOf(5, 6, 7, 8), Instant.now().epochSecond)
        assertEquals("< < < <", state.feedback(listOf(1, 2, 3, 4)))
    }

    @Test
    fun `feedback shows mixed results`() {
        val state = SafecrackerGameState(listOf(3, 5, 2, 7), Instant.now().epochSecond)
        assertEquals("= > < =", state.feedback(listOf(3, 8, 0, 7)))
    }

    @Test
    fun `feedback with zeros`() {
        val state = SafecrackerGameState(listOf(0, 0, 0, 0), Instant.now().epochSecond)
        assertEquals("= > > >", state.feedback(listOf(0, 5, 5, 5)))
    }

    @Test
    fun `feedback with nines`() {
        val state = SafecrackerGameState(listOf(9, 9, 9, 9), Instant.now().epochSecond)
        assertEquals("< < < =", state.feedback(listOf(0, 0, 0, 9)))
    }

    @Test
    fun `time remaining when game is fresh`() {
        val now = Instant.now()
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), now.epochSecond)
        val remaining = state.timeRemaining(now)
        assertTrue(remaining >= Duration.ofMinutes(4).plusSeconds(59))
    }

    @Test
    fun `time remaining after two minutes`() {
        val start = Instant.now().minus(Duration.ofMinutes(2))
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), start.epochSecond)
        val remaining = state.timeRemaining(Instant.now())
        assertTrue(remaining <= Duration.ofMinutes(3).plusSeconds(1))
        assertTrue(remaining >= Duration.ofMinutes(2).plusSeconds(59))
    }

    @Test
    fun `time remaining returns zero when expired`() {
        val start = Instant.now().minus(Duration.ofMinutes(6))
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), start.epochSecond)
        assertEquals(Duration.ZERO, state.timeRemaining(Instant.now()))
    }

    @Test
    fun `format time remaining shows minutes and seconds`() {
        val fixedNow = Instant.ofEpochSecond(1000000)
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), fixedNow.epochSecond)
        val formatted = state.formatTimeRemaining(fixedNow)
        assertEquals("5:00", formatted)
    }

    @Test
    fun `format time remaining after partial elapsed`() {
        val fixedStart = Instant.ofEpochSecond(1000000)
        val fixedNow = fixedStart.plus(Duration.ofMinutes(3).plusSeconds(30))
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), fixedStart.epochSecond)
        val formatted = state.formatTimeRemaining(fixedNow)
        assertEquals("1:30", formatted)
    }

    @Test
    fun `round-trip serialization preserves state`() {
        val state = SafecrackerGameState(listOf(3, 5, 2, 7), 1709078400L)
        val map = objectMapper.convertValue<Map<String, Any>>(state)
        val restored = objectMapper.convertValue<SafecrackerGameState>(map)
        assertEquals(state.combination, restored.combination)
        assertEquals(state.startedAtEpochSecond, restored.startedAtEpochSecond)
    }

    @Test
    fun `random combination generates four digits`() {
        val combo = SafecrackerGameState.randomCombination()
        assertEquals(4, combo.size)
        combo.forEach { assertTrue(it in 0..9) }
    }
}
