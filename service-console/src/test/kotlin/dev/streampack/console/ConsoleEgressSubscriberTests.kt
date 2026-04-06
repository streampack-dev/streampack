/* Joseph B. Ottinger (C)2026 */
package dev.streampack.console

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.Operation
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * Tests the console egress subscriber pattern. Since ConsoleEgressSubscriber is gated by
 * streampack.console.enabled, we use a capturing test subscriber to verify the egress flow.
 */
@SpringBootTest
class ConsoleEgressSubscriberTests {

    @TestConfiguration
    class Config {

        /** Simple echo operation for testing */
        @Bean
        fun testEchoOp() =
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
        fun testFailOp() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("fail ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Error((message.payload as String).removePrefix("fail ").trim())
            }
    }

    /** Captures egress messages for assertions instead of printing to stdout */
    @Component
    class CapturingConsoleSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<OperationResult>()

        override fun matches(provenance: Provenance): Boolean =
            provenance.protocol == Protocol.CONSOLE

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result)
        }

        fun reset() {
            received.clear()
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var subscriber: CapturingConsoleSubscriber

    private fun consoleMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    @BeforeEach
    fun setUp() {
        subscriber.reset()
    }

    @Test
    fun `Success result is delivered to console subscriber`() {
        eventGateway.process(consoleMessage("echo hello world"))

        assertEquals(1, subscriber.received.size)
        assertInstanceOf(OperationResult.Success::class.java, subscriber.received[0])
        assertEquals("hello world", (subscriber.received[0] as OperationResult.Success).payload)
    }

    @Test
    fun `Error result is delivered to console subscriber`() {
        eventGateway.process(consoleMessage("fail bad input"))

        assertEquals(1, subscriber.received.size)
        assertInstanceOf(OperationResult.Error::class.java, subscriber.received[0])
        assertEquals("bad input", (subscriber.received[0] as OperationResult.Error).message)
    }

    @Test
    fun `NotHandled result is delivered to console subscriber`() {
        eventGateway.process(consoleMessage("unknown command"))

        assertEquals(1, subscriber.received.size)
        assertInstanceOf(OperationResult.NotHandled::class.java, subscriber.received[0])
    }

    @Test
    fun `IRC messages are not delivered to console subscriber`() {
        val ircMessage =
            MessageBuilder.withPayload("echo from irc")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java"),
                )
                .build()

        eventGateway.process(ircMessage)

        assertEquals(0, subscriber.received.size)
    }

    @Test
    fun `send fires and result arrives via egress`() {
        eventGateway.send(consoleMessage("echo async test"))

        // Give the async processing a moment
        Thread.sleep(200)

        assertEquals(1, subscriber.received.size)
        assertInstanceOf(OperationResult.Success::class.java, subscriber.received[0])
        assertEquals("async test", (subscriber.received[0] as OperationResult.Success).payload)
    }
}
