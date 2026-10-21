/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.model.CommentAnchor
import dev.streampack.blog.repository.PostRepository
import java.util.UUID
import org.jsoup.nodes.Entities
import org.springframework.stereotype.Component

/**
 * Checks, stores and shows the passage that a comment quotes (#256).
 *
 * A comment's anchor is a W3C Web Annotation text quote selector: the quoted text, and some of the
 * post's text on each side of it. This class makes the anchor's whitespace the same as the post's
 * text, checks the anchor against the post, and builds the quote that goes at the start of the
 * comment's HTML.
 *
 * The post's text comes from [PostRepository.findReaderText], which uses the database function
 * `html_text`. The check when a comment is made and the check when comments are read use that one
 * function, so they can't disagree.
 *
 * @param postRepository gives the post's text.
 * @param sanitizer cleans the comment's HTML after the quote is added.
 * @constructor Makes the component.
 */
@Component
class CommentAnchors(
    private val postRepository: PostRepository,
    private val sanitizer: RenderedHtmlSanitizer,
) {

    /** The outcome of [check]. */
    sealed interface Checked {
        /**
         * The anchor is correct.
         *
         * @property anchor the anchor with its whitespace collapsed, as it is to be stored.
         */
        data class Accepted(val anchor: CommentAnchor) : Checked

        /**
         * The anchor is not correct.
         *
         * @property reason why, in a sentence for the person who wrote the comment. It never
         *   contains "not found", because the HTTP layer would then answer 404 instead of 400.
         */
        data class Refused(val reason: String) : Checked
    }

    /**
     * Collapses the anchor's whitespace, then checks it against the post.
     *
     * The anchor is accepted only when all of these are true:
     * - `exact` has 1 to [MAX_EXACT] characters after its whitespace is collapsed and trimmed;
     * - `prefix` and `suffix` each have at most [MAX_CONTEXT] characters after collapsing;
     * - `exact` occurs in the post's current text.
     *
     * This does not check that `prefix` and `suffix` are next to `exact` in the post. They only
     * help a front end to choose between repeats, so a wrong one does no harm.
     *
     * @param anchor the anchor that the client sent.
     * @param postId the post that the comment is on.
     * @return [Checked.Accepted] with the anchor to store, or [Checked.Refused] with the reason.
     */
    fun check(anchor: CommentAnchor, postId: UUID): Checked {
        val clean = normalize(anchor)
        if (clean.exact.isEmpty()) return Checked.Refused("The quoted passage is empty")
        if (clean.exact.length > MAX_EXACT) {
            return Checked.Refused("The quoted passage is longer than $MAX_EXACT characters")
        }
        if (
            (clean.prefix?.length ?: 0) > MAX_CONTEXT || (clean.suffix?.length ?: 0) > MAX_CONTEXT
        ) {
            return Checked.Refused("The quote's context is longer than $MAX_CONTEXT characters")
        }
        val text = postRepository.findReaderText(postId).orEmpty()
        if (!text.contains(clean.exact)) {
            return Checked.Refused("The quoted passage isn't in the post")
        }
        return Checked.Accepted(clean)
    }

    /**
     * Tells whether a quote still occurs in a post's text.
     *
     * Call this after the post is edited, to find comments whose passage the edit removed.
     *
     * @param exact the stored quote. It must already be collapsed, as [check] stores it.
     * @param postText the post's text, from [PostRepository.findReaderText].
     * @return true when [postText] contains [exact].
     */
    fun found(exact: String, postText: String): Boolean = postText.contains(exact)

    /**
     * Builds a comment's HTML: the quote first, when there is one, then the comment's own HTML.
     *
     * The quote is `<blockquote class="comment-anchor"><p>…</p></blockquote>`. Its text is escaped,
     * so HTML in a quote shows as text and is never run. Every front end shows the quote with no
     * change. A front end that draws callouts can hide this element.
     *
     * The result goes through [RenderedHtmlSanitizer] again. This makes its escaping the same as
     * the sanitizer's, so the startup backfill, which runs the sanitizer over stored comments,
     * leaves the comment as it is.
     *
     * @param exact the stored quote, or null when the comment quotes nothing.
     * @param commentHtml the comment's Markdown, already rendered and sanitized.
     * @return the HTML to store as the comment's `renderedHtml`.
     */
    fun html(exact: String?, commentHtml: String): String =
        if (exact == null) {
            commentHtml
        } else {
            sanitizer.sanitize(
                "<blockquote class=\"comment-anchor\"><p>${Entities.escape(exact)}</p></blockquote>$commentHtml",
            )
        }

    /**
     * Returns the anchor stored on a comment.
     *
     * @param comment the comment.
     * @return the anchor, or null when the comment quotes nothing.
     */
    fun of(comment: Comment): CommentAnchor? =
        comment.anchorExact?.let { CommentAnchor(it, comment.anchorPrefix, comment.anchorSuffix) }

    /**
     * Makes the anchor's whitespace match the post's text.
     *
     * The database function `html_text` makes each run of whitespace one space, but a browser's
     * selection keeps newlines and tabs. So each run of whitespace becomes one space here too.
     * `exact` is also trimmed, because a selection often takes a space at an end. `prefix` and
     * `suffix` are not trimmed, because their outer spaces are part of the post's text. An empty
     * `prefix` or `suffix` becomes null.
     *
     * @param anchor the anchor as the client sent it.
     * @return the anchor with its whitespace collapsed.
     */
    private fun normalize(anchor: CommentAnchor): CommentAnchor =
        CommentAnchor(
            exact = anchor.exact.replace(WHITESPACE, " ").trim(),
            prefix = anchor.prefix?.replace(WHITESPACE, " ")?.ifEmpty { null },
            suffix = anchor.suffix?.replace(WHITESPACE, " ")?.ifEmpty { null },
        )

    companion object {
        /** The most characters a quote can have, after its whitespace is collapsed. */
        const val MAX_EXACT = 500

        /** The most characters a prefix or a suffix can have, after collapsing. */
        const val MAX_CONTEXT = 64

        /**
         * A run of whitespace, as the database's `\s` matches it in `html_text` (V78) under the
         * image's en_US.utf8 locale: ASCII whitespace, U+1680, U+2000 to U+2006, U+2008 to U+200A,
         * U+2028, U+2029, U+205F and U+3000 (#258). Also U+00A0, which the post's text has as a
         * space (`&nbsp;`) and a browser gives back as U+00A0. U+0085, U+2007, U+202F, U+200B and
         * U+FEFF are not whitespace there. The same set as clientjs's `WHITESPACE`.
         */
        private val WHITESPACE =
            Regex(
                "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]+",
            )
    }
}
