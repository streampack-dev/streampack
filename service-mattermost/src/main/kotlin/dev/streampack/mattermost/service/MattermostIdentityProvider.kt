/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.service.IdentityDescription
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Validates Mattermost identities against registered servers. The external identifier stored is the
 * Mattermost user id, which is what `posted` events carry and what survives username changes. A
 * username (`alice` or `@alice`) is accepted too, and looked up on the server's live connection, so
 * nobody has to find their id (#245).
 */
@Component
class MattermostIdentityProvider(
    private val serverRepository: MattermostServerRepository,
    private val connectionManager: ObjectProvider<MattermostConnectionManager>,
) : IdentityProvider {

    override val protocol: Protocol = Protocol.MATTERMOST

    override fun resolveIdentity(
        serviceId: String,
        externalIdentifier: String,
    ): IdentityResolution {
        val server =
            serverRepository.findByNameAndDeletedFalse(serviceId)
                ?: return IdentityResolution.Invalid("Unknown Mattermost server: $serviceId")
        val given = externalIdentifier.trim()
        if (given.isEmpty()) {
            return IdentityResolution.Invalid("Mattermost user id cannot be blank")
        }
        if (USER_ID.matches(given)) {
            return IdentityResolution.Valid(serviceId = server.name, externalIdentifier = given)
        }

        val username = given.removePrefix("@")
        val adapter =
            connectionManager.ifAvailable?.getAdapter(server.name)?.takeIf { it.isConnected() }
                ?: return IdentityResolution.Invalid(
                    "'${server.name}' is not connected; give the user id, or connect first",
                )
        val user =
            adapter.lookupUser(username)?.takeIf { it.id.isNotBlank() }
                ?: return IdentityResolution.Invalid(
                    "No Mattermost user '$username' on '${server.name}'",
                )
        return IdentityResolution.Valid(serviceId = server.name, externalIdentifier = user.id)
    }

    override fun describeIdentity(): IdentityDescription =
        IdentityDescription(
            protocol = Protocol.MATTERMOST,
            serviceIdLabel = "server",
            externalIdLabel = "username-or-user-id",
            availableServices = serverRepository.findByDeletedFalse().map { it.name },
        )

    private companion object {
        /** Mattermost's ids: 26 lower-case letters and digits; `@` in front always means a name */
        val USER_ID = Regex("[a-z0-9]{26}")
    }
}
