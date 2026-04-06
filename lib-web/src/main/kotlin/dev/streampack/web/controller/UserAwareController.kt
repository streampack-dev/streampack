/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.controller

import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import jakarta.servlet.http.HttpServletRequest

/** Base class for controllers that need to resolve the authenticated user from a JWT */
abstract class UserAwareController(private val jwtService: JwtService) {

    /** Extracts and validates the JWT from cookies first, then the Authorization header */
    protected fun resolveUser(request: HttpServletRequest): UserPrincipal? {
        val cookieToken = request.cookies?.find { it.name == "access_token" }?.value
        if (cookieToken != null) {
            val principal = jwtService.validateToken(cookieToken)
            if (principal != null) return principal
        }
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        val token = header.substring(7)
        return jwtService.validateToken(token)
    }
}
