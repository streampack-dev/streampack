/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.ThrottlePolicy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

/**
 * A throttled operation that recognizes a message ends the chain with an error (#116): the message
 * was its command, so no later operation may reinterpret it. Operations that don't recognize a
 * message still pass it on.
 */
@SpringBootTest
@Import(TestChannelConfiguration::class)
class OperationThrottleTests {

    /** How many times each test operation has run. */
    class Calls {
        val limited = AtomicInteger()
        val fallback = AtomicInteger()

        fun reset() {
            limited.set(0)
            fallback.set(0)
        }
    }

    @TestConfiguration
    class TestOps {
        @Bean fun calls() = Calls()

        /** Recognizes "limited ...", once an hour per provenance. */
        @Bean
        fun limitedOperation(calls: Calls) =
            object : Operation {
                override val priority = 10
                override val throttlePolicy = ThrottlePolicy(1, Duration.ofHours(1))

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.startsWith("limited") == true

                override fun execute(message: Message<*>): OperationOutcome {
                    calls.limited.incrementAndGet()
                    return OperationResult.Success("limited ran")
                }
            }

        /**
         * Later in the chain, and happy to take the same messages, as the factoid setter is with
         * "ask why is foo": anything it runs on would be a side effect nobody asked for.
         */
        @Bean
        fun fallbackOperation(calls: Calls) =
            object : Operation {
                override val priority = 50

                override fun canHandle(message: Message<*>): Boolean {
                    val text = message.payload as? String ?: return false
                    return text.startsWith("limited") || text == "other"
                }

                override fun execute(message: Message<*>): OperationOutcome {
                    calls.fallback.incrementAndGet()
                    return OperationResult.Success("fallback ran")
                }
            }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var throttleService: ThrottleService
    @Autowired lateinit var calls: Calls

    @BeforeEach
    fun freshBuckets() {
        throttleService.clear()
        calls.reset()
    }

    private fun message(payload: String, replyTo: String = "local"): Message<String> {
        val provenance = Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = replyTo)
        return MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, provenance).build()
    }

    @Test
    fun `an operation with quota left runs as before`() {
        val result = eventGateway.process(message("limited one"))

        assertEquals(OperationResult.Success("limited ran"), result)
        assertEquals(1, calls.limited.get())
        assertEquals(0, calls.fallback.get())
    }

    @Test
    fun `a throttled operation ends the chain with an error, and nothing later runs`() {
        eventGateway.process(message("limited one"))
        val result = eventGateway.process(message("limited two"))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(OperationService.THROTTLED, (result as OperationResult.Error).message)
        assertEquals(1, calls.limited.get(), "the throttled operation didn't run again")
        assertEquals(0, calls.fallback.get(), "the message wasn't passed on to be reinterpreted")
    }

    @Test
    fun `a message no earlier operation recognizes still reaches later ones`() {
        eventGateway.process(message("limited one"))
        val result = eventGateway.process(message("other"))

        assertEquals(OperationResult.Success("fallback ran"), result)
        assertEquals(1, calls.fallback.get())
    }

    @Test
    fun `throttling stays per provenance`() {
        eventGateway.process(message("limited one", replyTo = "first"))
        val elsewhere = eventGateway.process(message("limited one", replyTo = "second"))

        assertEquals(OperationResult.Success("limited ran"), elsewhere)
        assertEquals(2, calls.limited.get())
    }
}
