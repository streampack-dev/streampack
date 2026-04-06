/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import java.util.UUID

/** Single comment response returned from create and edit operations */
data class CommentDetail(
    val id: UUID,
    val postId: UUID,
    val authorDisplayName: String,
    val renderedHtml: String,
    val createdAt: Instant,
)
