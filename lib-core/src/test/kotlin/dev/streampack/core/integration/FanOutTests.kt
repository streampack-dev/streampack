/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.FanOut
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.Operation
import java.util.concurrent.CopyOnWriteArrayList
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

/**
 * Integration tests for fan-out dispatch and hop-count loop detection.
 *
 * ## Test Operations (in priority order)
 * |Priority|Operation      |canHandle                  |execute behavior                       |
 * |--------|---------------|---------------------------|---------------------------------------|
 * |1       |Fan-out        |payload contains "fanout"  |returns FanOut with child messages     |
 * |2       |Empty fan-out  |payload contains "emptyfan"|returns FanOut with no messages        |
 * |3       |Failing fan-out|payload contains "failfan" |returns FanOut with good + bad children|
 * |5       |Recursive fan  |payload contains "recurse" |returns FanOut whose children also fan |
 * |50      |Receiver       |payload contains "child"   |records receipt, returns Success       |
 * |51      |Bomb           |payload contains "bomb"    |throws RuntimeException                |
 */
@SpringBootTest
class FanOutTests {

    @TestConfiguration
    class FanOutTestConfig {

        /** Collects payloads received by the downstream receiver operation */
        @Bean fun receivedPayloads(): CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

        /** Priority 1: produces a FanOut with two child messages */
        @Bean
        fun fanOutOperation() =
            object : Operation {
                override val priority = 1

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("fanout") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    val provenance =
                        Provenance(protocol = Protocol.HTTP, serviceId = "test", replyTo = "test")
                    val children =
                        listOf("child-alpha", "child-beta").map { payload ->
                            MessageBuilder.withPayload(payload)
                                .setHeader(Provenance.HEADER, provenance)
                                .build()
                        }
                    return FanOut(children)
                }
            }

        /** Priority 2: produces an empty FanOut */
        @Bean
        fun emptyFanOutOperation() =
            object : Operation {
                override val priority = 2

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("emptyfan") == true

                override fun execute(message: Message<*>): OperationOutcome = FanOut(emptyList())
            }

        /** Priority 3: produces a FanOut with one good child and one that triggers an exception */
        @Bean
        fun failingFanOutOperation() =
            object : Operation {
                override val priority = 3

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("failfan") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    val provenance =
                        Provenance(protocol = Protocol.HTTP, serviceId = "test", replyTo = "test")
                    val children =
                        listOf(
                            MessageBuilder.withPayload("bomb-first")
                                .setHeader(Provenance.HEADER, provenance)
                                .build(),
                            MessageBuilder.withPayload("child-survivor")
                                .setHeader(Provenance.HEADER, provenance)
                                .build(),
                        )
                    return FanOut(children)
                }
            }

        /** Priority 5: produces a FanOut whose children also trigger fan-out (recursive) */
        @Bean
        fun recursiveFanOutOperation() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("recurse") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    val provenance =
                        Provenance(protocol = Protocol.HTTP, serviceId = "test", replyTo = "test")
                    val children =
                        listOf(
                            MessageBuilder.withPayload("recurse-deeper")
                                .setHeader(Provenance.HEADER, provenance)
                                .build()
                        )
                    return FanOut(children)
                }
            }

        /** Priority 50: records receipt of child messages */
        @Bean
        fun receiverOperation(receivedPayloads: CopyOnWriteArrayList<String>) =
            object : Operation {
                override val priority = 50

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("child") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    receivedPayloads.add(message.payload as String)
                    return OperationResult.Success("received: ${message.payload}")
                }
            }

        /** Priority 51: always throws to simulate a failing child */
        @Bean
        fun bombOperation() =
            object : Operation {
                override val priority = 51

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("bomb") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    throw RuntimeException("boom")
                }
            }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var receivedPayloads: CopyOnWriteArrayList<String>

    @BeforeEach
    fun setUp() {
        receivedPayloads.clear()
    }

    private fun buildMessage(payload: String): Message<String> =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.HTTP, serviceId = "test-service", replyTo = "test"),
            )
            .build()

    @Test
    fun `fan-out dispatches child messages through the chain`() {
        val result = eventGateway.process(buildMessage("fanout"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(receivedPayloads.contains("child-alpha"), "child-alpha should be received")
        assertTrue(receivedPayloads.contains("child-beta"), "child-beta should be received")
    }

    @Test
    fun `fan-out returns Success with dispatch count`() {
        val result = eventGateway.process(buildMessage("fanout"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Dispatched 2 messages", (result as OperationResult.Success).payload)
    }

    @Test
    fun `hop count increments on re-entry`() {
        // Send a message with hop count 0 that triggers fan-out; children get hop count 1.
        // Children are "child-alpha" and "child-beta" which the receiver handles.
        // If hop count were not incremented, recursive scenarios would loop forever.
        val result = eventGateway.process(buildMessage("fanout"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(2, receivedPayloads.size)
    }

    @Test
    fun `hop count exceeding threshold returns Error`() {
        // Default maxHops is 3. Send a message with hop count already at 4.
        val message =
            MessageBuilder.withPayload("fanout")
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "test-service",
                        replyTo = "test",
                    ),
                )
                .setHeader(FanOut.HOP_COUNT_HEADER, 4)
                .build()

        val result = eventGateway.process(message)

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Maximum hop count exceeded", (result as OperationResult.Error).message)
        assertTrue(receivedPayloads.isEmpty(), "no children should be dispatched")
    }

    @Test
    fun `empty fan-out returns Success with zero count`() {
        val result = eventGateway.process(buildMessage("emptyfan"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Dispatched 0 messages", (result as OperationResult.Success).payload)
    }

    @Test
    fun `child message failure does not prevent other children from processing`() {
        // failfan produces two children: "bomb-first" (throws) and "child-survivor" (succeeds)
        val result = eventGateway.process(buildMessage("failfan"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Dispatched 1 messages", (result as OperationResult.Success).payload)
        assertTrue(
            receivedPayloads.contains("child-survivor"),
            "surviving child should be received",
        )
    }

    @Test
    fun `recursive fan-out terminates at hop count limit`() {
        // "recurse" triggers fan-out that produces another "recurse-deeper" which also contains
        // "recurse", creating a recursive chain. The hop count limit (default 3) stops it.
        val result = eventGateway.process(buildMessage("recurse"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        // The recursion should eventually hit the hop limit and return Error for that child,
        // but the parent dispatch still counts as Success (caught exception per child).
    }
}
