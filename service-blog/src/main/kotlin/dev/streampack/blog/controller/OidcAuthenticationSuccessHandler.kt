/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.service.CookieService
import dev.streampack.blog.service.UserConvergenceService
import dev.streampack.core.config.StreampackProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

/**
 * Handles successful OIDC/OAuth2 authentication by converging the external identity to a local user
 * and redirecting to the frontend with authentication cookies.
 */
@Component
class OidcAuthenticationSuccessHandler(
    private val userConvergenceService: UserConvergenceService,
    private val cookieService: CookieService,
    properties: StreampackProperties,
) : AuthenticationSuccessHandler {
    private val logger = LoggerFactory.getLogger(OidcAuthenticationSuccessHandler::class.java)
    private val frontendUrl = properties.frontendUrl.ifEmpty { properties.baseUrl }

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val (email, displayName) = extractUserInfo(authentication)

        logger.info("OIDC authentication succeeded for {}", email)
        val loginResponse = userConvergenceService.converge(email, displayName)

        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieService.createAccessTokenCookie(loginResponse.token).toString(),
        )
        loginResponse.refreshToken?.let {
            response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookieService.createRefreshTokenCookie(it).toString(),
            )
        }
        response.sendRedirect("$frontendUrl/auth/callback")
    }

    /** Extracts email and display name from either an OIDC or plain OAuth2 principal */
    private fun extractUserInfo(authentication: Authentication): Pair<String, String?> {
        val principal = authentication.principal

        if (principal is OidcUser) {
            val email =
                principal.email
                    ?: throw IllegalStateException("OIDC provider did not return an email")
            val displayName = principal.fullName ?: principal.preferredUsername
            return email to displayName
        }

        if (principal is OAuth2User) {
            /* GitHub returns email in the attributes, not as a standard OIDC claim */
            val email =
                principal.getAttribute<String>("email")
                    ?: throw IllegalStateException("OAuth2 provider did not return an email")
            val displayName = principal.getAttribute<String>("name") ?: principal.name
            return email to displayName
        }

        throw IllegalStateException("Unexpected principal type: ${principal?.javaClass?.name}")
    }
}
