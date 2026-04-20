/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import java.time.Instant

/** A registered RSS source projected for the public aggregator UI. */
data class RssFeedSourceResponse(
    val title: String,
    val feedUrl: String,
    val siteUrl: String?,
    val itemCount: Long,
    val latestItemTimestamp: Instant?,
)
