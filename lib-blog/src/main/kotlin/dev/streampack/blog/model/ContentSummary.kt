/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** Lightweight post representation for listing pages */
data class ContentSummary(
    val id: UUID,
    val title: String,
    val slug: String,
    val excerpt: String?,
    val authorDisplayName: String,
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
)
