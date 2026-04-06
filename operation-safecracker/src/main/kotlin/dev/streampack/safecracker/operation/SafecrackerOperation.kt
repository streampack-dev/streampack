/* Joseph B. Ottinger (C)2026 */
package dev.streampack.safecracker.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.TypedOperation
import dev.streampack.safecracker.model.SafecrackerGameState
import dev.streampack.safecracker.service.SafecrackerTimerService
import java.time.Instant
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.convertValue

/** Competitive code-breaking game where players race to crack a 4-digit combination */
@Component
class SafecrackerOperation(
    private val stateService: ProvenanceStateService,
    private val timerService: SafecrackerTimerService,
) : TypedOperation<String>(String::class) {

    private val objectMapper = JacksonMappers.standard()

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "safecracker"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val cmd = payload.compress().lowercase()
        return cmd == "safecracker" || cmd.startsWith("safecracker ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance available.")
        val provenanceUri = provenance.encode()
        val compressed = payload.compress()
        val args = compressed.substringAfter("safecracker", "").trim()

        return when {
            args.isEmpty() -> startOrShowGame(provenanceUri)
            args.lowercase() == "concede" -> concede(provenanceUri)
            else -> guess(provenanceUri, args, message)
        }
    }

    private fun startOrShowGame(provenanceUri: String): OperationOutcome {
        val existing = stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
        if (existing != null) {
            val state = objectMapper.convertValue<SafecrackerGameState>(existing)
            val remaining = state.formatTimeRemaining(Instant.now())
            return OperationResult.Success(
                "Safecracker game in progress! ($remaining to go!)" +
                    " Use '{{ref:safecracker N N N N}}' to guess."
            )
        }

        val now = Instant.now()
        val state =
            SafecrackerGameState(
                combination = SafecrackerGameState.randomCombination(),
                startedAtEpochSecond = now.epochSecond,
            )
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )
        timerService.registerGame(provenanceUri, now)

        val pattern = ". ".repeat(SafecrackerGameState.COMBINATION_LENGTH).trim()
        return OperationResult.Success(
            "safecracker pattern: $pattern Good luck, players! (5:00 to go!)"
        )
    }

    private fun guess(provenanceUri: String, args: String, message: Message<*>): OperationOutcome {
        val existing =
            stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No game in progress. Use '{{ref:safecracker}}' to start a new game."
                )

        val digits =
            parseGuess(args)
                ?: return OperationResult.Error(
                    "Guess must be 4 digits (0-9) separated by spaces. Example: {{ref:safecracker 3 0 3 0}}"
                )

        val state = objectMapper.convertValue<SafecrackerGameState>(existing)
        val playerName = senderName(message)

        if (digits == state.combination) {
            stateService.clearState(provenanceUri, SafecrackerGameState.STATE_KEY)
            timerService.unregisterGame(provenanceUri)
            return OperationResult.Success(
                "$playerName has broken the code! Way to go, $playerName!"
            )
        }

        val feedback = state.feedback(digits)
        val remaining = state.formatTimeRemaining(Instant.now())
        return OperationResult.Success(
            "safecracker result for $playerName: $feedback ($remaining to go!)"
        )
    }

    private fun concede(provenanceUri: String): OperationOutcome {
        val existing =
            stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
                ?: return OperationResult.Success(
                    "No game in progress. Use '{{ref:safecracker}}' to start one."
                )

        val state = objectMapper.convertValue<SafecrackerGameState>(existing)
        stateService.clearState(provenanceUri, SafecrackerGameState.STATE_KEY)
        timerService.unregisterGame(provenanceUri)

        val answer = state.combination.joinToString(" ")
        return OperationResult.Success("You concede. The combination was: $answer")
    }

    /** Parses a guess string like "3 0 3 0" into a list of 4 digits, or null if invalid */
    private fun parseGuess(input: String): List<Int>? {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size != SafecrackerGameState.COMBINATION_LENGTH) return null
        return parts.map { part ->
            val digit = part.toIntOrNull() ?: return null
            if (digit !in 0..9) return null
            digit
        }
    }
}
