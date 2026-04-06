/* Joseph B. Ottinger (C)2026 */
package dev.streampack.calc.operation

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
class CalculatorOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private fun calcMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    @Test
    fun `valid expression returns success`() {
        val result = eventGateway.process(calcMessage("calc 2+3"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("The result of 2+3 is: 5.0", payload)
    }

    @Test
    fun `complex expression returns success`() {
        val result = eventGateway.process(calcMessage("calc (10+5)*2"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `complex expression with embedded spaces returns success`() {
        val result = eventGateway.process(calcMessage("calc (10+5)*   2"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `complex expression with multiple embedded spaces returns success`() {
        val result = eventGateway.process(calcMessage("calc (10 +   5)*   2"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `invalid expression returns error`() {
        val result = eventGateway.process(calcMessage("calc hello world"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `mismatched parens returns error`() {
        val result = eventGateway.process(calcMessage("calc ((()"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `non-calc message is not handled`() {
        val result = eventGateway.process(calcMessage("karma foo++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `calc with no expression returns error`() {
        val result = eventGateway.process(calcMessage("calc "))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `triggered calc command is accepted`() {
        val result = eventGateway.process(calcMessage("!calc 7*6"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}
