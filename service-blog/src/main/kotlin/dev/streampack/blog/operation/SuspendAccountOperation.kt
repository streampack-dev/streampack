/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.SuspendAccountRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Suspends a user account: user cannot log in, content graph remains navigable for admin review */
@Component
class SuspendAccountOperation(private val userRepository: UserRepository) : Operation {
    override val priority = 50

    override fun canHandle(message: Message<*>): Boolean = message.payload is SuspendAccountRequest

    override fun execute(message: Message<*>): OperationResult {
        val request = message.payload as SuspendAccountRequest
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val targetUser =
            userRepository.findByUsername(request.username)
                ?: return OperationResult.Error("User not found")

        if (targetUser.role == Role.SUPER_ADMIN) {
            return OperationResult.Error("Cannot suspend a super admin")
        }

        if (!targetUser.isActive()) {
            return OperationResult.Error("User is not active")
        }

        userRepository.saveAndFlush(targetUser.copy(status = UserStatus.SUSPENDED))
        return OperationResult.Success("Account suspended")
    }
}
