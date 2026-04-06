/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.EditProfileRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Self-service profile editing for authenticated users */
@Component
class EditProfileOperation(private val userRepository: UserRepository) :
    TypedOperation<EditProfileRequest>(EditProfileRequest::class) {

    override val priority = 50

    override fun handle(payload: EditProfileRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        val user =
            userRepository.findByUsername(principal.username)
                ?: return OperationResult.Error("User not found")

        val updated =
            user.copy(
                displayName = payload.displayName ?: user.displayName,
                email = payload.email ?: user.email,
            )
        val saved = userRepository.saveAndFlush(updated)
        return OperationResult.Success(saved.toUserPrincipal())
    }
}
