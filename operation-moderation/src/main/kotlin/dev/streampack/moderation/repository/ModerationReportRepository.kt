/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.repository

import dev.streampack.moderation.entity.ModerationReport
import dev.streampack.moderation.model.ReportStatus
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ModerationReportRepository : JpaRepository<ModerationReport, UUID> {
    /** Every report, open ones first, newest first within each. */
    @Query(
        value =
            """
            SELECT r FROM ModerationReport r
            ORDER BY CASE WHEN r.status = dev.streampack.moderation.model.ReportStatus.OPEN
                          THEN 0 ELSE 1 END,
                     r.createdAt DESC, r.id DESC
            """,
        countQuery = "SELECT count(r) FROM ModerationReport r",
    )
    fun findAllOpenFirst(pageable: Pageable): Page<ModerationReport>

    /** Reports in one state, newest first. */
    @Query(
        value =
            """
            SELECT r FROM ModerationReport r
            WHERE r.status = :status
            ORDER BY r.createdAt DESC, r.id DESC
            """,
        countQuery = "SELECT count(r) FROM ModerationReport r WHERE r.status = :status",
    )
    fun findByStatusNewestFirst(status: ReportStatus, pageable: Pageable): Page<ModerationReport>

    fun countByStatus(status: ReportStatus): Long
}
