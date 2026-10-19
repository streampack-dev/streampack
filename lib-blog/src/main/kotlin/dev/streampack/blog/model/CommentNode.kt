/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import java.util.UUID

/** Tree node representing a comment in a threaded discussion */
data class CommentNode(
    val id: UUID,
    val authorId: UUID?,
    val authorDisplayName: String,
    val renderedHtml: String,
    val markdownSource: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deleted: Boolean,
    val editable: Boolean,
    val children: List<CommentNode>,
    /**
     * The passage of the post that the comment quotes, or null (#256). Null on a deleted comment.
     */
    val anchor: CommentAnchor? = null,
    /**
     * Whether [anchor]'s quote still occurs in the post, or null when there is no anchor. False
     * means an edit of the post removed the passage; the comment still shows its quote.
     */
    val anchorFound: Boolean? = null,
)
