/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.ServiceBinding
import dev.streampack.core.entity.User
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Handles user creation and protocol identity linking */
@Service
class UserRegistrationService(
    private val userRepository: UserRepository,
    private val serviceBindingRepository: ServiceBindingRepository,
) {

    /** Creates a user with an initial service binding. Role defaults to USER. */
    @Transactional
    fun register(
        username: String,
        email: String,
        displayName: String,
        protocol: Protocol,
        serviceId: String,
        externalIdentifier: String,
        metadata: Map<String, Any> = emptyMap(),
        role: Role = Role.USER,
    ): UserPrincipal {
        val user =
            userRepository.saveAndFlush(
                User(username = username, email = email, displayName = displayName, role = role)
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = protocol,
                serviceId = serviceId,
                externalIdentifier = externalIdentifier,
                metadata = metadata,
            )
        )
        return user.toUserPrincipal()
    }

    /** Creates a guest user from a protocol identity, using externalIdentifier as username */
    @Transactional
    fun registerGuest(
        protocol: Protocol,
        serviceId: String,
        externalIdentifier: String,
    ): UserPrincipal {
        val user =
            userRepository.saveAndFlush(
                User(
                    username = externalIdentifier,
                    displayName = externalIdentifier,
                    role = Role.GUEST,
                )
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = protocol,
                serviceId = serviceId,
                externalIdentifier = externalIdentifier,
            )
        )
        return user.toUserPrincipal()
    }

    /** Creates a user pre-verified by an admin, with no initial service binding */
    @Transactional
    fun createUser(
        username: String,
        email: String,
        displayName: String,
        role: Role = Role.USER,
    ): UserPrincipal {
        val user =
            userRepository.saveAndFlush(
                User(
                    username = username,
                    email = email,
                    displayName = displayName,
                    role = role,
                    emailVerified = true,
                )
            )
        return user.toUserPrincipal()
    }

    /** Removes a protocol identity binding */
    @Transactional
    fun unlinkProtocol(protocol: Protocol, serviceId: String, externalIdentifier: String) {
        val binding =
            serviceBindingRepository.resolve(protocol, serviceId, externalIdentifier)
                ?: throw IllegalArgumentException(
                    "No binding found for $protocol/$serviceId/$externalIdentifier"
                )
        serviceBindingRepository.delete(binding)
    }

    /** Links an additional protocol identity to an existing user */
    @Transactional
    fun linkProtocol(
        userId: UUID,
        protocol: Protocol,
        serviceId: String,
        externalIdentifier: String,
        metadata: Map<String, Any> = emptyMap(),
    ) {
        val user =
            userRepository.findById(userId).orElseThrow {
                IllegalArgumentException("User not found: $userId")
            }
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = protocol,
                serviceId = serviceId,
                externalIdentifier = externalIdentifier,
                metadata = metadata,
            )
        )
    }
}
