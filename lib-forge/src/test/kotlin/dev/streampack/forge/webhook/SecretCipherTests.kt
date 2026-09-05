/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.webhook

import dev.streampack.core.service.SilentStartupException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretCipherTests {
    private fun cipher(key: String) =
        SecretCipher(key, "streampack.test.key (TEST_KEY)", "Test", "TEST_KEY must be set")

    @Test
    fun `placeholder key is rejected`() {
        assertThrows(SilentStartupException::class.java) { cipher("change-me") }
    }

    @Test
    fun `blank key is tolerated until used`() {
        val c = cipher("")
        assertFalse(c.isConfigured)
        val error = assertThrows(IllegalStateException::class.java) { c.encrypt("x") }
        assertEquals("TEST_KEY must be set", error.message)
    }

    @Test
    fun `configured key round-trips`() {
        val c = cipher("a-real-key")
        assertTrue(c.isConfigured)
        assertEquals("hunter2", c.decrypt(c.encrypt("hunter2")))
    }
}
