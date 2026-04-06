/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.service.IdentityDescription
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.irc.repository.IrcNetworkRepository
import org.springframework.stereotype.Component

/** Validates IRC identities against registered networks */
@Component
class IrcIdentityProvider(private val networkRepository: IrcNetworkRepository) : IdentityProvider {

    override val protocol: Protocol = Protocol.IRC

    override fun resolveIdentity(
        serviceId: String,
        externalIdentifier: String,
    ): IdentityResolution {
        val network =
            networkRepository.findByNameAndDeletedFalse(serviceId)
                ?: return IdentityResolution.Invalid("Unknown IRC network: $serviceId")

        if (externalIdentifier.isBlank()) {
            return IdentityResolution.Invalid("IRC hostmask cannot be blank")
        }

        val atIndex = externalIdentifier.indexOf('@')
        if (atIndex < 0) {
            return IdentityResolution.Invalid(
                "IRC identifier must be in ident@host format, got: $externalIdentifier"
            )
        }
        val identPart = externalIdentifier.substring(0, atIndex)
        val hostPart = externalIdentifier.substring(atIndex + 1)
        if (identPart.isBlank()) {
            return IdentityResolution.Invalid("IRC hostmask ident part cannot be blank")
        }
        if (hostPart.isBlank()) {
            return IdentityResolution.Invalid("IRC hostmask host part cannot be blank")
        }

        return IdentityResolution.Valid(
            serviceId = network.name,
            externalIdentifier = externalIdentifier,
        )
    }

    override fun describeIdentity(): IdentityDescription =
        IdentityDescription(
            protocol = Protocol.IRC,
            serviceIdLabel = "network",
            externalIdLabel = "hostmask",
            availableServices = networkRepository.findByDeletedFalse().map { it.name },
        )
}
