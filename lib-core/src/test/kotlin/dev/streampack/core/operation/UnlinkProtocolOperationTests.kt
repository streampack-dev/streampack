/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UnlinkProtocolRequest
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for protocol identity unlinking via UnlinkProtocolOperation */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class UnlinkProtocolOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository

    private lateinit var regularUser: UserPrincipal
    private lateinit var adminUser: UserPrincipal
    private lateinit var superAdmin: UserPrincipal

    @BeforeEach
    fun setUp() {
        regularUser =
            userRegistrationService.register(
                username = "regularuser",
                email = "regular@example.com",
                displayName = "Regular User",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "regularuser",
            )
        adminUser =
            userRegistrationService.register(
                username = "adminuser",
                email = "admin@example.com",
                displayName = "Admin User",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "adminuser",
                role = Role.ADMIN,
            )
        superAdmin =
            userRegistrationService.register(
                username = "superuser",
                email = "super@example.com",
                displayName = "Super Admin",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "superuser",
                role = Role.SUPER_ADMIN,
            )

        // Create a binding to unlink in tests
        userRegistrationService.linkProtocol(
            userId = regularUser.id,
            protocol = Protocol.IRC,
            serviceId = "ircservice",
            externalIdentifier = "regularuser_irc",
        )
    }

    private fun unlinkMessage(request: UnlinkProtocolRequest, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "test-service",
                    replyTo = "admin/unlink-protocol",
                    user = asUser,
                ),
            )
            .build()

    private fun textMessage(text: String, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "console",
                    replyTo = "console",
                    user = asUser,
                ),
            )
            .build()

    @Test
    fun `super admin can unlink protocol identity`() {
        val request =
            UnlinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(unlinkMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)

        val binding =
            serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "regularuser_irc")
        assertNull(binding)
    }

    @Test
    fun `regular user cannot unlink`() {
        val request =
            UnlinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(unlinkMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `admin cannot unlink`() {
        val request =
            UnlinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(unlinkMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request =
            UnlinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(unlinkMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent user returns error`() {
        val request =
            UnlinkProtocolRequest(
                username = "nobody",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "nobody_irc",
            )
        val result = eventGateway.process(unlinkMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent binding returns error`() {
        val request =
            UnlinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "nonexistent_binding",
            )
        val result = eventGateway.process(unlinkMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Binding not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `text command unlinks protocol identity`() {
        val result =
            eventGateway.process(
                textMessage("unlink user regularuser irc ircservice regularuser_irc", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)

        val binding =
            serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "regularuser_irc")
        assertNull(binding)
    }

    @Test
    fun `text command with invalid protocol is not handled`() {
        val result =
            eventGateway.process(
                textMessage("unlink user regularuser BOGUS ircservice nick", superAdmin)
            )

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `text command with too few parts is not handled`() {
        val result =
            eventGateway.process(textMessage("unlink user regularuser irc ircservice", superAdmin))

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
