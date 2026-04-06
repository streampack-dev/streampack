/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.streampack.core.model.UserPrincipal

/** Successful authentication result with JWT and resolved identity */
data class LoginResponse(
    val token: String,
    val principal: UserPrincipal,
    @JsonIgnore val refreshToken: String? = null,
)
