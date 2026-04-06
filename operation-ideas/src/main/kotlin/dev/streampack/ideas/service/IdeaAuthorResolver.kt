/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.service

import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.ideas.model.IdeaSessionState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Resolves idea submitters to internal users via ServiceBinding lookups */
@Component
class IdeaAuthorResolver(
    private val serviceBindingRepository: ServiceBindingRepository,
    private val userRepository: UserRepository,
) {

    private val logger = LoggerFactory.getLogger(IdeaAuthorResolver::class.java)

    fun resolve(state: IdeaSessionState, preferred: UserPrincipal? = null): UserPrincipal? {
        if (preferred != null && userRepository.findActiveById(preferred.id) != null) {
            return preferred
        }

        val provenance =
            try {
                Provenance.decode(state.sourceProvenance)
            } catch (ex: Exception) {
                logger.debug(
                    "Unable to decode provenance {}: {}",
                    state.sourceProvenance,
                    ex.message,
                )
                return null
            }

        val serviceId = provenance.serviceId
        if (serviceId.isNullOrBlank()) {
            return null
        }

        val externalIdentifier = state.submitterName.trim()
        if (externalIdentifier.isEmpty()) {
            return null
        }

        val binding =
            serviceBindingRepository.resolve(provenance.protocol, serviceId, externalIdentifier)
                ?: serviceBindingRepository.resolveIgnoreCase(
                    provenance.protocol,
                    serviceId,
                    externalIdentifier,
                )
                ?: return null

        val userId = binding.user.id
        val user = userRepository.findActiveById(userId) ?: return null
        return user.toUserPrincipal()
    }
}
