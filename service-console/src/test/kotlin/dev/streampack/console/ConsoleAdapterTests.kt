/* Joseph B. Ottinger (C)2026 */
package dev.streampack.console

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/**
 * Tests the console adapter's superadmin resolution and message dispatch. The adapter itself is
 * disabled in tests (streampack.console.enabled is not set), so we test the components it depends
 * on directly.
 */
@SpringBootTest
@Transactional
class ConsoleAdapterTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var userRegistrationService: UserRegistrationService

    @Test
    fun `superadmin can be resolved from the database`() {
        userRegistrationService.register(
            username = "admin",
            email = "admin@test.com",
            displayName = "Test Admin",
            protocol = Protocol.HTTP,
            serviceId = "test",
            externalIdentifier = "admin",
            role = Role.SUPER_ADMIN,
        )

        val superAdmin =
            userRepository.findActiveByRole(Role.SUPER_ADMIN).firstOrNull()?.toUserPrincipal()
        assertNotNull(superAdmin)
    }

    @Test
    fun `returns null when no superadmin exists`() {
        val superAdmin =
            userRepository.findActiveByRole(Role.SUPER_ADMIN).firstOrNull()?.toUserPrincipal()
        assertNull(superAdmin)
    }

    @Test
    fun `message dispatched as superadmin flows through pipeline`() {
        val admin =
            userRegistrationService.register(
                username = "admin",
                email = "admin@test.com",
                displayName = "Test Admin",
                protocol = Protocol.HTTP,
                serviceId = "test",
                externalIdentifier = "admin",
                role = Role.SUPER_ADMIN,
            )

        val provenance =
            Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local", user = admin)

        val message =
            MessageBuilder.withPayload("this is not a known command")
                .setHeader(Provenance.HEADER, provenance)
                .build()

        val result = eventGateway.process(message)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
