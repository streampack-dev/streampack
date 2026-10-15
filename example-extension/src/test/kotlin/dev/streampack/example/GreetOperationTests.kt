/* Joseph B. Ottinger (C)2026 */
package dev.streampack.example

import dev.streampack.core.model.OperationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.messaging.support.MessageBuilder

class GreetOperationTests {
    private val operation = GreetOperation()

    private fun message(text: String) = MessageBuilder.withPayload(text).build()

    @Test
    fun `greet answers with the name`() {
        val text = "greet gholem"
        assertTrue(operation.canHandle(text, message(text)))
        assertEquals(
            OperationResult.Success("Hello, gholem, from an extension."),
            operation.handle(text, message(text)),
        )
    }

    @Test
    fun `other text isn't for it`() {
        assertFalse(operation.canHandle("greeting", message("greeting")))
        assertFalse(operation.canHandle("hello", message("hello")))
    }
}
