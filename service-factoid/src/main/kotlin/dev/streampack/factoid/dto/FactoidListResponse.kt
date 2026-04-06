/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.dto

/** Stable paginated response for factoid listings */
data class FactoidListResponse(
    val factoids: List<FactoidSummaryResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
)
