/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.TypedOperation
import dev.streampack.hangman.model.HangmanGameState
import dev.streampack.hangman.service.HangmanService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.convertValue

/** Word guessing game where players reveal letters one at a time before running out of lives */
@Component
class HangmanOperation(
    private val stateService: ProvenanceStateService,
    private val hangmanService: HangmanService,
) : TypedOperation<String>(String::class) {

    private val objectMapper = JacksonMappers.standard()

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "hangman"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val cmd = payload.compress().lowercase()
        return cmd == "hangman" || cmd.startsWith("hangman ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance available.")
        val provenanceUri = provenance.encode()
        val compressed = payload.compress()
        val args = compressed.substringAfter("hangman", "").trim()

        val playerName = senderName(message)

        return when {
            args.isEmpty() -> startOrShowGame(provenanceUri)
            args.lowercase() == "concede" -> concede(provenanceUri, playerName)
            args.lowercase().startsWith("solve ") ->
                solve(provenanceUri, args.substringAfter("solve ").trim(), playerName)

            args.lowercase().startsWith("block ") -> null
            args.lowercase().startsWith("unblock ") -> null
            args.length == 1 && (args[0] in 'a'..'z' || args[0] in 'A'..'Z') ->
                guessLetter(provenanceUri, args[0].lowercaseChar(), playerName)

            args.length == 1 ->
                OperationResult.Error("All the letters I use are in the English alphabet!")
            else ->
                OperationResult.Error(
                    "Unknown hangman command. Use '{{ref:hangman}}' to start, " +
                        "'{{ref:hangman <letter>}}' to guess, or '{{ref:hangman solve <word>}}' to solve."
                )
        }
    }

    private fun startOrShowGame(provenanceUri: String): OperationOutcome {
        val existing = stateService.getState(provenanceUri, HangmanGameState.STATE_KEY)
        if (existing != null) {
            val state = objectMapper.convertValue<HangmanGameState>(existing)
            return OperationResult.Success(formatState(state))
        }

        val word = hangmanService.selectWord()
        val state = HangmanGameState(word = word)
        stateService.setState(
            provenanceUri,
            HangmanGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )
        return OperationResult.Success(
            "Hangman: ${state.maskedWord} (${state.livesRemaining}/${HangmanGameState.MAX_LIVES} lives)" +
                " -- Use '{{ref:hangman <letter>}}' to guess, '{{ref:hangman solve <word>}}' to solve."
        )
    }

    private fun guessLetter(
        provenanceUri: String,
        letter: Char,
        playerName: String,
    ): OperationOutcome {
        val existing =
            stateService.getState(provenanceUri, HangmanGameState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No game in progress. Use '{{ref:hangman}}' to start a new game."
                )

        val state = objectMapper.convertValue<HangmanGameState>(existing)

        if (letter in state.guessedLetters) {
            return OperationResult.Success("You already guessed '$letter'. ${formatState(state)}")
        }

        val correct = letter in state.word
        val updated =
            if (correct) {
                state.copy(guessedLetters = state.guessedLetters + letter)
            } else {
                state.copy(
                    guessedLetters = state.guessedLetters + letter,
                    livesRemaining = state.livesRemaining - 1,
                )
            }

        if (updated.isWon) {
            stateService.clearState(provenanceUri, HangmanGameState.STATE_KEY)
            return OperationResult.Success(
                "$playerName got it! The word was '${state.word}'. Use '{{ref:hangman}}' to play again."
            )
        }

        if (updated.isLost) {
            stateService.clearState(provenanceUri, HangmanGameState.STATE_KEY)
            return OperationResult.Success(
                "No lives left! The word was '${state.word}'. Use '{{ref:hangman}}' to start over."
            )
        }

        stateService.setState(
            provenanceUri,
            HangmanGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(updated),
        )

        return if (correct) {
            OperationResult.Success(
                "Hangman: ${updated.maskedWord} " +
                    "(${updated.livesRemaining}/${HangmanGameState.MAX_LIVES} lives, " +
                    "guessed: ${formatGuessed(updated)}) -- '$letter' is in the word!"
            )
        } else {
            OperationResult.Success(
                "Hangman: ${updated.maskedWord} " +
                    "(${updated.livesRemaining}/${HangmanGameState.MAX_LIVES} lives, " +
                    "guessed: ${formatGuessed(updated)}) -- No '$letter'. " +
                    "${updated.livesRemaining} ${
                            if (updated.livesRemaining != 1) {
                                "lives"
                            } else {
                                "life"
                            }
                        } left."
            )
        }
    }

    private fun solve(provenanceUri: String, guess: String, playerName: String): OperationOutcome {
        val existing =
            stateService.getState(provenanceUri, HangmanGameState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No game in progress. Use '{{ref:hangman}}' to start a new game."
                )

        val state = objectMapper.convertValue<HangmanGameState>(existing)

        if (guess.lowercase() == state.word) {
            stateService.clearState(provenanceUri, HangmanGameState.STATE_KEY)
            val reaction = solveReaction(state.guessedLetters.size, playerName)
            return OperationResult.Success(
                "$reaction The word was '${state.word}'. Use '{{ref:hangman}}' to play again."
            )
        }

        val updated = state.copy(livesRemaining = state.livesRemaining - 1)
        if (updated.isLost) {
            stateService.clearState(provenanceUri, HangmanGameState.STATE_KEY)
            return OperationResult.Success(
                "No lives left! The word was '${state.word}'. Use '{{ref:hangman}}' to start over."
            )
        }

        stateService.setState(
            provenanceUri,
            HangmanGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(updated),
        )
        return OperationResult.Success(
            "'${guess.lowercase()}' is not the word. ${formatState(updated)}"
        )
    }

    private fun concede(provenanceUri: String, playerName: String): OperationOutcome {
        val existing =
            stateService.getState(provenanceUri, HangmanGameState.STATE_KEY)
                ?: return OperationResult.Success(
                    "No game in progress. Use '{{ref:hangman}}' to start one."
                )

        val state = objectMapper.convertValue<HangmanGameState>(existing)
        stateService.clearState(provenanceUri, HangmanGameState.STATE_KEY)
        return OperationResult.Success(
            "$playerName concedes. The word was '${state.word}'. Use '{{ref:hangman}}' for a new game."
        )
    }

    private fun solveReaction(lettersGuessed: Int, playerName: String): String =
        when (lettersGuessed) {
            0 -> ZERO_GUESS_REACTIONS.random()
            1 -> ONE_GUESS_REACTIONS.random()
            else -> "$playerName got it!"
        }

    private fun formatState(state: HangmanGameState): String {
        val guessed =
            if (state.guessedLetters.isNotEmpty()) {
                ", guessed: ${formatGuessed(state)}"
            } else {
                ""
            }
        return "Hangman: ${state.maskedWord} " +
            "(${state.livesRemaining}/${HangmanGameState.MAX_LIVES} lives$guessed)"
    }

    private fun formatGuessed(state: HangmanGameState): String =
        state.guessedLetters.sorted().joinToString(", ")

    companion object {
        val ZERO_GUESS_REACTIONS =
            listOf(
                "Wait, what... how? That's amazing! ... or suspicious.",
                "Wait, you nailed it without guessing a single letter? I'm not saying you cheated, but if I had eyebrows...",
                "Zero letters guessed and you nailed it? That's either genius or espionage.",
                "Impressive! Or suspicious. Let's go with impressively suspicious.",
                "You solved it cold? I'll just be over here checking the server logs.",
                "No guesses and a perfect solve? I want to believe.",
                "Either you're psychic or you've been scanning a database.",
                "Solved with no letters? You're putting a hangman out of business, mate.",
            )

        val ONE_GUESS_REACTIONS =
            listOf(
                "Nicely done! A single guess and you sussed it!",
                "One letter and you cracked it? That's genuinely impressive!",
                "Solved on a single guess? Well played, well played indeed.",
                "One letter was all you needed? I bow to thee, sirrah.",
                "A single letter and you saw the whole word? Nicely done!",
                "One guess to solve it - you make this look easy.",
                "You only needed one letter? You might want to play the lottery more.",
            )
    }
}
