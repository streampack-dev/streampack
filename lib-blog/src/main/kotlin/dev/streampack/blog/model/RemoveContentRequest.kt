/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Admin request to permanently delete a post (hard delete with FK cascade) */
data class RemoveContentRequest(val id: UUID)
