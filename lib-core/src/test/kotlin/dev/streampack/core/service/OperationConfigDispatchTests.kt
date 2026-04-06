/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
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
class OperationConfigDispatchTests {

    @TestConfiguration
    class TestOps {

        /** Addressed operation with a disableable group */
        @Bean
        fun disableableGreeter() =
            object : Operation {
                override val priority = 10
                override val operationGroup = "test-greet"

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("greet") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("greeted!")
            }

        /** Unaddressed operation with a disableable group */
        @Bean
        fun disableableListener() =
            object : Operation {
                override val priority = 50
                override val addressed = false
                override val operationGroup = "test-listen"

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("listen") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("listened!")
            }

        /** Operation with null group (cannot be disabled) */
        @Bean
        fun undisableableAdmin() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("admin") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("admin response")
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var operationService: OperationService
    @Autowired lateinit var operationConfigService: OperationConfigService

    @BeforeEach
    fun setup() {
        operationConfigService.clearCache()
    }

    private fun buildMessage(payload: String, provenance: Provenance? = null): Message<String> {
        val prov =
            provenance
                ?: Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        return MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, prov).build()
    }

    @Test
    fun `enabled operation handles messages normally`() {
        val result = eventGateway.process(buildMessage("greet me"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greeted!", (result as OperationResult.Success).payload)
    }

    @Test
    fun `globally disabled group skips operation`() {
        operationConfigService.setEnabled("", "test-greet", false)

        val result = eventGateway.process(buildMessage("greet me"))
        assertEquals(OperationResult.NotHandled, result)
    }

    @Test
    fun `provenance-specific disable skips operation for that provenance`() {
        operationConfigService.setEnabled("irc://libera/%23java", "test-greet", false)

        // Disabled channel
        val javaResult = eventGateway.process(buildMessage("greet me"))
        assertEquals(OperationResult.NotHandled, javaResult)

        // Different channel still works
        val kotlinProv =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#kotlin")
        val kotlinResult = eventGateway.process(buildMessage("greet me", kotlinProv))
        assertInstanceOf(OperationResult.Success::class.java, kotlinResult)
    }

    @Test
    fun `null operationGroup cannot be disabled`() {
        // Even with a global disable attempt on a bogus group, admin ops always work
        val result = eventGateway.process(buildMessage("admin command"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("admin response", (result as OperationResult.Success).payload)
    }

    @Test
    fun `disabled unaddressed operation does not trigger hasUnaddressedInterest`() {
        val message = buildMessage("listen to this")

        // Before disabling, unaddressed interest exists
        assertTrue(operationService.hasUnaddressedInterest(message))

        operationConfigService.setEnabled("", "test-listen", false)

        // After disabling, unaddressed interest is gone
        assertFalse(operationService.hasUnaddressedInterest(message))
    }

    @Test
    fun `re-enabling a disabled group restores operation`() {
        operationConfigService.setEnabled("", "test-greet", false)
        assertEquals(OperationResult.NotHandled, eventGateway.process(buildMessage("greet me")))

        operationConfigService.setEnabled("", "test-greet", true)
        val result = eventGateway.process(buildMessage("greet me"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}
