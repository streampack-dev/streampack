/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.LoginResponse
import dev.streampack.core.model.Protocol
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.RefreshTokenService
import dev.streampack.core.service.UserRegistrationService
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Resolves a verified email to a User account and issues a JWT.
 *
 * Shared by both OTP and OIDC authentication paths. If no account exists for the email, one is
 * created automatically.
 */
@Service
class UserConvergenceService(
    private val userRepository: UserRepository,
    private val serviceBindingRepository: ServiceBindingRepository,
    private val userRegistrationService: UserRegistrationService,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    blogProperties: BlogProperties,
) {
    private val logger = LoggerFactory.getLogger(UserConvergenceService::class.java)
    private val serviceId = blogProperties.serviceId

    /** Finds or creates a user for the given email, then issues a JWT */
    @Transactional
    fun converge(email: String, displayName: String? = null): LoginResponse {
        val normalizedEmail = email.lowercase()
        var user = userRepository.findByEmail(normalizedEmail)

        if (user == null) {
            val username = deriveUsername(normalizedEmail)
            val name = displayName ?: username
            logger.info("Creating new account for {} (username: {})", normalizedEmail, username)
            val principal =
                userRegistrationService.register(
                    username = username,
                    email = normalizedEmail,
                    displayName = name,
                    protocol = Protocol.HTTP,
                    serviceId = serviceId,
                    externalIdentifier = normalizedEmail,
                )
            user = userRepository.findActiveById(principal.id)!!
        }

        if (!user.isActive()) {
            throw IllegalStateException("Account is deactivated")
        }

        /* Ensure email is verified and stamp login time */
        if (!user.emailVerified || user.lastLoginAt == null) {
            val updated = user.copy(emailVerified = true, lastLoginAt = Instant.now())
            user = userRepository.saveAndFlush(updated)
        } else {
            user = userRepository.saveAndFlush(user.copy(lastLoginAt = Instant.now()))
        }

        /* Ensure an HTTP service binding exists for this email */
        val existingBinding =
            serviceBindingRepository.resolve(Protocol.HTTP, serviceId, normalizedEmail)
        if (existingBinding == null) {
            userRegistrationService.linkProtocol(
                userId = user.id,
                protocol = Protocol.HTTP,
                serviceId = serviceId,
                externalIdentifier = normalizedEmail,
            )
        }

        val principal = user.toUserPrincipal()
        val token = jwtService.generateToken(principal)
        val refreshToken = refreshTokenService.issueToken(user.id)
        return LoginResponse(token, principal, refreshToken)
    }

    /** Derives a unique username from the email prefix, appending a numeric suffix on collision */
    private fun deriveUsername(email: String): String {
        val base = email.substringBefore('@')
        var candidate = base
        var suffix = 1
        while (userRepository.findByUsername(candidate) != null) {
            candidate = "$base$suffix"
            suffix++
        }
        return candidate
    }
}
