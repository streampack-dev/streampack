/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.hangman.service.HangmanService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin commands for managing the hangman blocklist */
@Component
class HangmanAdminOperation(private val hangmanService: HangmanService) :
    TypedOperation<String>(String::class) {

    override val priority: Int = 49
    override val addressed: Boolean = true
    override val operationGroup: String = "hangman-admin"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val cmd = payload.compress().lowercase()
        return cmd.startsWith("hangman block ") || cmd.startsWith("hangman unblock ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val compressed = payload.compress().lowercase()
        return when {
            compressed.startsWith("hangman block ") -> {
                val word = compressed.substringAfter("hangman block ").trim()
                if (word.isBlank()) {
                    return OperationResult.Error("Usage: {{ref:hangman block <word>}}")
                }
                hangmanService.blockWord(word)
                OperationResult.Success("Blocked '$word' from hangman games.")
            }
            compressed.startsWith("hangman unblock ") -> {
                val word = compressed.substringAfter("hangman unblock ").trim()
                if (word.isBlank()) {
                    return OperationResult.Error("Usage: {{ref:hangman unblock <word>}}")
                }
                hangmanService.unblockWord(word)
                OperationResult.Success("Unblocked '$word' from hangman games.")
            }
            else -> null
        }
    }
}
