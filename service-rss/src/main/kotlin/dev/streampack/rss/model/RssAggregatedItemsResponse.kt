/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

/** Paginated response for aggregated stored RSS entries. */
data class RssAggregatedItemsResponse(
    val items: List<RssAggregatedItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
)
