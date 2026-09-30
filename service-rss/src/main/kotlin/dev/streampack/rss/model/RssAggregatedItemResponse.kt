/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

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
)
