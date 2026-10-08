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
    /**
     * When the factoid was first set. Always filled in; optional in the spec only so clients built
     * before it keep reading (the Atlas tells new from changed by it, ui-pudl#184).
     */
    val createdAt: Instant? = null,
    val text: String?,
    val tags: List<String>,
)
