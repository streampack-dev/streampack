/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.service

import dev.streampack.core.entity.MessageLog
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.entity.ModerationAction
import dev.streampack.moderation.entity.ModerationReport
import dev.streampack.moderation.model.ModerationActionResult
import dev.streampack.moderation.model.ModerationActionType
import dev.streampack.moderation.model.ModerationActionView
import dev.streampack.moderation.model.ModerationLine
import dev.streampack.moderation.model.ModerationLogDay
import dev.streampack.moderation.model.ReportDetail
import dev.streampack.moderation.model.ReportListResponse
import dev.streampack.moderation.model.ReportStatus
import dev.streampack.moderation.model.ReportSummary
import dev.streampack.moderation.model.VerdictView
import dev.streampack.moderation.repository.ModerationActionRepository
import dev.streampack.moderation.repository.ModerationReportRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What an admin does with reports and log lines (#150): read them, hide or unhide lines, purge
 * lines, dismiss a report. Every action is recorded with who did it and when. Nothing here runs on
 * its own; the callers check that the actor is an admin.
 *
 * Lines are read and changed through the moderation queries on [MessageLogRepository], which see
 * hidden lines and never touch a direct one.
 */
@Service
class ModerationService(
    private val reports: ModerationReportRepository,
    private val actions: ModerationActionRepository,
    private val messageLog: MessageLogRepository,
    private val properties: ModerationProperties,
) {
    private val logger = LoggerFactory.getLogger(ModerationService::class.java)

    /** No report has that id. */
    class ReportNotFoundException(id: UUID) : RuntimeException("No moderation report $id")

    /** Reports, open first, newest first; or only those in [status]. */
    @Transactional(readOnly = true)
    fun list(status: ReportStatus?, page: Int, size: Int): ReportListResponse {
        val pageable = PageRequest.of(page, size)
        val found =
            if (status == null) reports.findAllOpenFirst(pageable)
            else reports.findByStatusNewestFirst(status, pageable)
        return ReportListResponse(
            page = page,
            size = size,
            totalCount = found.totalElements,
            totalPages = found.totalPages,
            openCount = reports.countByStatus(ReportStatus.OPEN),
            reports = found.content.map(::summary),
        )
    }

    /** One report with its excerpt, hidden lines included and marked. */
    @Transactional(readOnly = true) fun detail(id: UUID): ReportDetail = detail(find(id))

    /**
     * One UTC day of a channel's log with hidden lines included and marked, for an admin to find
     * and unhide them. Direct lines are never included.
     */
    @Transactional(readOnly = true)
    fun logDay(provenanceUri: String, day: LocalDate): ModerationLogDay {
        val start = day.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val lines =
            messageLog.findWindowForModeration(provenanceUri, start, end, DAY_LIMIT).map {
                line(it, emptySet(), emptySet())
            }
        return ModerationLogDay(provenanceUri, day.toString(), lines)
    }

    /** Hides lines of a report's excerpt from public view, and marks the report actioned. */
    @Transactional
    fun hide(reportId: UUID, lineIds: List<UUID>, actor: UserPrincipal, note: String?) =
        onReport(reportId, lineIds, ModerationActionType.HIDE, actor, note) {
            messageLog.setHiddenForModeration(it, true)
        }

    /** Puts hidden lines of a report's excerpt back. The report's status stays as it is. */
    @Transactional
    fun unhide(reportId: UUID, lineIds: List<UUID>, actor: UserPrincipal, note: String?) =
        onReport(reportId, lineIds, ModerationActionType.UNHIDE, actor, note) {
            messageLog.setHiddenForModeration(it, false)
        }

    /**
     * Deletes lines of a report's excerpt for good, and marks the report actioned. Their ids stay
     * on the report and the action, so what was done can still be read.
     */
    @Transactional
    fun purge(reportId: UUID, lineIds: List<UUID>, actor: UserPrincipal, note: String?) =
        onReport(reportId, lineIds, ModerationActionType.PURGE, actor, note) {
            messageLog.purgeForModeration(it)
        }

    /** Closes an open report as not abuse. */
    @Transactional
    fun dismiss(reportId: UUID, actor: UserPrincipal, note: String?): ModerationActionResult {
        val report = find(reportId)
        require(report.status == ReportStatus.OPEN) { "The report is already ${report.status}." }
        val now = Instant.now()
        reports.save(close(report, ReportStatus.DISMISSED, actor, now))
        val action =
            record(
                report.id,
                report.provenanceUri,
                ModerationActionType.DISMISS,
                emptyList(),
                actor,
                note,
                now,
            )
        return ModerationActionResult(view(action), 0)
    }

    /** Hides or unhides lines found in a log day rather than a report. */
    @Transactional
    fun setHidden(
        lineIds: List<UUID>,
        hidden: Boolean,
        actor: UserPrincipal,
        note: String?,
    ): ModerationActionResult {
        val ids = checkIds(lineIds)
        val lines = messageLog.findForModeration(ids)
        require(lines.size == ids.size) { "Some of those lines don't exist." }
        val changed = messageLog.setHiddenForModeration(ids, hidden)
        val type = if (hidden) ModerationActionType.HIDE else ModerationActionType.UNHIDE
        val provenance = lines.map { it.provenanceUri }.distinct().singleOrNull()
        val action = record(null, provenance, type, ids, actor, note, Instant.now())
        logger.info("{} {} {} line(s)", actor.username, type, changed)
        return ModerationActionResult(view(action), changed)
    }

    private fun onReport(
        reportId: UUID,
        lineIds: List<UUID>,
        type: ModerationActionType,
        actor: UserPrincipal,
        note: String?,
        change: (List<UUID>) -> Int,
    ): ModerationActionResult {
        val report = find(reportId)
        val ids = checkIds(lineIds)
        val excerpt = report.excerptLineIds.toSet()
        require(ids.all { it.toString() in excerpt }) {
            "Only lines in the report's excerpt can be acted on from it."
        }
        val changed = change(ids)
        val now = Instant.now()
        if (type != ModerationActionType.UNHIDE) {
            reports.save(close(report, ReportStatus.ACTIONED, actor, now))
        }
        val action = record(report.id, report.provenanceUri, type, ids, actor, note, now)
        logger.info("{} {} {} line(s) of report {}", actor.username, type, changed, report.id)
        return ModerationActionResult(view(action), changed)
    }

    private fun checkIds(lineIds: List<UUID>): List<UUID> {
        val ids = lineIds.distinct()
        require(ids.isNotEmpty()) { "Name at least one line." }
        require(ids.size <= MAX_LINES) { "At most $MAX_LINES lines at a time." }
        return ids
    }

    private fun close(
        report: ModerationReport,
        status: ReportStatus,
        actor: UserPrincipal,
        now: Instant,
    ) =
        report.copy(
            status = status,
            actedBy = actor.username,
            actedById = actor.id,
            actedAt = now,
            updatedAt = now,
        )

    private fun record(
        reportId: UUID?,
        provenanceUri: String?,
        type: ModerationActionType,
        ids: List<UUID>,
        actor: UserPrincipal,
        note: String?,
        now: Instant,
    ): ModerationAction =
        actions.save(
            ModerationAction(
                reportId = reportId,
                action = type,
                provenanceUri = provenanceUri,
                lineIds = ids.map { it.toString() },
                note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE),
                actor = actor.username,
                actorId = actor.id,
                actedAt = now,
            )
        )

    private fun find(id: UUID): ModerationReport =
        reports.findById(id).orElseThrow { ReportNotFoundException(id) }

    private fun detail(report: ModerationReport): ReportDetail {
        val excerptIds = report.excerptLineIds.mapNotNull(::uuid)
        val found =
            if (excerptIds.isEmpty()) emptyList() else messageLog.findForModeration(excerptIds)
        val foundIds = found.map { it.id }.toSet()
        val flagged = report.flaggedLineIds.toSet()
        val cited = report.citedLineIds.toSet()
        return ReportDetail(
            report = summary(report),
            lines =
                found.map { log ->
                    val id = log.id.toString()
                    val weight = report.lineWeights?.get(id)?.takeIf { id in flagged }
                    line(log, flagged, cited)
                        .copy(
                            signals = report.lineSignals?.get(id)?.takeIf { id in flagged },
                            weight = weight,
                            strong = weight?.let { it >= properties.strongLineWeight },
                        )
                },
            purgedLineIds = excerptIds.filter { it !in foundIds },
            actions = actions.findByReportIdOrderByActedAtAsc(report.id).map(::view),
        )
    }

    private fun summary(report: ModerationReport) =
        ReportSummary(
            id = report.id,
            provenanceUri = report.provenanceUri,
            protocol = report.protocol,
            serviceId = report.serviceId,
            channel =
                runCatching { Provenance.decode(report.provenanceUri).replyTo }
                    .getOrDefault(report.provenanceUri),
            sender = report.sender,
            userId = report.userId,
            score = report.score,
            signals = report.signals,
            verdict =
                report.verdictAbusive?.let {
                    VerdictView(it, report.verdictReason, report.verdictModel)
                },
            status = report.status,
            windowStart = report.windowStart,
            windowEnd = report.windowEnd,
            createdAt = report.createdAt,
            actedBy = report.actedBy,
            actedAt = report.actedAt,
        )

    private fun line(log: MessageLog, flagged: Set<String>, cited: Set<String>) =
        ModerationLine(
            id = log.id,
            timestamp = log.timestamp,
            day = log.timestamp.atZone(ZoneOffset.UTC).toLocalDate().toString(),
            sender = log.sender,
            content = log.content,
            direction = log.direction,
            hidden = log.hidden,
            flagged = log.id.toString() in flagged,
            cited = log.id.toString() in cited,
        )

    private fun view(action: ModerationAction) =
        ModerationActionView(
            id = action.id,
            reportId = action.reportId,
            action = action.action,
            lineIds = action.lineIds.mapNotNull(::uuid),
            note = action.note,
            actor = action.actor,
            actedAt = action.actedAt,
        )

    private fun uuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    companion object {
        /** The most lines one action may name. */
        const val MAX_LINES = 500
        private const val MAX_NOTE = 2000
        private const val DAY_LIMIT = 5000
    }
}
