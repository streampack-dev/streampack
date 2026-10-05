/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.controller

import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.web.auth.AuthCookieNames
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant

/** Base class for controllers that need to resolve the authenticated user from a JWT */
abstract class UserAwareController(private val jwtService: JwtService) {

    /** Extracts and validates the JWT from cookies first, then the Authorization header */
    protected fun resolveUser(request: HttpServletRequest): UserPrincipal? =
        resolveCredential(request)?.principal

    /**
     * The request's credential: the access-token cookie if it's valid, else a bearer token. A valid
     * cookie wins over a bearer header, as [resolveUser] has it; [fromCookie] says which was used,
     * since a cookie is sent by the browser on its own and so needs CSRF checks a bearer token
     * doesn't.
     */
    protected fun resolveCredential(request: HttpServletRequest): Credential? {
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

    /** Who a request's credential names, until when, and whether it came from the cookie. */
    data class Credential(
        val principal: UserPrincipal,
        val expiresAt: Instant,
        val fromCookie: Boolean,
    )
}
