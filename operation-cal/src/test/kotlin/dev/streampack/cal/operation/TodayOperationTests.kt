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
class TodayOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    @Test
    fun `today returns Gregorian date`() {
        val result = eventGateway.process(message("today"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Today is "), "Expected Today is in: $payload")
    }

    @Test
    fun `today hebrew returns Hebrew date`() {
        val result = eventGateway.process(message("today hebrew"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Hebrew"), "Expected Hebrew in: $payload")
    }

    @Test
    fun `today hijri returns Hijri date`() {
        val result = eventGateway.process(message("today hijri"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Hijri"), "Expected Hijri in: $payload")
    }

    @Test
    fun `today list returns all calendar names`() {
        val result = eventGateway.process(message("today list"))
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
    fun `today with unknown calendar returns error`() {
        val result = eventGateway.process(message("today nonexistent"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val error = (result as OperationResult.Error).message
        assertTrue(error.contains("nonexistent"), "Expected calendar name in error: $error")
        assertTrue(error.contains("today list"), "Expected usage hint in error: $error")
    }

    @Test
    fun `today with too many arguments returns usage error`() {
        val result = eventGateway.process(message("today hebrew extra"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val error = (result as OperationResult.Error).message
        assertTrue(error.contains("Usage: today"), "Expected usage in error: $error")
    }

    @Test
    fun `non-today message is not handled`() {
        val result = eventGateway.process(message("something else"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
