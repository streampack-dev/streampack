/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.LoggingRequest
import dev.streampack.core.model.MessageDirection
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.core.service.Operation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class LoggingEgressSubscriberTests {

    @TestConfiguration
    class LoggingTestConfig {

        @Bean
        fun loggingTestEchoOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("echo ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success(
                        (message.payload as String).removePrefix("echo ").trim()
                    )
            }

        @Bean
        fun loggingTestFailOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("fail ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Error((message.payload as String).removePrefix("fail ").trim())
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var messageLogRepository: MessageLogRepository

    private fun uniqueProvenance(): Provenance =
        Provenance(
            protocol = Protocol.CONSOLE,
            serviceId = "log-test-${System.nanoTime()}",
            replyTo = "local",
        )

    private fun messageWith(payload: Any, provenance: Provenance) =
        MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, provenance).build()

    @Test
    fun `Success result is logged as outbound`() {
        val prov = uniqueProvenance()
        eventGateway.process(messageWith("echo hello", prov))

        val page =
            messageLogRepository.findByProvenanceUriOrderByTimestampDesc(
                prov.encode(),
                PageRequest.of(0, 10),
            )
        val outbound = page.content.filter { it.direction == MessageDirection.OUTBOUND }
        assertEquals(1, outbound.size)
        assertEquals("hello", outbound[0].content)
    }

    @Test
    fun `Error result is logged as outbound`() {
        val prov = uniqueProvenance()
        eventGateway.process(messageWith("fail something broke", prov))

        val page =
            messageLogRepository.findByProvenanceUriOrderByTimestampDesc(
                prov.encode(),
                PageRequest.of(0, 10),
            )
        val outbound = page.content.filter { it.direction == MessageDirection.OUTBOUND }
        assertEquals(1, outbound.size)
        assertEquals("Error: something broke", outbound[0].content)
    }

    @Test
    fun `NotHandled result is not logged`() {
        val prov = uniqueProvenance()
        eventGateway.process(messageWith("unknown command", prov))

        val page =
            messageLogRepository.findByProvenanceUriOrderByTimestampDesc(
                prov.encode(),
                PageRequest.of(0, 10),
            )
        val outbound = page.content.filter { it.direction == MessageDirection.OUTBOUND }
        assertEquals(0, outbound.size)
    }

    @Test
    fun `inbound message is logged via wire tap`() {
        val prov = uniqueProvenance()
        eventGateway.process(messageWith("echo hello", prov))

        val page =
            messageLogRepository.findByProvenanceUriOrderByTimestampDesc(
                prov.encode(),
                PageRequest.of(0, 10),
            )
        val inbound = page.content.filter { it.direction == MessageDirection.INBOUND }
        assertEquals(1, inbound.size)
        assertEquals("echo hello", inbound[0].content)
    }

    @Test
    fun `LoggingRequest is captured by wire tap but never reaches operations`() {
        val prov = uniqueProvenance()
        val result =
            eventGateway.process(messageWith(LoggingRequest("* alice left the channel"), prov))

        assertInstanceOf(OperationResult.NotHandled::class.java, result)

        val page =
            messageLogRepository.findByProvenanceUriOrderByTimestampDesc(
                prov.encode(),
                PageRequest.of(0, 10),
            )
        val inbound = page.content.filter { it.direction == MessageDirection.INBOUND }
        assertEquals(1, inbound.size)
        assertEquals("* alice left the channel", inbound[0].content)

        val outbound = page.content.filter { it.direction == MessageDirection.OUTBOUND }
        assertEquals(0, outbound.size)
    }
}
