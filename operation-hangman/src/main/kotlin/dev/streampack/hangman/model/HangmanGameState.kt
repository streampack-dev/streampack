/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.model

import com.fasterxml.jackson.annotation.JsonIgnore

/** Per-channel hangman game state, serialized to JSONB via ProvenanceStateService */
data class HangmanGameState(
    val word: String,
    val guessedLetters: Set<Char> = emptySet(),
    val livesRemaining: Int = 6,
) {
    @get:JsonIgnore
    val maskedWord: String
        get() = word.map { if (it in guessedLetters) it else '_' }.joinToString(" ")

    @get:JsonIgnore
    val isWon: Boolean
        get() = word.all { it in guessedLetters }

    @get:JsonIgnore
    val isLost: Boolean
        get() = livesRemaining == 0

    @get:JsonIgnore
    val isOver: Boolean
        get() = isWon || isLost

    companion object {
        const val STATE_KEY = "hangman"
        const val MAX_LIVES = 6
    }
}
