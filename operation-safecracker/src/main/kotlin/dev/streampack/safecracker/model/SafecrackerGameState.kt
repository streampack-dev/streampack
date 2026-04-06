/* Joseph B. Ottinger (C)2026 */
package dev.streampack.safecracker.model

import java.time.Duration
import java.time.Instant

/** Per-channel safecracker game state, serialized to JSONB via ProvenanceStateService */
data class SafecrackerGameState(val combination: List<Int>, val startedAtEpochSecond: Long) {

    /** Computes per-position feedback for a guess: = correct, > too high, < too low */
    fun feedback(guess: List<Int>): String =
        combination.zip(guess).joinToString(" ") { (target, actual) ->
            when {
                actual == target -> "="
                actual > target -> ">"
                else -> "<"
            }
        }

    /** Returns the time remaining in the game, or Duration.ZERO if expired */
    fun timeRemaining(now: Instant): Duration {
        val elapsed = Duration.between(Instant.ofEpochSecond(startedAtEpochSecond), now)
        val remaining = GAME_DURATION.minus(elapsed)
        return if (remaining.isNegative) Duration.ZERO else remaining
    }

    /** Formats the time remaining as M:SS */
    fun formatTimeRemaining(now: Instant): String {
        val remaining = timeRemaining(now)
        val totalSeconds = remaining.toSeconds()
        return "${totalSeconds / 60}:%02d".format(totalSeconds % 60)
    }

    companion object {
        const val STATE_KEY = "safecracker"
        const val COMBINATION_LENGTH = 4
        val GAME_DURATION: Duration = Duration.ofMinutes(5)
        val ANNOUNCEMENT_INTERVAL: Duration = Duration.ofSeconds(30)

        /** Generates a random 4-digit combination where each digit is 0-9 */
        fun randomCombination(): List<Int> = List(COMBINATION_LENGTH) { (0..9).random() }
    }
}
