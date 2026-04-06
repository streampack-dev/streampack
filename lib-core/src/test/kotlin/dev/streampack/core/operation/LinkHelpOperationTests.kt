/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.IdentityDescription
import dev.streampack.core.service.IdentityProvider
import dev.streampack.core.service.IdentityResolution
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for link help discovery via LinkHelpOperation */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class, LinkHelpOperationTests.TestIdentityProviderConfig::class)
class LinkHelpOperationTests {

    @TestConfiguration
    class TestIdentityProviderConfig {
        @Bean
        fun testIdentityProvider(): IdentityProvider =
            object : IdentityProvider {
                override val protocol = Protocol.IRC

                override fun resolveIdentity(
                    serviceId: String,
                    externalIdentifier: String,
                ): IdentityResolution = IdentityResolution.Valid(serviceId, externalIdentifier)

                override fun describeIdentity() =
                    IdentityDescription(
                        protocol = Protocol.IRC,
                        serviceIdLabel = "network",
                        externalIdLabel = "nick",
                        availableServices = listOf("libera", "oftc"),
                    )
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService

    private lateinit var regularUser: UserPrincipal
    private lateinit var superAdmin: UserPrincipal

    @BeforeEach
    fun setUp() {
        regularUser =
            userRegistrationService.register(
                username = "helpuser",
                email = "helpuser@example.com",
                displayName = "Help User",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "helpuser",
            )
        superAdmin =
            userRegistrationService.register(
                username = "helpadmin",
                email = "helpadmin@example.com",
                displayName = "Help Admin",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "helpadmin",
                role = Role.SUPER_ADMIN,
            )
    }

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
    fun `link help lists all available protocols`() {
        val result = eventGateway.process(textMessage("link help", superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val output = (result as OperationResult.Success).payload as String
        assertTrue(output.contains("Available protocols for identity binding:"))
        assertTrue(output.contains("IRC: link user <username> irc <network> <nick>"))
        assertTrue(output.contains("Networks: libera, oftc"))
    }

    @Test
    fun `link help for specific protocol shows only that protocol`() {
        val result = eventGateway.process(textMessage("link help irc", superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val output = (result as OperationResult.Success).payload as String
        assertTrue(output.contains("IRC: link user <username> irc <network> <nick>"))
        assertTrue(output.contains("Networks: libera, oftc"))
        assertTrue(!output.contains("Available protocols"))
    }

    @Test
    fun `link help is case insensitive for protocol`() {
        val result = eventGateway.process(textMessage("link help IRC", superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val output = (result as OperationResult.Success).payload as String
        assertTrue(output.contains("IRC:"))
    }

    @Test
    fun `link help with unknown protocol returns error`() {
        val result = eventGateway.process(textMessage("link help bogus", superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Unknown protocol: bogus", (result as OperationResult.Error).message)
    }

    @Test
    fun `link help with protocol that has no provider returns error`() {
        val result = eventGateway.process(textMessage("link help discord", superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("No identity provider for DISCORD", (result as OperationResult.Error).message)
    }

    @Test
    fun `link help requires super admin`() {
        val result = eventGateway.process(textMessage("link help", regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `link help requires authentication`() {
        val result = eventGateway.process(textMessage("link help", null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }
}
