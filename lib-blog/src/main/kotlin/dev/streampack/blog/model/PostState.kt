/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant

/**
 * Where a post is in its life, as a reader of its list entry sees it (#273). [PostStatus] keeps a
 * scheduled post as [PostStatus.APPROVED]; this tells it apart from a published one.
 */
enum class PostState {
    /** Not approved yet: the author's draft, or a submission in the review queue. */
    DRAFT,

    /** Approved, with a publication time in the future. */
    SCHEDULED,

    /** Approved, with a publication time now or in the past. */
    PUBLISHED;

    companion object {
        /**
         * The state of a post with [status] and [publishedAt], at the time [now].
         *
         * @param status the post's stored status
         * @param publishedAt the post's publication time, or null if it has none
         * @param now the time to compare [publishedAt] with
         * @return the post's state at [now]
         */
        fun of(status: PostStatus, publishedAt: Instant?, now: Instant): PostState =
            when {
                status != PostStatus.APPROVED -> DRAFT
                publishedAt == null || publishedAt.isAfter(now) -> SCHEDULED
                else -> PUBLISHED
            }
    }
}
