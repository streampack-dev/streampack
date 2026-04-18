/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import java.time.Instant

/** A stored RSS entry projected for the public aggregator API. */
data class RssAggregatedItemResponse(
    val feedTitle: String,
    val feedUrl: String,
    val siteUrl: String?,
    val guid: String,
    val link: String,
    val title: String,
    val publishedAt: Instant?,
)
