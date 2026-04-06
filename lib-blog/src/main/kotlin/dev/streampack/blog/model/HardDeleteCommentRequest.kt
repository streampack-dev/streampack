/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Admin request to permanently delete a comment (DB cascades to child comments) */
data class HardDeleteCommentRequest(val id: UUID)
