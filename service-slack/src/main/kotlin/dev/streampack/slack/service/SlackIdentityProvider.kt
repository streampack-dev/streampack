/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.service.IdentityDescription
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.slack.repository.SlackWorkspaceRepository
import org.springframework.stereotype.Component

/** Validates Slack identities against registered workspaces */
@Component
class SlackIdentityProvider(private val workspaceRepository: SlackWorkspaceRepository) :
    IdentityProvider {

    override val protocol: Protocol = Protocol.SLACK

    override fun resolveIdentity(
        serviceId: String,
        externalIdentifier: String,
    ): IdentityResolution {
        val workspace =
            workspaceRepository.findByNameAndDeletedFalse(serviceId)
                ?: return IdentityResolution.Invalid("Unknown Slack workspace: $serviceId")

        if (externalIdentifier.isBlank()) {
            return IdentityResolution.Invalid("Slack user ID cannot be blank")
        }

        return IdentityResolution.Valid(
            serviceId = workspace.name,
            externalIdentifier = externalIdentifier,
        )
    }

    override fun describeIdentity(): IdentityDescription =
        IdentityDescription(
            protocol = Protocol.SLACK,
            serviceIdLabel = "workspace",
            externalIdLabel = "user-id",
            availableServices = workspaceRepository.findByDeletedFalse().map { it.name },
        )
}
