/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretRefTests {

    @Test
    fun `literal values are not env refs and resolve directly`() {
        val ref = SecretRef.literal("hunter2")

        assertFalse(ref.isEnvRef())
        assertNull(ref.envKeyOrNull())
        assertEquals("hunter2", ref.resolve(emptyMap()))
        assertEquals("hunter2", ref.asStoredValue())
    }

    @Test
    fun `env refs expose key and resolve from environment`() {
        val ref = SecretRef.env("irc_libera_sasl_password")

        assertTrue(ref.isEnvRef())
        assertEquals("IRC_LIBERA_SASL_PASSWORD", ref.envKeyOrNull())
        assertEquals(
            "super-secret",
            ref.resolve(mapOf("IRC_LIBERA_SASL_PASSWORD" to "super-secret")),
        )
        assertEquals("env://IRC_LIBERA_SASL_PASSWORD", ref.asStoredValue())
    }

    @Test
    fun `invalid env refs do not expose key`() {
        val ref = SecretRef.parse("env://bad-key")

        assertTrue(ref.isEnvRef())
        assertNull(ref.envKeyOrNull())
        assertEquals("env://bad-key", ref.resolve(emptyMap()))
    }

    @Test
    fun `missing env variable resolves to null`() {
        val ref = SecretRef.env("SLACK_PRIMARY_BOT_TOKEN")

        assertNull(ref.resolve(emptyMap()))
    }
}
