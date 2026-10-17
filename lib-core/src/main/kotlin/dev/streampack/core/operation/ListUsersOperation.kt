/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TypedOperation
import java.util.UUID
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * `list users [role]`: every account that isn't erased, with its role, state and how many
 * identities it has linked (#247). For `ADMIN` and up; email addresses only for `SUPER_ADMIN`.
 */
@Component
class ListUsersOperation(
    private val userRepository: UserRepository,
    private val serviceBindingRepository: ServiceBindingRepository,
) : TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val words = payload.trim().lowercase().split(WHITESPACE)
        return words.size in 2..3 && words[0] == "list" && words[1] == "users"
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }
        val filter =
            payload.trim().split(WHITESPACE).getOrNull(2)?.let {
                UserCommands.parseRole(it)
                    ?: return OperationResult.Error(
                        "Unknown role '$it': use ${UserCommands.roles}",
                    )
            }
        val emails = hasRole(message, Role.SUPER_ADMIN)
        val links =
            serviceBindingRepository.countByUser().associate { (id, count) ->
                id as UUID to (count as Number).toInt()
            }
        val users =
            userRepository.findActiveOrSuspended().filter { filter == null || it.role == filter }
        if (users.isEmpty()) {
            return OperationResult.Success(
                if (filter == null) "No users"
                else "No users with the role ${UserCommands.role(filter)}",
            )
        }
        val lines = users.map { user ->
            val linked = links[user.id] ?: 0
            buildString {
                append("  ${UserCommands.name(user)}: ${UserCommands.role(user.role)}, ")
                append("${UserCommands.state(user)}, $linked linked")
                if (emails && user.email.isNotEmpty()) append(", ${user.email}")
            }
        }
        val count = if (users.size == 1) "1 user" else "${users.size} users"
        return OperationResult.Success("$count:\n" + lines.joinToString("\n"))
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
