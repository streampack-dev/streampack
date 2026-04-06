/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.SecretRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SecretRefEnvironmentTests {
    @Test
    fun `buildKey normalizes segments`() {
        assertEquals(
            "IRC_LIBERA_SASL_PASSWORD",
            SecretRefEnvironment.buildKey("irc", "libera", "sasl-password"),
        )
    }

    @Test
    fun `shellQuote escapes single quotes`() {
        assertEquals("'ab'\"'\"'cd'", SecretRefEnvironment.shellQuote("ab'cd"))
    }

    @Test
    fun `resolve uses env lookup for env ref`() {
        val secret = SecretRef.env("IRC_LIBERA_SASL_PASSWORD")
        val resolved = SecretRefEnvironment.resolve(secret) { "secret-value" }
        assertEquals("secret-value", resolved)
    }

    @Test
    fun `resolve falls back to stored value when env missing`() {
        val secret = SecretRef.env("IRC_LIBERA_SASL_PASSWORD")
        val resolved = SecretRefEnvironment.resolve(secret) { null }
        assertEquals("env://IRC_LIBERA_SASL_PASSWORD", resolved)
    }
}
