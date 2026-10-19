/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.util.UUID

/**
 * HTTP request body for creating a comment (postId resolved from slug path)
 *
 * @property anchor the passage of the post that the comment quotes, or null (#256). Only a
 *   top-level comment can have one.
 */
data class CreateCommentHttpRequest(
    val parentCommentId: UUID? = null,
    val markdownSource: String,
    val anchor: CommentAnchor? = null,
)
