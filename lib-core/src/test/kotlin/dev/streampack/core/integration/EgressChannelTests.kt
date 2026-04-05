/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.service.Operation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

@SpringBootTest
class EgressChannelTests {

    @TestConfiguration
    class EgressTestConfig {

        /** Simple operation that echoes the payload back as a Success result */
        @Bean
        fun echoOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("echo ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success(
                        (message.payload as String).removePrefix("echo ").trim()
                    )
            }

        /** Operation that returns an Error for "fail" messages */
        @Bean
        fun failOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("fail ") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Error((message.payload as String).removePrefix("fail ").trim())
            }

        /** Operation that redirects output to a different provenance */
        @Bean
        fun redirectOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("redirect ") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    val body = (message.payload as String).removePrefix("redirect ").trim()
                    val targetProvenance =
                        Provenance(
                            protocol = Protocol.IRC,
                            serviceId = "libera",
                            replyTo = "#redirected",
                        )
                    return OperationResult.Success(body, provenance = targetProvenance)
                }
            }
    }

    /** Test subscriber that captures egress messages matching CONSOLE protocol */
    @Component
    class ConsoleTestSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()
        var latch = CountDownLatch(1)

        override fun matches(provenance: Provenance): Boolean =
            provenance.protocol == Protocol.CONSOLE

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result to provenance)
            latch.countDown()
        }

        fun reset(expectedCount: Int = 1) {
            received.clear()
            latch = CountDownLatch(expectedCount)
        }
    }

    /** Test subscriber that captures egress messages matching IRC protocol */
    @Component
    class IrcTestSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()
        var latch = CountDownLatch(1)

        override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.IRC

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result to provenance)
            latch.countDown()
        }

        fun reset(expectedCount: Int = 1) {
            received.clear()
            latch = CountDownLatch(expectedCount)
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var consoleSubscriber: ConsoleTestSubscriber

    @Autowired lateinit var ircSubscriber: IrcTestSubscriber

    private fun consoleMessage(text: String): Message<String> =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    private fun ircMessage(text: String): Message<String> =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java"),
            )
            .build()

    @BeforeEach
    fun setUp() {
        consoleSubscriber.reset()
        ircSubscriber.reset()
    }

    @Test
    fun `process still returns result synchronously`() {
        val result = eventGateway.process(consoleMessage("echo hello"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("hello", (result as OperationResult.Success).payload)
    }

    @Test
    fun `process publishes Success result to egress channel`() {
        eventGateway.process(consoleMessage("echo hello"))

        assertEquals(1, consoleSubscriber.received.size)
        val (result, provenance) = consoleSubscriber.received[0]
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("hello", (result as OperationResult.Success).payload)
        assertEquals(Protocol.CONSOLE, provenance.protocol)
    }

    @Test
    fun `process publishes Error result to egress channel`() {
        eventGateway.process(consoleMessage("fail something broke"))

        assertEquals(1, consoleSubscriber.received.size)
        val (result, _) = consoleSubscriber.received[0]
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("something broke", (result as OperationResult.Error).message)
    }

    @Test
    fun `NotHandled results are published to egress`() {
        eventGateway.process(consoleMessage("unknown command"))

        assertEquals(1, consoleSubscriber.received.size)
        val (result, _) = consoleSubscriber.received[0]
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `subscriber only receives messages matching its protocol`() {
        consoleSubscriber.reset(1)

        // IRC message should not be received by the console subscriber
        eventGateway.process(ircMessage("echo from irc"))

        assertEquals(0, consoleSubscriber.received.size)

        // Console message should be received
        eventGateway.process(consoleMessage("echo from console"))

        assertEquals(1, consoleSubscriber.received.size)
        val (result, _) = consoleSubscriber.received[0]
        assertEquals("from console", (result as OperationResult.Success).payload)
    }

    @Test
    fun `send fires and forgets with result on egress`() {
        consoleSubscriber.reset(1)
        eventGateway.send(consoleMessage("echo async"))

        // Wait for the async processing to complete
        assertTrue(consoleSubscriber.latch.await(5, TimeUnit.SECONDS))

        assertEquals(1, consoleSubscriber.received.size)
        val (result, _) = consoleSubscriber.received[0]
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("async", (result as OperationResult.Success).payload)
    }

    @Test
    fun `egress message carries provenance from the original input`() {
        val provenance =
            Provenance(protocol = Protocol.CONSOLE, serviceId = "test-svc", replyTo = "test-reply")
        val message =
            MessageBuilder.withPayload("echo check provenance")
                .setHeader(Provenance.HEADER, provenance)
                .build()

        eventGateway.process(message)

        assertEquals(1, consoleSubscriber.received.size)
        val (_, receivedProvenance) = consoleSubscriber.received[0]
        assertEquals("test-svc", receivedProvenance.serviceId)
        assertEquals("test-reply", receivedProvenance.replyTo)
    }

    @Test
    fun `provenance override redirects egress to a different destination`() {
        eventGateway.process(consoleMessage("redirect hello from console"))

        // Console subscriber should NOT receive it -- provenance was overridden to IRC
        assertEquals(0, consoleSubscriber.received.size)

        // IRC subscriber should receive it with the overridden provenance
        assertTrue(ircSubscriber.latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, ircSubscriber.received.size)
        val (result, provenance) = ircSubscriber.received[0]
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("hello from console", (result as OperationResult.Success).payload)
        assertEquals(Protocol.IRC, provenance.protocol)
        assertEquals("libera", provenance.serviceId)
        assertEquals("#redirected", provenance.replyTo)
    }
}
