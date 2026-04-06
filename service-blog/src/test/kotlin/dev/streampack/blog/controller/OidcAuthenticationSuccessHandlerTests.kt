/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.model.LoginResponse
import dev.streampack.blog.service.CookieService
import dev.streampack.blog.service.UserConvergenceService
import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority

/** Verifies the OIDC success handler redirects to frontendUrl when set, or baseUrl as fallback */
class OidcAuthenticationSuccessHandlerTests {

    private val stubResponse =
        LoginResponse(
            token = "test-jwt-token",
            principal =
                UserPrincipal(
                    id = UUID.randomUUID(),
                    username = "testuser",
                    displayName = "Test User",
                    role = Role.USER,
                ),
            refreshToken = "test-refresh-token",
        )

    private fun mockConvergenceService(): UserConvergenceService {
        val service = mock(UserConvergenceService::class.java)
        `when`(service.converge("test@example.com", "Test User")).thenReturn(stubResponse)
        return service
    }

    private fun defaultCookieService(): CookieService {
        return CookieService(StreampackProperties())
    }

    private fun oidcAuthentication(email: String): Authentication {
        val idToken =
            OidcIdToken.withTokenValue("id-token")
                .claim("sub", "12345")
                .claim("email", email)
                .claim("name", "Test User")
                .build()
        val user = DefaultOidcUser(listOf(OidcUserAuthority(idToken)), idToken)
        return object : Authentication {
            override fun getName() = email

            override fun getAuthorities() = user.authorities

            override fun getCredentials() = null

            override fun getDetails() = null

            override fun getPrincipal() = user

            override fun isAuthenticated() = true

            override fun setAuthenticated(isAuthenticated: Boolean) {}
        }
    }

    @Test
    fun `redirects to frontendUrl without token in URL`() {
        val properties =
            StreampackProperties(
                baseUrl = "https://rest.bytecode.news",
                frontendUrl = "https://bytecode.news",
            )
        val handler =
            OidcAuthenticationSuccessHandler(
                mockConvergenceService(),
                defaultCookieService(),
                properties,
            )

        val response = MockHttpServletResponse()
        handler.onAuthenticationSuccess(
            MockHttpServletRequest(),
            response,
            oidcAuthentication("test@example.com"),
        )

        assertEquals("https://bytecode.news/auth/callback", response.redirectedUrl)
    }

    @Test
    fun `sets authentication cookies on response`() {
        val properties =
            StreampackProperties(
                baseUrl = "https://rest.bytecode.news",
                frontendUrl = "https://bytecode.news",
            )
        val handler =
            OidcAuthenticationSuccessHandler(
                mockConvergenceService(),
                defaultCookieService(),
                properties,
            )

        val response = MockHttpServletResponse()
        handler.onAuthenticationSuccess(
            MockHttpServletRequest(),
            response,
            oidcAuthentication("test@example.com"),
        )

        val setCookieHeaders = response.getHeaders("Set-Cookie")
        val accessHeader = setCookieHeaders.find { it.contains(CookieService.ACCESS_TOKEN_COOKIE) }
        assertNotNull(accessHeader)
        assertTrue(accessHeader!!.contains("test-jwt-token"))
        assertTrue(accessHeader.contains("HttpOnly"))

        val refreshHeader =
            setCookieHeaders.find { it.contains(CookieService.REFRESH_TOKEN_COOKIE) }
        assertNotNull(refreshHeader)
        assertTrue(refreshHeader!!.contains("test-refresh-token"))
        assertTrue(refreshHeader.contains("HttpOnly"))
    }

    @Test
    fun `falls back to baseUrl when frontendUrl is empty`() {
        val properties = StreampackProperties(baseUrl = "https://rest.bytecode.news")
        val handler =
            OidcAuthenticationSuccessHandler(
                mockConvergenceService(),
                defaultCookieService(),
                properties,
            )

        val response = MockHttpServletResponse()
        handler.onAuthenticationSuccess(
            MockHttpServletRequest(),
            response,
            oidcAuthentication("test@example.com"),
        )

        assertEquals("https://rest.bytecode.news/auth/callback", response.redirectedUrl)
    }
}
