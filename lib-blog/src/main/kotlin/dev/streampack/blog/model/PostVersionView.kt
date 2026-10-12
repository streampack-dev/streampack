/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * A post's version as the post API shows it (#196): the post in another form, for now a recording
 * of it. Front ends show the kinds they know and ignore the rest.
 */
@Schema(description = "A version of a post: the post in another form, such as a recording of it")
data class PostVersionView(
    @Schema(description = "What it is; `audio` is the only kind so far", example = "audio")
    val kind: String,
    @Schema(description = "Which one of its kind, when a kind has several; null for audio")
    val variant: String?,
    @Schema(description = "Its media type", example = "audio/mpeg") val mediaType: String,
    @Schema(description = "Its size in bytes") val size: Long,
    @Schema(description = "Its length in whole seconds, when known") val duration: Int?,
    @Schema(
        description =
            "Where to fetch it: an absolute address on the API's own origin, stable while the " +
                "version is replaced. Supports Range requests. Front ends must not rewrite it.",
        example =
            "https://api.bytecode.news/media/posts/0190a1b2-0000-7000-8000-000000000000/audio",
    )
    val url: String,
    @Schema(description = "A note about it, such as \"read by the author\"") val note: String?,
    @Schema(description = "Who made it: the uploader's display name, when known")
    val maker: String?,
    @Schema(
        description =
            "Whether it was made from the post as it is now: false once the post's markdown has " +
                "been edited since it was attached"
    )
    val current: Boolean,
    @Schema(description = "When it was first attached") val createdAt: Instant,
    @Schema(description = "When its content was last replaced; null if never")
    val replacedAt: Instant?,
)
