/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class TomorrowOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    @Test
    fun `tomorrow returns Gregorian date`() {
        val result = eventGateway.process(message("tomorrow"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Tomorrow is "), "Expected Tomorrow is in: $payload")
    }

    @Test
    fun `tomorrow hebrew returns Hebrew date`() {
        val result = eventGateway.process(message("tomorrow hebrew"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Hebrew"), "Expected Hebrew in: $payload")
    }

    @Test
    fun `tomorrow list returns all calendar names`() {
        val result = eventGateway.process(message("tomorrow list"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("gregorian"), "Expected gregorian in: $payload")
        assertTrue(payload.contains("hebrew"), "Expected hebrew in: $payload")
        assertTrue(payload.contains("hijri"), "Expected hijri in: $payload")
        assertTrue(payload.contains("japanese"), "Expected japanese in: $payload")
        assertTrue(payload.contains("minguo"), "Expected minguo in: $payload")
        assertTrue(payload.contains("thai-buddhist"), "Expected thai-buddhist in: $payload")
    }

    @Test
    fun `tomorrow with unknown calendar returns error`() {
        val result = eventGateway.process(message("tomorrow nonexistent"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val error = (result as OperationResult.Error).message
        assertTrue(error.contains("nonexistent"), "Expected calendar name in error: $error")
        assertTrue(error.contains("tomorrow list"), "Expected usage hint in error: $error")
    }

    @Test
    fun `tomorrow with too many arguments returns usage error`() {
        val result = eventGateway.process(message("tomorrow hebrew extra"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val error = (result as OperationResult.Error).message
        assertTrue(error.contains("Usage: tomorrow"), "Expected usage in error: $error")
    }

    @Test
    fun `non-tomorrow message is not handled`() {
        val result = eventGateway.process(message("something else entirely"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
