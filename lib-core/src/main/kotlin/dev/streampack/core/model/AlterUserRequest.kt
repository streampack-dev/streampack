/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/** Admin request to modify a user's fields. Only non-null fields are applied. */
data class AlterUserRequest(
    val username: String,
    val newUsername: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val role: Role? = null,
)
