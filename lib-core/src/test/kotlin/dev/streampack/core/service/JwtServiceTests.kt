/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JwtServiceTests {

    private fun propertiesWithSecret(secret: String, expirationHours: Long = 24) =
        StreampackProperties(
            jwt =
                StreampackProperties.JwtProperties(
                    secret = secret,
                    expirationHours = expirationHours,
                )
        )

    private val jwtService =
        JwtService(propertiesWithSecret("test-secret-that-is-at-least-256-bits-long!!"))

    private val testPrincipal =
        UserPrincipal(
            id = UUID.fromString("01234567-89ab-7def-8123-456789abcdef"),
            username = "testuser",
            displayName = "Test User",
            role = Role.USER,
        )

    @Test
    fun `generate and validate round-trip preserves identity`() {
        val token = jwtService.generateToken(testPrincipal)
        val result = jwtService.validateToken(token)

        assertNotNull(result)
        assertEquals(testPrincipal.id, result!!.id)
        assertEquals(testPrincipal.username, result.username)
        assertEquals(testPrincipal.displayName, result.displayName)
        assertEquals(testPrincipal.role, result.role)
    }

    @Test
    fun `validate returns null for tampered token`() {
        val token = jwtService.generateToken(testPrincipal)
        val tampered = token.dropLast(5) + "XXXXX"

        assertNull(jwtService.validateToken(tampered))
    }

    @Test
    fun `validate returns null for garbage input`() {
        assertNull(jwtService.validateToken("not.a.jwt"))
    }

    @Test
    fun `validate returns null for empty string`() {
        assertNull(jwtService.validateToken(""))
    }

    @Test
    fun `expired token is rejected`() {
        val expiredService =
            JwtService(propertiesWithSecret("test-secret-that-is-at-least-256-bits-long!!", 0))
        val token = expiredService.generateToken(testPrincipal)

        assertNull(expiredService.validateToken(token))
    }

    @Test
    fun `tokens from different keys are rejected`() {
        val otherService =
            JwtService(propertiesWithSecret("different-secret-also-at-least-256-bits-long!!"))
        val token = jwtService.generateToken(testPrincipal)

        assertNull(otherService.validateToken(token))
    }

    @Test
    fun `admin role round-trips correctly`() {
        val admin = testPrincipal.copy(role = Role.SUPER_ADMIN)
        val token = jwtService.generateToken(admin)
        val result = jwtService.validateToken(token)

        assertNotNull(result)
        assertEquals(Role.SUPER_ADMIN, result!!.role)
    }
}
