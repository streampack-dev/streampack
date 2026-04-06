/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UnlinkProtocolRequest
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.core.service.UserRegistrationService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Removes a protocol identity binding from a user. Restricted to SUPER_ADMIN. */
@Component
class UnlinkProtocolOperation(
    private val userRepository: UserRepository,
    private val userRegistrationService: UserRegistrationService,
    private val serviceBindingRepository: ServiceBindingRepository,
) : TranslatingOperation<UnlinkProtocolRequest>(UnlinkProtocolRequest::class) {

    override val priority = 50

    override fun translate(payload: String, message: Message<*>): UnlinkProtocolRequest? {
        val text = payload.compress().lowercase()
        if (!text.startsWith("unlink user ")) return null
        val parts =
            payload.compress().removePrefix("unlink user ").removePrefix("Unlink user ").split(" ")
        if (parts.size < 4) return null

        val username = parts[0]
        val protocol =
            try {
                Protocol.valueOf(parts[1].uppercase())
            } catch (_: IllegalArgumentException) {
                return null
            }
        val serviceId = parts[2]
        val externalIdentifier = parts[3]

        return UnlinkProtocolRequest(
            username = username,
            protocol = protocol,
            serviceId = serviceId,
            externalIdentifier = externalIdentifier,
        )
    }

    override fun handle(payload: UnlinkProtocolRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        if (principal.role != Role.SUPER_ADMIN) {
            return OperationResult.Error("Insufficient privileges")
        }

        val targetUser =
            userRepository.findByUsername(payload.username)
                ?: return OperationResult.Error("User not found")

        val binding =
            serviceBindingRepository.resolve(
                payload.protocol,
                payload.serviceId,
                payload.externalIdentifier,
            ) ?: return OperationResult.Error("Binding not found")

        if (binding.user.id != targetUser.id) {
            return OperationResult.Error("Binding does not belong to user ${payload.username}")
        }

        return try {
            userRegistrationService.unlinkProtocol(
                protocol = payload.protocol,
                serviceId = payload.serviceId,
                externalIdentifier = payload.externalIdentifier,
            )
            OperationResult.Success(
                "Unlinked ${payload.externalIdentifier} from ${payload.username}"
            )
        } catch (e: Exception) {
            logger.warn("Failed to unlink protocol for user {}: {}", payload.username, e.message)
            OperationResult.Error("Failed to unlink: ${e.message}")
        }
    }
}
