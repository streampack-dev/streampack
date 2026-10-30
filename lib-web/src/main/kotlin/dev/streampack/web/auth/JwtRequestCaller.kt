/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.auth

import dev.streampack.core.model.Credential
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.RequestCaller
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * The server's [RequestCaller] (#302), used by its own controllers through [UserAwareController]
 * and injected into extensions' controllers. A valid access-token cookie wins over a bearer token;
 * an invalid cookie falls back to the bearer token.
 */
@Component
class JwtRequestCaller(private val jwtService: JwtService) : RequestCaller {
    override fun resolve(request: HttpServletRequest): Credential? {
        val cookieToken = request.cookies?.find { it.name == AuthCookieNames.ACCESS_TOKEN }?.value
        if (cookieToken != null) {
            jwtService.validate(cookieToken)?.let {
                return Credential(it.principal, it.expiresAt, fromCookie = true)
            }
        }
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return jwtService.validate(header.substring(7))?.let {
            Credential(it.principal, it.expiresAt, fromCookie = false)
        }
    }
}
