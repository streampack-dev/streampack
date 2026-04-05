/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/** Admin request to provision a new user account */
data class CreateUserRequest(
    val username: String,
    val email: String,
    val displayName: String,
    val role: Role = Role.USER,
)
