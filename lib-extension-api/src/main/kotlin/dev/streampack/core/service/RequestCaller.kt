/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Credential
import jakarta.servlet.http.HttpServletRequest

/**
 * Finds who an HTTP request comes from (#302), as the server's own controllers do: the access-token
 * cookie if it is valid, else a bearer token. An extension's controller injects this to check
 * sign-in and [dev.streampack.core.model.Role].
 */
interface RequestCaller {
    /** The request's [Credential], or null when nobody valid is signed in. */
    fun resolve(request: HttpServletRequest): Credential?
}
