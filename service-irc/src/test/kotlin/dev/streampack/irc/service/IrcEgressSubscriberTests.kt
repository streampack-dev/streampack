/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

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
 * Tests the IRC egress subscriber pattern. Since IrcEgressSubscriber is gated by
 * streampack.irc.enabled, we use a capturing test subscriber to verify the egress flow for IRC
 * protocol messages.
 */
@SpringBootTest
class IrcEgressSubscriberTests {

    @TestConfiguration
    class Config {

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

    /** Captures egress messages for assertions instead of sending to IRC */
    @Component
    class CapturingIrcSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.IRC

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result to provenance)
        }

        fun reset() {
            received.clear()
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var subscriber: CapturingIrcSubscriber

    private fun ircMessage(
        text: String,
        networkName: String = "libera",
        channel: String = "#java",
    ) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.IRC, serviceId = networkName, replyTo = channel),
            )
            .build()

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
    fun `Success result is delivered to IRC subscriber`() {
        eventGateway.process(ircMessage("echo hello world"))

        assertEquals(1, subscriber.received.size)
        val (result, provenance) = subscriber.received[0]
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("hello world", (result as OperationResult.Success).payload)
        assertEquals(Protocol.IRC, provenance.protocol)
        assertEquals("libera", provenance.serviceId)
        assertEquals("#java", provenance.replyTo)
    }

    @Test
    fun `Error result is delivered to IRC subscriber`() {
        eventGateway.process(ircMessage("fail bad input"))

        assertEquals(1, subscriber.received.size)
        val (result, _) = subscriber.received[0]
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("bad input", (result as OperationResult.Error).message)
    }

    @Test
    fun `NotHandled result is delivered to IRC subscriber`() {
        eventGateway.process(ircMessage("unknown command"))

        assertEquals(1, subscriber.received.size)
        val (result, _) = subscriber.received[0]
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `Console messages are not delivered to IRC subscriber`() {
        eventGateway.process(consoleMessage("echo from console"))

        assertEquals(0, subscriber.received.size)
    }

    @Test
    fun `Provenance carries through from input to egress`() {
        eventGateway.process(ircMessage("echo check provenance", "oftc", "#kotlin"))

        assertEquals(1, subscriber.received.size)
        val (_, provenance) = subscriber.received[0]
        assertEquals("oftc", provenance.serviceId)
        assertEquals("#kotlin", provenance.replyTo)
    }

    @Test
    fun `send fires and result arrives via egress`() {
        eventGateway.send(ircMessage("echo async test"))

        Thread.sleep(200)

        assertEquals(1, subscriber.received.size)
        val (result, _) = subscriber.received[0]
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("async test", (result as OperationResult.Success).payload)
    }
}
