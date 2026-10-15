/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import io.swagger.v3.oas.annotations.media.Schema

/** Why a search result matched: a short passage with the matching words marked (#226). */
@Schema(
    description =
        "Why a search result matched: a short passage from the post, as plain text, with the " +
            "words that matched marked by offsets. Search stems words, so a marked word may differ " +
            "from the one searched for (`emerging` for `emergent`); a quoted word matches exactly.",
)
data class SearchMatch(
    @Schema(
        description = "Where the passage comes from: `body`, `excerpt`, `title` or `tags`.",
        example = "body",
    )
    val source: String,
    @Schema(
        description =
            "The passage, as plain text with no markup. ` … ` joins separate extracts, and " +
                "starts or ends one cut from a longer text.",
        example = "An emerging runtime, call it oden, could use the same modules.",
    )
    val text: String,
    @Schema(description = "The matching words in `text`, in order, without overlaps.")
    val marks: List<SearchMark>,
)

/** One marked range of a [SearchMatch]'s text. */
@Schema(
    description =
        "A range of `text`: from `start` up to, not including, `end`, counted in UTF-16 code " +
            "units, as JavaScript's `String.prototype.slice` counts.",
)
data class SearchMark(
    @Schema(example = "3") val start: Int,
    @Schema(example = "11") val end: Int,
)
