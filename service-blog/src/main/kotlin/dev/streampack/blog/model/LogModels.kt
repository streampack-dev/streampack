/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.core.model.MessageDirection
import dev.streampack.core.model.MessageKind
import java.time.Instant

data class LogProvenanceSummary(
    val provenanceUri: String,
    val protocol: String,
    val serviceId: String?,
    val replyTo: String,
    val latestTimestamp: Instant?,
    val latestSender: String?,
    val latestContentPreview: String?,
    /** The channel's readable address, `irc/libera/primate`, for `/logs/{path}` (#147) */
    val path: String? = null,
)

/** A channel found by its readable address: its provenance, and its path as the API writes it */
data class LogChannelResponse(val provenanceUri: String, val path: String)

data class LogProvenanceListResponse(val provenances: List<LogProvenanceSummary>)

data class LogEntry(
    val timestamp: Instant,
    val sender: String,
    val content: String,
    val direction: MessageDirection,
    /** What the line is: something said, or a join, part, quit, nick change or topic (#174) */
    val kind: MessageKind,
)

data class LogDayResponse(val provenanceUri: String, val day: String, val entries: List<LogEntry>)

/** A line found by a search, with its UTC day, so a client can link to the day and the line. */
data class LogSearchHit(
    val timestamp: Instant,
    val day: String,
    val sender: String,
    val content: String,
    val direction: MessageDirection,
    /** What the line is: something said, or a join, part, quit, nick change or topic (#174) */
    val kind: MessageKind,
)

/** One page of a channel's search results, newest first. */
data class LogSearchResponse(
    val provenanceUri: String,
    val query: String?,
    val sender: String?,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
    val hits: List<LogSearchHit>,
)
