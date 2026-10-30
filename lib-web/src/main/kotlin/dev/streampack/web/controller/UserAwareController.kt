/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.controller

import dev.streampack.core.model.Credential
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.web.auth.JwtRequestCaller
import jakarta.servlet.http.HttpServletRequest

/**
 * Base class for controllers that need to resolve the authenticated user from a JWT. The lookup is
 * [JwtRequestCaller], the same one extensions inject as a RequestCaller (#302).
 */
abstract class UserAwareController(jwtService: JwtService) {
    private val caller = JwtRequestCaller(jwtService)

    /** Extracts and validates the JWT from cookies first, then the Authorization header */
    protected fun resolveUser(request: HttpServletRequest): UserPrincipal? =
        resolveCredential(request)?.principal

    /**
     * The request's credential: the access-token cookie if it's valid, else a bearer token.
     * [Credential.fromCookie] says which was used, since a cookie is sent by the browser on its own
     * and so needs CSRF checks a bearer token doesn't.
     */
    protected fun resolveCredential(request: HttpServletRequest): Credential? =
        caller.resolve(request)
}
