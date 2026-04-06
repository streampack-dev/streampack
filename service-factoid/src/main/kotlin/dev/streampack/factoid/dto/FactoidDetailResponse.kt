/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.dto

import java.time.Instant

/** Full representation of a factoid with all its rendered attributes */
data class FactoidDetailResponse(
    val selector: String,
    val locked: Boolean,
    val updatedBy: String?,
    val updatedAt: Instant,
    val lastAccessedAt: Instant?,
    val accessCount: Long,
    val attributes: List<FactoidAttributeResponse>,
)

/** A single rendered attribute of a factoid */
data class FactoidAttributeResponse(val type: String, val value: String?, val rendered: String)
