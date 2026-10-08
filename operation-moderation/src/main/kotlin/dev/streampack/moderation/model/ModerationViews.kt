/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.model

import dev.streampack.core.model.MessageDirection
import java.time.Instant
import java.util.UUID

/** The moderation model's answer on a report; absent when AI was off or didn't answer. */
data class VerdictView(val abusive: Boolean, val reason: String?, val model: String?)

/** A report as the admin list shows it. */
data class ReportSummary(
    val id: UUID,
    val provenanceUri: String,
    val protocol: String,
    val serviceId: String?,
    /** The channel's name or id, from the provenance. */
    val channel: String,
    /** Who, as the message log names them. */
    val sender: String,
    /** Their account, when their protocol identity resolved to one. */
    val userId: UUID?,
    /** The highest their score reached before the review. */
    val score: Double,
    /** How many lines raised each signal. */
    val signals: Map<String, Int>,
    val verdict: VerdictView?,
    val status: ReportStatus,
    /** The first and last line the review read. */
    val windowStart: Instant,
    val windowEnd: Instant,
    val createdAt: Instant,
    /** The admin who last acted on it, and when. */
    val actedBy: String?,
    val actedAt: Instant?,
)

/** A page of reports, open ones first, with how many are open in all. */
data class ReportListResponse(
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
    val openCount: Long,
    val reports: List<ReportSummary>,
)

/**
 * One message log line in an admin's view. [hidden] lines are out of public view; [flagged] ones
 * raised a signal; [cited] ones are those the model pointed at. [day] is its UTC day, for a link to
 * the log day.
 *
 * For a flagged line in a report, [signals] names what it raised, [weight] is what it added, and
 * [strong] says whether that's enough to suggest hiding it (#169). All three are null for a line
 * that isn't flagged, in a log day, or in a report filed before they were kept.
 */
data class ModerationLine(
    val id: UUID,
    val timestamp: Instant,
    val day: String,
    val sender: String,
    val content: String,
    val direction: MessageDirection,
    val hidden: Boolean,
    val flagged: Boolean,
    val cited: Boolean,
    val signals: List<String>? = null,
    val weight: Double? = null,
    val strong: Boolean? = null,
)

/** One thing an admin did: who, what, when. */
data class ModerationActionView(
    val id: UUID,
    val reportId: UUID?,
    val action: ModerationActionType,
    val lineIds: List<UUID>,
    val note: String?,
    val actor: String,
    val actedAt: Instant,
)

/**
 * A report with its excerpt: the lines the review read, in order, hidden ones included and marked.
 * [purgedLineIds] were in the excerpt and have since been purged. [actions] is everything done on
 * it, oldest first.
 */
data class ReportDetail(
    val report: ReportSummary,
    val lines: List<ModerationLine>,
    val purgedLineIds: List<UUID>,
    val actions: List<ModerationActionView>,
)

/** One UTC day of a channel's log as an admin sees it: hidden lines included, and marked. */
data class ModerationLogDay(
    val provenanceUri: String,
    val day: String,
    val lines: List<ModerationLine>,
)

/** What an action changed. */
data class ModerationActionResult(
    val action: ModerationActionView,
    /** How many lines changed: hidden, unhidden or purged. Lines already so aren't counted. */
    val changed: Int,
)
