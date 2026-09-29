/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.EditProfileRequest
import dev.streampack.core.model.EmailAddresses
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Self-service profile editing for authenticated users: their display name. */
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

        // Email is the sign-in identity, and nothing here proves the new address is the user's,
        // so it can't be changed this way. Sending the current address back is not a change.
        if (
            payload.email != null &&
                EmailAddresses.normalize(payload.email) != EmailAddresses.normalize(user.email)
        ) {
            return OperationResult.Error("Email address can't be changed here")
        }
        val displayName = payload.displayName?.trim()
        if (displayName != null && displayName.isEmpty()) {
            return OperationResult.Error("Display name can't be blank")
        }

        val updated = user.copy(displayName = displayName ?: user.displayName)
        val saved = userRepository.saveAndFlush(updated)
        return OperationResult.Success(saved.toUserPrincipal())
    }
}
