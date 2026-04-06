/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import org.springframework.stereotype.Service

/** Resolves protocol-specific external identities to authenticated user principals */
@Service
class UserResolutionService(private val serviceBindingRepository: ServiceBindingRepository) {
    /**
     * Looks up a user by their protocol-specific identity, returning null if not found or deleted
     */
    fun resolve(protocol: Protocol, serviceId: String, externalIdentifier: String): UserPrincipal? {
        val binding =
            serviceBindingRepository.resolve(protocol, serviceId, externalIdentifier) ?: return null
        val user = binding.user
        if (!user.isActive()) return null
        return user.toUserPrincipal()
    }
}
