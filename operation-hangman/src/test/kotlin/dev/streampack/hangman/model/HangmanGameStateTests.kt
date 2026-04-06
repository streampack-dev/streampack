/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.model

import dev.streampack.core.json.JacksonMappers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue

class HangmanGameStateTests {
    private val objectMapper: JsonMapper = JacksonMappers.standard()

    @Test
    fun `masked word hides unguessed letters`() {
        val state = HangmanGameState(word = "hangman")
        assertEquals("_ _ _ _ _ _ _", state.maskedWord)
    }

    @Test
    fun `masked word reveals guessed letters`() {
        val state = HangmanGameState(word = "hangman", guessedLetters = setOf('h', 'a', 'n'))
        assertEquals("h a n _ _ a n", state.maskedWord)
    }

    @Test
    fun `masked word fully revealed when all letters guessed`() {
        val state = HangmanGameState(word = "cat", guessedLetters = setOf('c', 'a', 't'))
        assertEquals("c a t", state.maskedWord)
    }

    @Test
    fun `isWon when all letters guessed`() {
        val state = HangmanGameState(word = "cat", guessedLetters = setOf('c', 'a', 't'))
        assertTrue(state.isWon)
    }

    @Test
    fun `isWon false when letters missing`() {
        val state = HangmanGameState(word = "cat", guessedLetters = setOf('c', 'a'))
        assertFalse(state.isWon)
    }

    @Test
    fun `isLost when lives are zero`() {
        val state = HangmanGameState(word = "cat", livesRemaining = 0)
        assertTrue(state.isLost)
    }

    @Test
    fun `isLost false when lives remain`() {
        val state = HangmanGameState(word = "cat", livesRemaining = 3)
        assertFalse(state.isLost)
    }

    @Test
    fun `isOver when won`() {
        val state = HangmanGameState(word = "cat", guessedLetters = setOf('c', 'a', 't'))
        assertTrue(state.isOver)
    }

    @Test
    fun `isOver when lost`() {
        val state = HangmanGameState(word = "cat", livesRemaining = 0)
        assertTrue(state.isOver)
    }

    @Test
    fun `isOver false when game still active`() {
        val state = HangmanGameState(word = "cat", guessedLetters = setOf('c'), livesRemaining = 4)
        assertFalse(state.isOver)
    }

    @Test
    fun `round-trip serialization preserves state`() {
        val state =
            HangmanGameState(
                word = "hangman",
                guessedLetters = setOf('h', 'a', 'x'),
                livesRemaining = 5,
            )
        val map = objectMapper.convertValue<Map<String, Any>>(state)
        val restored = objectMapper.convertValue<HangmanGameState>(map)
        assertEquals(state.word, restored.word)
        assertEquals(state.guessedLetters, restored.guessedLetters)
        assertEquals(state.livesRemaining, restored.livesRemaining)
    }

    @Test
    fun `round-trip serialization with empty guessed letters`() {
        val state = HangmanGameState(word = "test")
        val map = objectMapper.convertValue<Map<String, Any>>(state)
        val restored = objectMapper.convertValue<HangmanGameState>(map)
        assertEquals(state.word, restored.word)
        assertEquals(emptySet<Char>(), restored.guessedLetters)
        assertEquals(6, restored.livesRemaining)
    }

    @Test
    fun `duplicate letters in word handled correctly`() {
        val state = HangmanGameState(word = "banana", guessedLetters = setOf('a'))
        assertEquals("_ a _ a _ a", state.maskedWord)
        assertFalse(state.isWon)
    }
}
