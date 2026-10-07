/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** Lines to hide or unhide, with an optional note for the record. */
data class ModerationLinesRequest(
    @field:Schema(description = "Message log line ids, at most 500") val lineIds: List<UUID>,
    @field:Schema(description = "Why, for the record") val note: String? = null,
)

/**
 * Lines to purge. Purging can't be undone, so [confirm] must be true: a client asks the admin
 * first, and says so.
 */
data class ModerationPurgeRequest(
    @field:Schema(description = "Message log line ids, at most 500") val lineIds: List<UUID>,
    @field:Schema(description = "Must be true: the admin confirmed that this can't be undone")
    val confirm: Boolean = false,
    @field:Schema(description = "Why, for the record") val note: String? = null,
)

/** Dismissing a report as not abuse, with an optional note for the record. */
data class ModerationDismissRequest(
    @field:Schema(description = "Why, for the record") val note: String? = null
)
