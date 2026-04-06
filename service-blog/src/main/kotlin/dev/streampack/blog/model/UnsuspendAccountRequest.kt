/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Admin request to restore a suspended user account to active status */
data class UnsuspendAccountRequest(val username: String)
