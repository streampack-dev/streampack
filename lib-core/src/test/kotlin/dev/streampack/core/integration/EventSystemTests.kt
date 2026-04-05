/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.Declined
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.service.Operation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

/**
 * Integration tests for the core event system.
 *
 * These tests demonstrate the full round-trip: message enters via the EventGateway, flows through
 * the ingress channel, gets processed by the OperationService (which runs the operation chain), and
 * the result returns to the caller.
 *
 * The test operations below are intentionally simple -- they model the pattern every real operation
 * will follow. Study them as the canonical examples of how to implement an Operation.
 *
 * ## Test Operations (in priority order)
 * |Priority|Operation     |canHandle                 |execute behavior             |addressed|
 * |--------|--------------|--------------------------|-----------------------------|---------|
 * |1       |Indecisive    |payload contains "maybe"  |always returns null (passes) |true     |
 * |3       |Bouncer       |payload contains "private"|returns Declined             |true     |
 * |5       |Error reporter|payload contains "error"  |returns Error                |true     |
 * |10      |Greeter       |payload contains "hello"  |returns Success("greetings!")|true     |
 * |50      |Listener      |payload contains "music"  |returns Success("heard it")  |false    |
 */
@SpringBootTest
class EventSystemTests {

    /**
     * Registers test operations that exercise all paths through the operation chain. Each operation
     * demonstrates a specific behavior: handling, error reporting, or passing.
     */
    @TestConfiguration
    class TestOperationsConfig {

        /** Priority 1: accepts "maybe" messages but always returns null -- silent pass-through */
        @Bean
        fun indecisiveOperation() =
            object : Operation {
                override val priority = 1

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("maybe") == true

                override fun execute(message: Message<*>): OperationOutcome? = null
            }

        /** Priority 3: recognizes "private" messages but declines with a reason */
        @Bean
        fun bouncerOperation() =
            object : Operation {
                override val priority = 3

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("private") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    Declined("not authorized for this resource")
            }

        /** Priority 5: handles "error" messages by returning an Error result */
        @Bean
        fun errorReporterOperation() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("error") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Error("something went wrong")
            }

        /** Priority 10: handles "hello" messages by returning a greeting */
        @Bean
        fun greeterOperation() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("hello") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("greetings!")
            }

        /** Priority 50: handles "music" messages, does not require addressing */
        @Bean
        fun listenerOperation() =
            object : Operation {
                override val priority = 50
                override val addressed = false

                override fun canHandle(message: Message<*>): Boolean =
                    (message.payload as? String)?.contains("music") == true

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("heard it")
            }
    }

    @Autowired lateinit var eventGateway: EventGateway

    /** Builds a message with a Provenance header, mimicking what a real service adapter would do */
    private fun buildMessage(payload: String): Message<String> =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.HTTP, serviceId = "test-service", replyTo = "test"),
            )
            .build()

    /** Builds a message with explicit addressed header, mimicking a protocol adapter dispatch */
    private fun buildMessage(payload: String, addressed: Boolean): Message<String> =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.HTTP, serviceId = "test-service", replyTo = "test"),
            )
            .setHeader(Provenance.ADDRESSED, addressed)
            .build()

    // ---- Happy path: an operation handles the message ----

    @Test
    fun `message matching an operation returns Success`() {
        val result = eventGateway.process(buildMessage("hello world"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greetings!", (result as OperationResult.Success).payload)
    }

    // ---- Error path: an operation reports an error ----

    @Test
    fun `error result short-circuits the chain`() {
        val result = eventGateway.process(buildMessage("an error occurred"))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("something went wrong", (result as OperationResult.Error).message)
    }

    // ---- Declined: operation recognized the message but passed with diagnostic info ----

    @Test
    fun `declined operation continues the chain to next handler`() {
        // "private hello" matches bouncerOperation (priority 3) which declines,
        // then falls through to greeterOperation (priority 10) which handles it.
        val result = eventGateway.process(buildMessage("private hello"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greetings!", (result as OperationResult.Success).payload)
    }

    @Test
    fun `declined operation with no subsequent handler returns NotHandled`() {
        // "private" matches only bouncerOperation which declines.
        // No other operation can handle it, so the result is NotHandled.
        val result = eventGateway.process(buildMessage("private"))

        assertEquals(OperationResult.NotHandled, result)
    }

    // ---- Nothing handles the message ----

    @Test
    fun `unrecognized message returns NotHandled`() {
        val result = eventGateway.process(buildMessage("something completely unknown"))

        assertEquals(OperationResult.NotHandled, result)
    }

    // ---- Priority ordering: higher-priority operation wins ----

    @Test
    fun `higher priority operation runs first when multiple can handle`() {
        // "hello error" matches both greeterOperation (priority 10) and errorReporterOperation
        // (priority 5). The error reporter has higher priority (lower number) and should win.
        val result = eventGateway.process(buildMessage("hello error"))

        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    // ---- Pass-through: canHandle is true but execute returns null ----

    @Test
    fun `operation returning null from execute passes to next in chain`() {
        // "maybe hello" matches indecisiveOperation (priority 1) which returns null,
        // then falls through to greeterOperation (priority 10) which handles it.
        val result = eventGateway.process(buildMessage("maybe hello"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greetings!", (result as OperationResult.Success).payload)
    }

    @Test
    fun `operation returning null with no subsequent handler returns NotHandled`() {
        // "maybe" matches only indecisiveOperation which returns null.
        // No other operation can handle it, so the result is NotHandled.
        val result = eventGateway.process(buildMessage("maybe"))

        assertEquals(OperationResult.NotHandled, result)
    }

    // ---- canHandle filtering: operations that cannot handle are skipped entirely ----

    @Test
    fun `operations that cannot handle the message are skipped`() {
        // "hello" does NOT contain "error" or "maybe", so only greeterOperation runs.
        val result = eventGateway.process(buildMessage("hello"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greetings!", (result as OperationResult.Success).payload)
    }

    // ---- Default canHandle: operations that omit canHandle accept everything ----

    @Test
    fun `operation with default canHandle accepts all messages`() {
        // Verify the default canHandle behavior from the Operation interface
        val catchAll =
            object : Operation {
                override val priority = 999

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("caught")
            }

        // Default canHandle should return true for any message
        assertEquals(true, catchAll.canHandle(buildMessage("anything")))
    }

    // ---- Addressed header filtering ----

    @Test
    fun `addressed operation handles message when addressed header is true`() {
        val result = eventGateway.process(buildMessage("hello", addressed = true))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greetings!", (result as OperationResult.Success).payload)
    }

    @Test
    fun `addressed operation handles message when addressed header is absent`() {
        // No addressed header -- defaults to true for backward compatibility
        val result = eventGateway.process(buildMessage("hello"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("greetings!", (result as OperationResult.Success).payload)
    }

    @Test
    fun `addressed operation is skipped when addressed header is false`() {
        // greeterOperation (addressed=true) should be skipped on unaddressed messages
        val result = eventGateway.process(buildMessage("hello", addressed = false))

        assertEquals(OperationResult.NotHandled, result)
    }

    @Test
    fun `unaddressed operation handles message regardless of addressed header`() {
        // listenerOperation (addressed=false) handles "music" even when not addressed
        val result = eventGateway.process(buildMessage("music", addressed = false))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("heard it", (result as OperationResult.Success).payload)
    }

    @Test
    fun `unaddressed operation also handles addressed messages`() {
        val result = eventGateway.process(buildMessage("music", addressed = true))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("heard it", (result as OperationResult.Success).payload)
    }
}
