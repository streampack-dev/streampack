/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.dto

import java.time.Instant

/** Summary representation of a factoid for paginated listings */
data class FactoidSummaryResponse(
    val selector: String,
    val locked: Boolean,
    val updatedBy: String?,
    val updatedAt: Instant,
    val lastAccessedAt: Instant?,
    val accessCount: Long,
    val text: String?,
    val tags: List<String>,
)
