/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * What an account may do now, from the user store rather than a credential (#115). A credential
 * says who sent a request and what they could do when it was issued; their role or status may have
 * changed since. Any authenticated command transport can use this; the web console does, at the
 * operation chain's entry.
 */
@Service
class LiveAuthorityService(private val userRepository: UserRepository) {
    /** [principal] as the account stands now, or null if it's no longer active. */
    fun current(principal: UserPrincipal): UserPrincipal? =
        userRepository.findActiveById(principal.id)?.toUserPrincipal()
}
