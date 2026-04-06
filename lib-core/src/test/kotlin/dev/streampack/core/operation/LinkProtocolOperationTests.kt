/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.LinkProtocolRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.service.IdentityDescription
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for protocol identity linking via LinkProtocolOperation */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class, LinkProtocolOperationTests.TestIdentityProviders::class)
class LinkProtocolOperationTests {

    @TestConfiguration
    class TestIdentityProviders {
        @Bean
        fun testSlackIdentityProvider() =
            object : IdentityProvider {
                override val protocol: Protocol = Protocol.SLACK

                override fun resolveIdentity(
                    serviceId: String,
                    externalIdentifier: String,
                ): IdentityResolution {
                    return if (serviceId == "invalid-workspace") {
                        IdentityResolution.Invalid("Invalid Slack workspace")
                    } else {
                        IdentityResolution.Valid(
                            serviceId = serviceId,
                            externalIdentifier = externalIdentifier.lowercase(),
                        )
                    }
                }

                override fun describeIdentity(): IdentityDescription =
                    IdentityDescription(
                        protocol = Protocol.SLACK,
                        serviceIdLabel = "workspace",
                        externalIdLabel = "user ID",
                        availableServices = listOf("test-workspace"),
                    )
            }
    }

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
    }

    private fun linkProtocolMessage(request: LinkProtocolRequest, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "test-service",
                    replyTo = "admin/link-protocol",
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
    fun `super admin can link protocol identity`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(linkProtocolMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)

        val binding =
            serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "regularuser_irc")
        assertNotNull(binding)
    }

    @Test
    fun `link preserves metadata`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.DISCORD,
                serviceId = "jvm-community",
                externalIdentifier = "regular#1234",
                metadata = mapOf("oauthToken" to "tok_abc"),
            )
        val result = eventGateway.process(linkProtocolMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)

        val binding =
            serviceBindingRepository.resolve(Protocol.DISCORD, "jvm-community", "regular#1234")
        assertNotNull(binding)
        assertEquals("tok_abc", binding!!.metadata["oauthToken"])
    }

    @Test
    fun `regular user cannot link`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(linkProtocolMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `admin cannot link`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(linkProtocolMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val result = eventGateway.process(linkProtocolMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent user returns error`() {
        val request =
            LinkProtocolRequest(
                username = "nobody",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "nobody_irc",
            )
        val result = eventGateway.process(linkProtocolMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `duplicate binding returns error`() {
        // Link the first time
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        eventGateway.process(linkProtocolMessage(request, superAdmin))

        // Attempt duplicate
        val result = eventGateway.process(linkProtocolMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Duplicate binding", (result as OperationResult.Error).message)
    }

    // --- Text-based translate path tests ---

    @Test
    fun `text command links protocol identity`() {
        val result =
            eventGateway.process(
                textMessage("link user regularuser irc ircservice regularuser_irc", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)

        val binding =
            serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "regularuser_irc")
        assertNotNull(binding)
    }

    @Test
    fun `text command is case-insensitive for protocol`() {
        val result =
            eventGateway.process(
                textMessage("link user regularuser DISCORD jvm-community regular#1234", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)

        val binding =
            serviceBindingRepository.resolve(Protocol.DISCORD, "jvm-community", "regular#1234")
        assertNotNull(binding)
    }

    @Test
    fun `text command with invalid protocol is not handled`() {
        val result =
            eventGateway.process(
                textMessage("link user regularuser BOGUS ircservice nick", superAdmin)
            )

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `text command with too few parts is not handled`() {
        val result =
            eventGateway.process(textMessage("link user regularuser irc ircservice", superAdmin))

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `text command requires super admin`() {
        val result =
            eventGateway.process(
                textMessage("link user regularuser irc ircservice nick", regularUser)
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `text command with nonexistent user returns error`() {
        val result =
            eventGateway.process(
                textMessage("link user nobody irc ircservice nobody_irc", superAdmin)
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `provider validation failure returns provider reason`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.SLACK,
                serviceId = "invalid-workspace",
                externalIdentifier = "SomeUser",
            )
        val result = eventGateway.process(linkProtocolMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Invalid Slack workspace", (result as OperationResult.Error).message)
    }

    @Test
    fun `message without provenance returns no provenance error`() {
        val request =
            LinkProtocolRequest(
                username = "regularuser",
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "regularuser_irc",
            )
        val message = MessageBuilder.withPayload(request).build()
        val result = eventGateway.process(message)

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("No provenance", (result as OperationResult.Error).message)
    }

    @Test
    fun `capitalized link command prefix is accepted`() {
        val result =
            eventGateway.process(
                textMessage("Link user regularuser irc ircservice regularuser_caps", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val binding =
            serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "regularuser_caps")
        assertNotNull(binding)
    }
}
