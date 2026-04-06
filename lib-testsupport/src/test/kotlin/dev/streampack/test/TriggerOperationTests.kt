/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class TriggerOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private val provenance =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")

    private fun msg(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance)
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @Test
    fun `trigger success returns Success with message`() {
        val result = eventGateway.process(msg("trigger success hello world"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("hello world", (result as OperationResult.Success).payload)
    }

    @Test
    fun `trigger error returns Error with message`() {
        val result = eventGateway.process(msg("trigger error something went wrong"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("something went wrong", (result as OperationResult.Error).message)
    }

    @Test
    fun `trigger nothandled returns NotHandled`() {
        val result = eventGateway.process(msg("trigger nothandled"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `trigger success preserves reference tokens`() {
        val result = eventGateway.process(msg("trigger success look up {{ref:spring}}"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("look up {{ref:spring}}", (result as OperationResult.Success).payload)
    }

    @Test
    fun `trigger error preserves reference tokens`() {
        val result = eventGateway.process(msg("trigger error use {{ref:21 matches}} to start"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("use {{ref:21 matches}} to start", (result as OperationResult.Error).message)
    }

    @Test
    fun `non-trigger message is not handled`() {
        val result = eventGateway.process(msg("something else entirely"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
