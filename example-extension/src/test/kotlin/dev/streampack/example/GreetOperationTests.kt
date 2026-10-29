/* Joseph B. Ottinger (C)2026 */
package dev.streampack.example

import dev.streampack.core.model.OperationResult
import dev.streampack.core.testing.ExtensionHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The extension's tests run with the harness: no server and no database (#294). */
class GreetOperationTests {
    private val harness = ExtensionHarness().use(GreetOperation())

    @Test
    fun `greet answers with the name`() {
        assertEquals("Hello, gholem, from an extension.", harness.reply("greet gholem"))
    }

    @Test
    fun `other text isn't for it`() {
        assertEquals(OperationResult.NotHandled, harness.send("greeting"))
        assertEquals(OperationResult.NotHandled, harness.send("hello"))
    }
}
