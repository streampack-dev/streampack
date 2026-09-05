/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.service.SilentStartupException
import dev.streampack.github.config.GitHubProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookSecretCipherTests {

    @Test
    fun `placeholder key is rejected at startup`() {
        assertThrows(SilentStartupException::class.java) {
            WebhookSecretCipher(GitHubProperties(webhookSecretKey = "change-me"))
        }
        assertThrows(SilentStartupException::class.java) {
            WebhookSecretCipher(GitHubProperties(webhookSecretKey = " Change-Me-In-Production "))
        }
    }

    @Test
    fun `blank key constructs but refuses to encrypt or decrypt`() {
        val cipher = WebhookSecretCipher(GitHubProperties(webhookSecretKey = ""))
        assertFalse(cipher.isConfigured)
        val error = assertThrows(IllegalStateException::class.java) { cipher.encrypt("secret") }
        assertTrue(error.message!!.contains("GITHUB_WEBHOOK_SECRET_KEY"), error.message)
        assertThrows(IllegalStateException::class.java) { cipher.decrypt("AAAA") }
    }

    @Test
    fun `real key round-trips with a fresh iv per encryption`() {
        val cipher = WebhookSecretCipher(GitHubProperties(webhookSecretKey = "test-key"))
        assertTrue(cipher.isConfigured)
        val first = cipher.encrypt("hunter2")
        val second = cipher.encrypt("hunter2")
        assertNotEquals(first, second)
        assertEquals("hunter2", cipher.decrypt(first))
        assertEquals("hunter2", cipher.decrypt(second))
    }
}
