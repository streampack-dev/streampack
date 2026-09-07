/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.service.IdentityDescription
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.springframework.stereotype.Component

/**
 * Validates Mattermost identities against registered servers. The external identifier is the
 * Mattermost user id, which is what `posted` events carry and what survives username changes.
 */
@Component
class MattermostIdentityProvider(private val serverRepository: MattermostServerRepository) :
    IdentityProvider {

    override val protocol: Protocol = Protocol.MATTERMOST

    override fun resolveIdentity(
        serviceId: String,
        externalIdentifier: String,
    ): IdentityResolution {
        val server =
            serverRepository.findByNameAndDeletedFalse(serviceId)
                ?: return IdentityResolution.Invalid("Unknown Mattermost server: $serviceId")
        if (externalIdentifier.isBlank()) {
            return IdentityResolution.Invalid("Mattermost user id cannot be blank")
        }
        return IdentityResolution.Valid(
            serviceId = server.name,
            externalIdentifier = externalIdentifier.trim(),
        )
    }

    override fun describeIdentity(): IdentityDescription =
        IdentityDescription(
            protocol = Protocol.MATTERMOST,
            serviceIdLabel = "server",
            externalIdLabel = "user-id",
            availableServices = serverRepository.findByDeletedFalse().map { it.name },
        )
}
