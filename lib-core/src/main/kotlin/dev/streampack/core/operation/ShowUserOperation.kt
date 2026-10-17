/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * `show user <username>`: one account's role, state and linked identities, each written as `unlink
 * user` takes it (#247). For `ADMIN` and up; the email address only for `SUPER_ADMIN`.
 */
@Component
class ShowUserOperation(
    private val userRepository: UserRepository,
    private val serviceBindingRepository: ServiceBindingRepository,
) : TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val words = payload.trim().lowercase().split(WHITESPACE)
        return words.size == 3 && words[0] == "show" && words[1] == "user"
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }
        val username = payload.trim().split(WHITESPACE)[2]
        val user =
            userRepository.findByUsername(username)?.takeUnless { it.isErased() }
                ?: return OperationResult.Error("User not found")
        val bindings =
            serviceBindingRepository
                .findByUserIdOrderByProtocolAscServiceIdAscExternalIdentifierAsc(
                    user.id,
                )
        val text = buildString {
            append(UserCommands.name(user))
            append("\n  Role: ${UserCommands.role(user.role)}")
            append("\n  State: ${UserCommands.state(user)}")
            if (hasRole(message, Role.SUPER_ADMIN)) {
                val email =
                    when {
                        user.email.isEmpty() -> "none"
                        user.emailVerified -> "${user.email} (verified)"
                        else -> "${user.email} (not verified)"
                    }
                append("\n  Email: $email")
            }
            if (bindings.isEmpty()) {
                append("\n  Linked identities: none")
            } else {
                append("\n  Linked identities:")
                bindings.forEach { append("\n    ${UserCommands.identity(it)}") }
            }
        }
        return OperationResult.Success(text)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
