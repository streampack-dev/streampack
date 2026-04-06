/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Request to create a comment on a blog post */
data class CreateCommentRequest(
    val postId: UUID,
    val parentCommentId: UUID? = null,
    val markdownSource: String,
)
