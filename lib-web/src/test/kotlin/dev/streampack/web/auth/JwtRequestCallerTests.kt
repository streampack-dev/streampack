/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.auth

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import jakarta.servlet.http.Cookie
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/** Who a request comes from, as the server's controllers and extensions see it (#302). */
class JwtRequestCallerTests {
    private val jwtService =
        JwtService(
            StreampackProperties(
                jwt = StreampackProperties.JwtProperties(secret = "test-secret-value"),
            ),
        )
    private val caller = JwtRequestCaller(jwtService)

    private val alice = UserPrincipal(UUID.randomUUID(), "alice", "Alice", Role.ADMIN)
    private val bob = UserPrincipal(UUID.randomUUID(), "bob", "Bob", Role.USER)

    @Test
    fun `no credential is no caller`() {
        assertThat(caller.resolve(MockHttpServletRequest())).isNull()
    }

    @Test
    fun `a bearer token names the caller, not from the cookie`() {
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer ${jwtService.generateToken(alice)}")

        val credential = caller.resolve(request)!!

        assertThat(credential.principal).isEqualTo(alice)
        assertThat(credential.fromCookie).isFalse()
        assertThat(credential.expiresAt).isAfter(java.time.Instant.now())
    }

    @Test
    fun `a valid cookie wins over a bearer token`() {
        val request = MockHttpServletRequest()
        request.setCookies(Cookie(AuthCookieNames.ACCESS_TOKEN, jwtService.generateToken(alice)))
        request.addHeader("Authorization", "Bearer ${jwtService.generateToken(bob)}")

        val credential = caller.resolve(request)!!

        assertThat(credential.principal).isEqualTo(alice)
        assertThat(credential.fromCookie).isTrue()
    }

    @Test
    fun `an invalid cookie falls back to the bearer token`() {
        val request = MockHttpServletRequest()
        request.setCookies(Cookie(AuthCookieNames.ACCESS_TOKEN, "not-a-token"))
        request.addHeader("Authorization", "Bearer ${jwtService.generateToken(bob)}")

        val credential = caller.resolve(request)!!

        assertThat(credential.principal).isEqualTo(bob)
        assertThat(credential.fromCookie).isFalse()
    }

    @Test
    fun `an invalid token or another scheme is no caller`() {
        val bad = MockHttpServletRequest()
        bad.addHeader("Authorization", "Bearer not-a-token")
        val basic = MockHttpServletRequest()
        basic.addHeader("Authorization", "Basic abc123")

        assertThat(caller.resolve(bad)).isNull()
        assertThat(caller.resolve(basic)).isNull()
    }
}
