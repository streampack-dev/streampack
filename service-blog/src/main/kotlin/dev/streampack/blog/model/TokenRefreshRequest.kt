/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Request to issue a fresh JWT from an existing valid token or a validated user ID */
data class TokenRefreshRequest(val token: String = "", val userId: java.util.UUID? = null)
