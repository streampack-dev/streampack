/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.LinkProtocolRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.core.service.UserRegistrationService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Binds a protocol identity to a user. Restricted to SUPER_ADMIN. */
@Component
class LinkProtocolOperation(
    private val userRepository: UserRepository,
    private val userRegistrationService: UserRegistrationService,
    private val identityProviders: List<IdentityProvider>,
) : TranslatingOperation<LinkProtocolRequest>(LinkProtocolRequest::class) {

    override val priority = 50

    override fun translate(payload: String, message: Message<*>): LinkProtocolRequest? {
        val text = payload.compress().lowercase()
        if (!text.startsWith("link user ")) return null
        val parts =
            payload.compress().removePrefix("link user ").removePrefix("Link user ").split(" ")
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

        return LinkProtocolRequest(
            username = username,
            protocol = protocol,
            serviceId = serviceId,
            externalIdentifier = externalIdentifier,
        )
    }

    override fun handle(payload: LinkProtocolRequest, message: Message<*>): OperationOutcome {
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

        // Validate via identity provider if one exists for this protocol
        val provider = identityProviders.find { it.protocol == payload.protocol }
        if (provider != null) {
            val resolution = provider.resolveIdentity(payload.serviceId, payload.externalIdentifier)
            if (resolution is IdentityResolution.Invalid) {
                return OperationResult.Error(resolution.reason)
            }
        }

        return try {
            userRegistrationService.linkProtocol(
                userId = targetUser.id,
                protocol = payload.protocol,
                serviceId = payload.serviceId,
                externalIdentifier = payload.externalIdentifier,
                metadata = payload.metadata,
            )
            OperationResult.Success(targetUser.toUserPrincipal())
        } catch (e: Exception) {
            logger.warn("Failed to link protocol for user {}: {}", payload.username, e.message)
            OperationResult.Error("Duplicate binding")
        }
    }
}
