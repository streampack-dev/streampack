/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The passage of a post that a comment is about, as a W3C Web Annotation text quote selector.
 *
 * The passage is found by its text, not by its position, so an edit in another part of the post
 * does not move it. The text is the post's text as a reader sees it: tags removed, entities
 * decoded, and each run of whitespace made one space.
 *
 * @property exact the quoted text. It is 1 to 500 characters after its whitespace is collapsed.
 * @property prefix the post's text just before [exact], at most 64 characters, or null. A front end
 *   uses it to choose between two places where [exact] occurs.
 * @property suffix the post's text just after [exact], at most 64 characters, or null.
 */
@Schema(description = "The passage of the post that a comment quotes (a text quote selector)")
data class CommentAnchor(
    @field:Schema(
        description = "The quoted text, 1 to 500 characters",
        minLength = 1,
        maxLength = 500,
    )
    val exact: String,
    @field:Schema(
        description = "The post's text just before the quote, at most 64 characters",
        maxLength = 64,
    )
    val prefix: String? = null,
    @field:Schema(
        description = "The post's text just after the quote, at most 64 characters",
        maxLength = 64,
    )
    val suffix: String? = null,
)
