/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.bootstrap

import dev.streampack.blog.config.BlogProperties
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** Creates a superadmin user on first boot if none exists, using a configured email address */
@Component
class SuperAdminBootstrap(
    private val userRepository: UserRepository,
    private val userRegistrationService: UserRegistrationService,
    @Value("\${ADMIN_EMAIL:}") private val adminEmail: String,
    blogProperties: BlogProperties,
) : ApplicationRunner {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(SuperAdminBootstrap::class.java)

    override fun run(args: ApplicationArguments) {
        if (userRepository.hasActiveWithRole(Role.SUPER_ADMIN)) {
            logger.debug("Superadmin already exists, skipping bootstrap")
            return
        }

        if (adminEmail.isBlank()) {
            logger.warn("No ADMIN_EMAIL configured, skipping superadmin bootstrap")
            return
        }

        val username = adminEmail.substringBefore('@')
        userRegistrationService.register(
            username = username,
            email = adminEmail,
            displayName = "System Administrator",
            protocol = Protocol.HTTP,
            serviceId = serviceId,
            externalIdentifier = adminEmail,
            role = Role.SUPER_ADMIN,
        )

        logger.info("Superadmin account created for {}, login via OTP", adminEmail)
    }
}
