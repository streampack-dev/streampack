/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
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
class OperationTimeoutTests {

    @TestConfiguration
    class TestOps {

        @Bean fun fallbackRuns() = AtomicInteger()

        /** Operation that sleeps longer than its timeout allows */
        @Bean
        fun slowOperation() =
            object : Operation {
                override val priority = 10
                override val timeout: Duration = Duration.ofSeconds(1)

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "slow"

                override fun execute(message: Message<*>): OperationOutcome {
                    Thread.sleep(5_000)
                    return OperationResult.Success("should not reach this")
                }
            }

        /** Swallows the interrupt and returns nothing, as careless code does. */
        @Bean
        fun swallowingOperation() =
            object : Operation {
                override val priority = 10
                override val timeout: Duration = Duration.ofMillis(500)

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "swallow"

                override fun execute(message: Message<*>): OperationOutcome? {
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        // ignored
                    }
                    return null
                }
            }

        /** Fails with what an interrupted I/O call throws, not InterruptedException. */
        @Bean
        fun interruptedIoOperation() =
            object : Operation {
                override val priority = 10
                override val timeout: Duration = Duration.ofMillis(500)

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "io"

                override fun execute(message: Message<*>): OperationOutcome {
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        throw java.nio.channels.ClosedByInterruptException()
                    }
                    return OperationResult.Success("should not reach this")
                }
            }

        /**
         * Later in the chain and happy to take the same messages, as the factoid setter is with
         * almost anything: whatever it runs on would be a side effect nobody asked for.
         */
        @Bean
        fun fallbackOperation(fallbackRuns: AtomicInteger) =
            object : Operation {
                override val priority = 50

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) in setOf("slow", "swallow", "io")

                override fun execute(message: Message<*>): OperationOutcome {
                    fallbackRuns.incrementAndGet()
                    return OperationResult.Success("fallback handled it")
                }
            }

        /** Fast operation to verify normal operations are unaffected by timeout machinery */
        @Bean
        fun fastOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String) == "fast"

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("fast response")
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var fallbackRuns: AtomicInteger

    private fun buildMessage(payload: String): Message<String> {
        val provenance = Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")
        return MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, provenance).build()
    }

    @Test
    fun `a timed out operation ends the chain with an error, and nothing later runs`() {
        fallbackRuns.set(0)
        val start = System.currentTimeMillis()
        val result = eventGateway.process(buildMessage("slow"))
        val elapsed = System.currentTimeMillis() - start

        assertEquals(OperationResult.Error(OperationService.TIMED_OUT), result)
        assertEquals(0, fallbackRuns.get())
        assertTrue(elapsed < 3_000) { "Should end at the ~1s timeout, took ${elapsed}ms" }
    }

    @Test
    fun `it ends the chain however the interrupt surfaces`() {
        fallbackRuns.set(0)
        for (payload in listOf("swallow", "io")) {
            assertEquals(
                OperationResult.Error(OperationService.TIMED_OUT),
                eventGateway.process(buildMessage(payload)),
                payload,
            )
        }
        assertEquals(0, fallbackRuns.get())
    }

    @Test
    fun `fast operation completes normally`() {
        val result = eventGateway.process(buildMessage("fast"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload == "fast response") { "Expected fast response, got: $payload" }
    }
}
