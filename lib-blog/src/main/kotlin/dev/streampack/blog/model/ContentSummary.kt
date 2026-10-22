/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.core.model.Role
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** Lightweight post representation for listing pages */
data class ContentSummary(
    val id: UUID,
    val title: String,
    val slug: String,
    val excerpt: String?,
    /** The author's user id (#273), or null for an anonymous post. */
    @Schema(
        description = "The author's user id, for `GET /posts?author=`; null for an anonymous post.",
        nullable = true,
    )
    val authorId: UUID? = null,
    val authorDisplayName: String,
    /** The author's role (#273), or null for an anonymous post. */
    @Schema(description = "The author's role; null for an anonymous post.", nullable = true)
    val authorRole: Role? = null,
    val publishedAt: Instant?,
    val sortOrder: Int = 0,
    val commentCount: Int = 0,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    /**
     * The kinds of version the post has (#200), such as `audio` for a recording, so a list can mark
     * them; the versions themselves are in the post's details.
     */
    @Schema(
        description =
            "The kinds of version the post has, sorted, such as `audio` for a recording of it; " +
                "empty when it has none or media is off. The versions themselves are in the " +
                "post's details.",
        example = "[\"audio\"]",
    )
    val versionKinds: List<String> = emptyList(),
    /** Where the post is in its life (#273): draft, scheduled or published. */
    @Schema(
        description =
            "Where the post is in its life: `DRAFT`, `SCHEDULED` or `PUBLISHED`. Public lists " +
                "carry only published posts; `GET /auth/posts` carries all three.",
    )
    val state: PostState = PostState.PUBLISHED,
    /** In search results only: why the post matched (#226). */
    @Schema(
        description =
            "In search results only: a passage showing why the post matched, with the matching " +
                "words marked. Null in other listings.",
        nullable = true,
    )
    val match: SearchMatch? = null,
)
