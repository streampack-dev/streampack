/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.controller

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class UserAwareControllerTests {

    private val jwtService =
        JwtService(
            StreampackProperties(
                jwt =
                    StreampackProperties.JwtProperties(
                        secret = "test-secret-value",
                        expirationHours = 12,
                    )
            )
        )

    private val controller = TestUserAwareController(jwtService)

    @Test
    fun `resolveUser returns null when header missing`() {
        val request = MockHttpServletRequest()

        val principal = controller.resolve(request)

        assertNull(principal)
    }

    @Test
    fun `resolveUser returns null when header is not bearer`() {
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Basic abc123")

        val principal = controller.resolve(request)

        assertNull(principal)
    }

    @Test
    fun `resolveUser returns principal when token is valid`() {
        val expected =
            UserPrincipal(
                id = java.util.UUID.randomUUID(),
                username = "jwt-user",
                displayName = "JWT User",
                role = Role.ADMIN,
            )
        val token = jwtService.generateToken(expected)
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer $token")

        val resolved = controller.resolve(request)

        requireNotNull(resolved, { "Expected principal to be resolved" })
        assertEquals(expected.id, resolved.id)
        assertEquals(expected.username, resolved.username)
        assertEquals(expected.displayName, resolved.displayName)
        assertEquals(expected.role, resolved.role)
    }

    /** Concrete controller to expose the protected resolveUser method */
    private class TestUserAwareController(jwtService: JwtService) :
        UserAwareController(jwtService) {
        fun resolve(request: HttpServletRequest) = resolveUser(request)
    }
}
