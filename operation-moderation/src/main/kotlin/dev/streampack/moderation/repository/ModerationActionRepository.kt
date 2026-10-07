/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.repository

import dev.streampack.moderation.entity.ModerationAction
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ModerationActionRepository : JpaRepository<ModerationAction, UUID> {
    fun findByReportIdOrderByActedAtAsc(reportId: UUID): List<ModerationAction>
}
