/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/** Request to create a comment on a blog post */
data class CreateCommentRequest(
    val postId: UUID,
    val parentCommentId: UUID? = null,
    val markdownSource: String,
    /** The passage of the post that the comment quotes, or null (#256). */
    val anchor: CommentAnchor? = null,
)
