/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.FanOut
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

/**
 * Integration tests for loopback dispatch: an operation returns Success with loopback=true, the
 * result is delivered to egress normally, then the payload is re-injected as a new addressed
 * message which a second operation handles.
 */
@SpringBootTest
class LoopbackTests {

    @TestConfiguration
    class LoopbackTestConfig {

        @Bean fun loopbackCatcherLog(): CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

        /** Priority 10: triggers loopback when payload starts with "loopback " */
        @Bean
        fun loopbackTriggerOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("loopback ") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    val body = (message.payload as String).removePrefix("loopback ").trim()
                    return OperationResult.Success(
                        "Poem about $body\nWith multiple lines",
                        loopback = true,
                    )
                }
            }

        /** Priority 5: catches multi-line text (the looped-back poem) */
        @Bean
        fun loopbackCatcherOperation(loopbackCatcherLog: CopyOnWriteArrayList<String>) =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>): Boolean {
                    val payload = message.payload as? String ?: return false
                    return payload.contains("\n") && payload.length > 10
                }

                override fun execute(message: Message<*>): OperationOutcome {
                    loopbackCatcherLog.add(message.payload as String)
                    return OperationResult.Success("Analysis: caught loopback content")
                }
            }
    }

    /** Captures egress results for CONSOLE protocol, with latch for async scenarios */
    @Component
    class LoopbackEgressSubscriber : EgressSubscriber() {
        val received = CopyOnWriteArrayList<OperationResult>()
        var latch = CountDownLatch(1)

        override fun matches(provenance: Provenance): Boolean =
            provenance.protocol == Protocol.CONSOLE

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result)
            latch.countDown()
        }

        fun reset(expectedCount: Int = 1) {
            received.clear()
            latch = CountDownLatch(expectedCount)
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var loopbackCatcherLog: CopyOnWriteArrayList<String>

    @Autowired lateinit var egressSubscriber: LoopbackEgressSubscriber

    @BeforeEach
    fun setUp() {
        loopbackCatcherLog.clear()
        egressSubscriber.reset(2)
    }

    private fun consoleMessage(text: String): Message<String> =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @Test
    fun `loopback result is delivered to egress and re-injected`() {
        val result = eventGateway.process(consoleMessage("loopback roses"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Poem about roses"), "Original result should contain the poem")

        // Wait for both egress messages (original + loopback analysis) before checking catcher
        // log, because the loopback runs asynchronously on a virtual thread
        assertTrue(
            egressSubscriber.latch.await(5, TimeUnit.SECONDS),
            "Expected 2 egress deliveries",
        )
        assertEquals(2, egressSubscriber.received.size, "Expected 2 egress messages")

        // Now the catcher should have received the looped-back multi-line content
        assertEquals(1, loopbackCatcherLog.size, "Catcher should have received the loopback")
        assertTrue(loopbackCatcherLog[0].contains("Poem about roses"))
    }

    @Test
    fun `non-loopback result is not re-injected`() {
        egressSubscriber.reset(1)
        eventGateway.process(consoleMessage("no-match-at-all"))

        assertTrue(loopbackCatcherLog.isEmpty(), "Catcher should not have been triggered")
        assertTrue(egressSubscriber.latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, egressSubscriber.received.size, "Only one egress message expected")
    }

    @Test
    fun `loopback respects hop count limit`() {
        egressSubscriber.reset(1)
        // Send a message already past the hop limit (maxHops default is 3, guard is >)
        val message =
            MessageBuilder.withPayload("loopback testing")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
                )
                .setHeader(Provenance.ADDRESSED, true)
                .setHeader(FanOut.HOP_COUNT_HEADER, 4)
                .build()

        val result = eventGateway.process(message)

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(loopbackCatcherLog.isEmpty(), "No loopback should occur at hop limit")
    }
}
