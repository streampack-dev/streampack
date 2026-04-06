/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Admin request to soft-delete a post (hides from public, visible to admins) */
data class SoftDeleteContentRequest(val id: UUID)
