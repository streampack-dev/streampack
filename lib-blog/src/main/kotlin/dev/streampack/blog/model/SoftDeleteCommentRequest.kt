/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Admin request to soft-delete a comment (marks deleted, preserves thread structure) */
data class SoftDeleteCommentRequest(val id: UUID)
