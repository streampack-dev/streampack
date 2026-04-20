/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

/** Response for active RSS sources available to the public aggregator UI. */
data class RssFeedSourcesResponse(val feeds: List<RssFeedSourceResponse>)
