/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.extensions.compress
import com.enigmastation.streampack.core.model.CreateUserRequest
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.service.TranslatingOperation
import com.enigmastation.streampack.core.service.UserRegistrationService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Provisions a new user account. Restricted to SUPER_ADMIN. */
@Component
class CreateUserOperation(private val userRegistrationService: UserRegistrationService) :
    TranslatingOperation<CreateUserRequest>(CreateUserRequest::class) {

    override val priority = 50

    override fun translate(payload: String, message: Message<*>): CreateUserRequest? {
        val text = payload.compress().lowercase()
        if (!text.startsWith("create user ")) return null
        val parts =
            payload.compress().removePrefix("create user ").removePrefix("Create user ").split(" ")
        if (parts.size < 3) return null

        val username = parts[0]
        val email = parts[1]
        val displayName = parts[2]
        val role =
            if (parts.size > 3) {
                try {
                    Role.valueOf(parts[3].uppercase().replace("-", "_"))
                } catch (_: IllegalArgumentException) {
                    Role.USER
                }
            } else {
                Role.USER
            }

        return CreateUserRequest(
            username = username,
            email = email,
            displayName = displayName,
            role = role,
        )
    }

    override fun handle(payload: CreateUserRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        if (principal.role != Role.SUPER_ADMIN) {
            return OperationResult.Error("Insufficient privileges")
        }

        return try {
            val created =
                userRegistrationService.createUser(
                    username = payload.username,
                    email = payload.email,
                    displayName = payload.displayName,
                    role = payload.role,
                )
            OperationResult.Success(created)
        } catch (e: Exception) {
            logger.warn("Failed to create user {}: {}", payload.username, e.message)
            OperationResult.Error("Username already exists")
        }
    }
}
