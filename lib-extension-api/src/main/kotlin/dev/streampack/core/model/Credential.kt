/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import java.time.Instant

/**
 * Who an HTTP request comes from (#302): the signed-in [principal], when the sign-in ends
 * ([expiresAt]), and whether the browser's cookie carried it ([fromCookie]) rather than an
 * `Authorization: Bearer` header. A browser sends the cookie by itself, so a request that changes
 * something and comes [fromCookie] may need a check that it came from a trusted page.
 */
data class Credential(val principal: UserPrincipal, val expiresAt: Instant, val fromCookie: Boolean)
