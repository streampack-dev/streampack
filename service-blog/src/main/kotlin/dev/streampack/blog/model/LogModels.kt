/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.core.model.MessageDirection
import java.time.Instant

data class LogProvenanceSummary(
    val provenanceUri: String,
    val protocol: String,
    val serviceId: String?,
    val replyTo: String,
    val latestTimestamp: Instant?,
    val latestSender: String?,
    val latestContentPreview: String?,
)

data class LogProvenanceListResponse(val provenances: List<LogProvenanceSummary>)

data class LogEntry(
    val timestamp: Instant,
    val sender: String,
    val content: String,
    val direction: MessageDirection,
)

data class LogDayResponse(val provenanceUri: String, val day: String, val entries: List<LogEntry>)

/** A line found by a search, with its UTC day, so a client can link to the day and the line. */
data class LogSearchHit(
    val timestamp: Instant,
    val day: String,
    val sender: String,
    val content: String,
    val direction: MessageDirection,
)

/** One page of a channel's search results, newest first. */
data class LogSearchResponse(
    val provenanceUri: String,
    val query: String,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
    val hits: List<LogSearchHit>,
)
