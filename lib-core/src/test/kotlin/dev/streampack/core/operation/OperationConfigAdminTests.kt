/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.TestChannelConfiguration
import com.enigmastation.streampack.core.integration.EventGateway
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.model.UserPrincipal
import com.enigmastation.streampack.core.service.Operation
import com.enigmastation.streampack.core.service.OperationConfigService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class OperationConfigAdminTests {

    /** Provides a test operation with a known group for admin command tests */
    @TestConfiguration
    class TestOps {
        @Bean
        fun adminTestOp() =
            object : Operation {
                override val priority = 80
                override val operationGroup = "test-admin-op"

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("test-admin-trigger") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("test-admin-response")
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var configService: OperationConfigService

    @BeforeEach
    fun setup() {
        configService.clearCache()
    }

    private fun buildMessage(
        payload: String,
        role: Role = Role.GUEST,
        provenance: Provenance? = null,
    ): Message<String> {
        val prov =
            provenance
                ?: Provenance(
                    protocol = Protocol.IRC,
                    serviceId = "libera",
                    replyTo = "#java",
                    user =
                        UserPrincipal(
                            id = java.util.UUID.randomUUID(),
                            username = "testuser",
                            displayName = "Test User",
                            role = role,
                        ),
                )
        return MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, prov).build()
    }

    // -- ServiceAdminOperation tests --

    @Test
    fun `service help shows usage`() {
        val result = eventGateway.process(buildMessage("service"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Service Admin"))
    }

    @Test
    fun `service list is public`() {
        val result = eventGateway.process(buildMessage("service list", Role.GUEST))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `service enable requires SUPER_ADMIN`() {
        val result = eventGateway.process(buildMessage("service enable irc", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("SUPER_ADMIN"))
    }

    @Test
    fun `service enable works for SUPER_ADMIN`() {
        val result = eventGateway.process(buildMessage("service enable irc", Role.SUPER_ADMIN))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("enabled"))
    }

    @Test
    fun `service disable persists state`() {
        eventGateway.process(buildMessage("service disable irc", Role.SUPER_ADMIN))
        val config = configService.findConfig("", "service:irc")
        assertFalse(config!!.enabled)
    }

    // -- OperationAdminOperation tests --

    @Test
    fun `operation help shows usage`() {
        val result = eventGateway.process(buildMessage("operation"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success).payload.toString().contains("Operation Admin")
        )
    }

    @Test
    fun `operation config is public`() {
        val result = eventGateway.process(buildMessage("operation config", Role.GUEST))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `operation disable requires ADMIN`() {
        val result =
            eventGateway.process(buildMessage("operation disable test-admin-op", Role.USER))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("ADMIN"))
    }

    @Test
    fun `operation disable works for ADMIN`() {
        val result =
            eventGateway.process(buildMessage("operation disable test-admin-op", Role.ADMIN))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("disabled"))
    }

    @Test
    fun `operation disable rejects unknown group`() {
        val result = eventGateway.process(buildMessage("operation disable nonexistent", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("Unknown"))
    }

    @Test
    fun `operation enable round-trip restores functionality`() {
        // Disable the test group
        eventGateway.process(buildMessage("operation disable test-admin-op", Role.ADMIN))
        val disabled = eventGateway.process(buildMessage("test-admin-trigger"))
        assertEquals(OperationResult.NotHandled, disabled)

        // Re-enable
        eventGateway.process(buildMessage("operation enable test-admin-op", Role.ADMIN))
        val enabled = eventGateway.process(buildMessage("test-admin-trigger"))
        assertInstanceOf(OperationResult.Success::class.java, enabled)
        assertEquals("test-admin-response", (enabled as OperationResult.Success).payload)
    }

    @Test
    fun `operation set stores config value`() {
        val result =
            eventGateway.process(
                buildMessage("operation set test-admin-op maxItems 50", Role.ADMIN)
            )
        assertInstanceOf(OperationResult.Success::class.java, result)

        val config = configService.findConfig("", "test-admin-op")
        assertEquals("50", config!!.config["maxItems"])
    }

    @Test
    fun `operation unknown subcommand returns error`() {
        val result = eventGateway.process(buildMessage("operation explode", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(
            (result as OperationResult.Error).message.contains("Unknown operation subcommand")
        )
    }

    @Test
    fun `operation enable without group returns usage error`() {
        val result = eventGateway.process(buildMessage("operation enable", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Usage: operation enable <group>", (result as OperationResult.Error).message)
    }

    @Test
    fun `operation disable without group returns usage error`() {
        val result = eventGateway.process(buildMessage("operation disable", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Usage: operation disable <group>", (result as OperationResult.Error).message)
    }

    @Test
    fun `operation set without enough args returns usage error`() {
        val result =
            eventGateway.process(buildMessage("operation set test-admin-op onlyKey", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Usage: operation set <group> <key> <value>",
            (result as OperationResult.Error).message,
        )
    }

    // -- ChannelConfigOperation tests --

    @Test
    fun `channel help shows usage`() {
        val result = eventGateway.process(buildMessage("channel"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success).payload.toString().contains("Channel Config")
        )
    }

    @Test
    fun `channel config is public`() {
        val result = eventGateway.process(buildMessage("channel config", Role.GUEST))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `channel disable requires ADMIN`() {
        val result = eventGateway.process(buildMessage("channel disable test-admin-op", Role.USER))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("ADMIN"))
    }

    @Test
    fun `channel disable uses current provenance when no for clause`() {
        eventGateway.process(buildMessage("channel disable test-admin-op", Role.ADMIN))
        val config = configService.findConfig("irc://libera/%23java", "test-admin-op")
        assertFalse(config!!.enabled)
    }

    @Test
    fun `channel disable with for clause targets specific provenance`() {
        eventGateway.process(
            buildMessage("channel disable test-admin-op for irc://oftc/%23java", Role.ADMIN)
        )
        val config = configService.findConfig("irc://oftc/%23java", "test-admin-op")
        assertFalse(config!!.enabled)
    }

    @Test
    fun `channel config shows resolved state`() {
        configService.setEnabled("", "test-admin-op", false)
        configService.setEnabled("irc://libera", "test-admin-op", true)

        val result = eventGateway.process(buildMessage("channel config", Role.GUEST))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val output = (result as OperationResult.Success).payload.toString()
        assertTrue(output.contains("test-admin-op"))
    }

    @Test
    fun `channel unknown subcommand returns error`() {
        val result = eventGateway.process(buildMessage("channel kaboom", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("Unknown channel subcommand"))
    }

    @Test
    fun `channel enable without group returns usage error`() {
        val result = eventGateway.process(buildMessage("channel enable", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Usage: channel enable <group> [for <pattern>]",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `channel set without enough args returns usage error`() {
        val result =
            eventGateway.process(buildMessage("channel set test-admin-op onlyKey", Role.ADMIN))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Usage: channel set <group> <key> <value> [for <pattern>]",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `channel set with value and for clause stores scoped config`() {
        val result =
            eventGateway.process(
                buildMessage(
                    "channel set test-admin-op greeting hello there for irc://oftc/%23kotlin",
                    Role.ADMIN,
                )
            )
        assertInstanceOf(OperationResult.Success::class.java, result)

        val config = configService.findConfig("irc://oftc/%23kotlin", "test-admin-op")
        assertEquals("hello there", config!!.config["greeting"])
    }

    @Test
    fun `channel config without provenance suggests for clause`() {
        val message = MessageBuilder.withPayload("channel config").build()
        val result = eventGateway.process(message)
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(
            (result as OperationResult.Error).message.contains("Cannot determine target provenance")
        )
    }
}
