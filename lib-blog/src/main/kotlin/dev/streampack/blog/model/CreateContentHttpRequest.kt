/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** HTTP request body for creating a post (separates JSON binding from operation payload) */
@Schema(description = "Request body for creating a new blog post draft")
data class CreateContentHttpRequest(
    @Schema(description = "Post title", example = "Understanding Virtual Threads", required = true)
    val title: String = "",
    @Schema(
        description = "Post content in Markdown. No raw HTML allowed.",
        example = "Virtual threads change the concurrency model...",
        required = true,
    )
    val markdownSource: String = "",
    @Schema(
        description = "Tag names to associate with the post. Created automatically if new.",
        example = "[\"java\", \"concurrency\"]",
        required = false,
    )
    val tags: List<String>? = emptyList(),
    @Schema(
        description = "Category UUIDv7s to associate. Non-existent or deleted IDs are skipped.",
        required = false,
    )
    val categoryIds: List<UUID>? = emptyList(),
    @Schema(
        description =
            "Optional manual summary text from the UI. This value is persisted as the post " +
                "excerpt. If omitted or blank, the backend derives excerpt text heuristically " +
                "from markdown content.",
        required = false,
    )
    val summary: String? = null,
    @Schema(
        description =
            "Honeypot field for spam prevention. Frontends render this as a CSS-hidden " +
                "input. Legitimate users never see or fill it. Must be empty or absent.",
        hidden = true,
    )
    val website: String? = null,
    @Schema(
        description =
            "Epoch milliseconds when the form was rendered (Date.now()). " +
                "Required. Submissions arriving less than 3 seconds after this timestamp " +
                "are silently rejected.",
        example = "1709740800000",
        required = true,
    )
    val formLoadedAt: Long? = null,
)
