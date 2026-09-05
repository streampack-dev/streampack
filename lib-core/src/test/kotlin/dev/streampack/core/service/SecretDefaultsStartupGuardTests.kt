/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretDefaultsStartupGuardTests {
    private val guard = SecretDefaultsStartupGuard(StreampackProperties(), true)

    @Test
    fun `documented placeholder fails even when enforcement is off`() {
        assertThrows(SilentStartupException::class.java) {
            guard.enforce("change-me-in-production", enforceExternalSecrets = false)
        }
    }

    @Test
    fun `placeholder match is case and whitespace insensitive`() {
        assertThrows(SilentStartupException::class.java) {
            guard.enforce("  Change-Me  ", enforceExternalSecrets = true)
        }
        assertThrows(SilentStartupException::class.java) {
            guard.enforce("CHANGEME", enforceExternalSecrets = true)
        }
    }

    @Test
    fun `blank secret fails when enforcement is on`() {
        assertThrows(SilentStartupException::class.java) {
            guard.enforce("", enforceExternalSecrets = true)
        }
        assertThrows(SilentStartupException::class.java) {
            guard.enforce("   ", enforceExternalSecrets = true)
        }
    }

    @Test
    fun `blank secret is allowed when enforcement is off`() {
        assertDoesNotThrow { guard.enforce("", enforceExternalSecrets = false) }
    }

    @Test
    fun `real secret passes in both modes`() {
        val secret = "a-genuinely-random-looking-secret-with-plenty-of-length"
        assertDoesNotThrow { guard.enforce(secret, enforceExternalSecrets = true) }
        assertDoesNotThrow { guard.enforce(secret, enforceExternalSecrets = false) }
    }

    @Test
    fun `short secret is accepted with a warning rather than rejected`() {
        assertDoesNotThrow { guard.enforce("short", enforceExternalSecrets = true) }
    }

    @Test
    fun `afterPropertiesSet reads the configured jwt secret`() {
        val bad =
            SecretDefaultsStartupGuard(
                StreampackProperties(
                    jwt = StreampackProperties.JwtProperties(secret = "change-me")
                ),
                true,
            )
        assertThrows(SilentStartupException::class.java) { bad.afterPropertiesSet() }
        val good =
            SecretDefaultsStartupGuard(
                StreampackProperties(
                    jwt =
                        StreampackProperties.JwtProperties(
                            secret = "test-secret-that-is-at-least-256-bits-long!!"
                        )
                ),
                true,
            )
        assertDoesNotThrow { good.afterPropertiesSet() }
        assertTrue(SecretPlaceholders.isPlaceholder("changeit"))
    }
}
