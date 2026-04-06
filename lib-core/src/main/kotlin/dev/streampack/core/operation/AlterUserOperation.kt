/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.AlterUserRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TranslatingOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Modifies a user's fields. Supports both typed requests (REST) and text commands (IRC/console).
 *
 * Text syntax: `alter user <username> <field> <value>`
 *
 * Fields: role, email, displayname, username
 *
 * Authorization hierarchy:
 * - ADMIN: can modify users with role < ADMIN, can set role to values < ADMIN
 * - SUPER_ADMIN: can modify any user, can set role to any value, cannot change own role
 * - Neither can change their own role
 */
@Component
class AlterUserOperation(private val userRepository: UserRepository) :
    TranslatingOperation<AlterUserRequest>(AlterUserRequest::class) {

    override val priority = 50

    override fun translate(payload: String, message: Message<*>): AlterUserRequest? {
        val text = payload.compress()
        if (!text.lowercase().startsWith("alter user ")) return null
        val parts =
            text.removePrefix("alter user ").removePrefix("Alter user ").split(" ", limit = 3)
        if (parts.size < 3) return null

        val username = parts[0]
        val field = parts[1].lowercase()
        val value = parts[2]

        return when (field) {
            "role" -> {
                val role =
                    try {
                        Role.valueOf(value.uppercase().replace("-", "_"))
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                AlterUserRequest(username = username, role = role)
            }
            "email" -> AlterUserRequest(username = username, email = value)
            "displayname" -> AlterUserRequest(username = username, displayName = value)
            "username" -> AlterUserRequest(username = username, newUsername = value)
            else -> null
        }
    }

    override fun handle(payload: AlterUserRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val targetUser =
            userRepository.findByUsername(payload.username)
                ?: return OperationResult.Error("User not found")

        // Prevent self-role-change
        if (payload.role != null && targetUser.username == principal.username) {
            return OperationResult.Error("Cannot change own role")
        }

        // ADMIN authorization checks
        if (principal.role == Role.ADMIN) {
            if (targetUser.role >= Role.ADMIN) {
                return OperationResult.Error("Insufficient privileges")
            }
            if (payload.role != null && payload.role >= Role.ADMIN) {
                return OperationResult.Error("Insufficient privileges")
            }
        }

        return try {
            val updated =
                targetUser.copy(
                    username = payload.newUsername ?: targetUser.username,
                    email = payload.email ?: targetUser.email,
                    displayName = payload.displayName ?: targetUser.displayName,
                    role = payload.role ?: targetUser.role,
                )
            val saved = userRepository.saveAndFlush(updated)
            OperationResult.Success(saved.toUserPrincipal())
        } catch (e: Exception) {
            logger.warn("Failed to alter user {}: {}", payload.username, e.message)
            OperationResult.Error("Username already exists")
        }
    }
}
