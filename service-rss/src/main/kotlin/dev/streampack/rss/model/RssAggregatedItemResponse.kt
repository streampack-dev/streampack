/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** A stored RSS entry projected for the public aggregator API. */
data class RssAggregatedItemResponse(
    val id: UUID,
    val feedTitle: String,
    val feedUrl: String,
    val siteUrl: String?,
    val guid: String,
    val link: String,
    val title: String,
    /** The feed's own summary of the item, as plain text; absent when it gave none (#98). */
    val summary: String? = null,
    val publishedAt: Instant?,
    val receivedAt: Instant,
    /** The feed's own tags for the item, as written; empty when it gave none (#139). */
    @field:Schema(
        description =
            "The feed's own tags for the item (RSS category, Atom category term), as written; " +
                "empty when it gave none or the item was stored before tags were kept"
    )
    val categories: List<String>,
    /** The BCN tags the feed's own tags map to (#139). */
    @field:Schema(
        description =
            "The BCN tags the item's feed tags map to: a tag of the same name, or an alias's tag. " +
                "Stoplisted and waiting feed tags map to nothing. Feed items don't count toward " +
                "tag counts or the taxonomy."
    )
    val tags: List<String>,
)
