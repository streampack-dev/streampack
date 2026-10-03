/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidSetRequest
import dev.streampack.factoid.model.FactoidUpdatedEvent
import dev.streampack.factoid.model.FactoidVerbSetRequest
import dev.streampack.factoid.service.FactoidService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.integration.channel.QueueChannel
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class FactoidUpdateEventEmissionTests {

    @Autowired lateinit var factoidService: FactoidService

    @Test
    fun `set factoid emits factoid updated event on successful save`() {
        val gateway = RecordingEventGateway()
        val operation = SetFactoidOperation(factoidService, gateway)

        val result =
            operation.handle(
                FactoidSetRequest("thing", FactoidAttributeType.TEXT, "hello"),
                message(),
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(1, gateway.sentMessages.size)
        val event =
            assertInstanceOf(FactoidUpdatedEvent::class.java, gateway.sentMessages.single().payload)
        assertEquals("thing", event.selector)
    }

    @Test
    fun `factoid set verb emits factoid updated event on successful save`() {
        val gateway = RecordingEventGateway()
        val operation = SetFactoidVerbOperation(factoidService, gateway)

        val result =
            operation.handle(
                FactoidVerbSetRequest("thing", FactoidAttributeType.TEXT, "hello"),
                message(),
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(1, gateway.sentMessages.size)
        val event =
            assertInstanceOf(FactoidUpdatedEvent::class.java, gateway.sentMessages.single().payload)
        assertEquals("thing", event.selector)
    }

    @Test
    fun `a follow-up event keeps the provenance but not the caller's reply channels`() {
        // A request-and-reply caller (an HTTP endpoint) waits on these; a follow-up that kept them
        // could answer it in the setter's place.
        val provenance =
            Provenance(protocol = Protocol.HTTP, serviceId = "factoid", replyTo = "set")
        val incoming =
            MessageBuilder.withPayload("ignored")
                .setHeader(Provenance.HEADER, provenance)
                .setReplyChannel(QueueChannel())
                .setErrorChannel(QueueChannel())
                .build()

        for (send in
            listOf<(EventGateway) -> Unit>(
                {
                    SetFactoidOperation(factoidService, it)
                        .handle(
                            FactoidSetRequest("thing", FactoidAttributeType.TEXT, "hello"),
                            incoming,
                        )
                },
                {
                    SetFactoidVerbOperation(factoidService, it)
                        .handle(
                            FactoidVerbSetRequest("thing", FactoidAttributeType.TEXT, "hello"),
                            incoming,
                        )
                },
            )) {
            val gateway = RecordingEventGateway()
            send(gateway)
            val headers = gateway.sentMessages.single().headers
            assertEquals(provenance, headers[Provenance.HEADER])
            assertNull(headers.replyChannel)
            assertNull(headers.errorChannel)
        }
    }

    private fun message(): Message<*> = MessageBuilder.withPayload("ignored").build()

    private class RecordingEventGateway : EventGateway {
        val sentMessages = mutableListOf<Message<*>>()

        override fun process(message: Message<*>): OperationResult = OperationResult.NotHandled

        override fun send(message: Message<*>) {
            sentMessages += message
        }
    }
}
