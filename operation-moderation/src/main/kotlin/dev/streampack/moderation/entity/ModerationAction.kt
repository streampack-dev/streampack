/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.entity

import dev.streampack.moderation.model.ModerationActionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/**
 * One thing an admin did (#150): hid, unhid or purged lines, or dismissed a report. [reportId] is
 * null for lines acted on from a log day rather than a report. [lineIds] are message log line ids,
 * as strings; a purged line's id stays here after the line is gone.
 */
@Entity
@Table(name = "moderation_action")
data class ModerationAction(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    val reportId: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val action: ModerationActionType = ModerationActionType.HIDE,
    @Column(length = 500) val provenanceUri: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val lineIds: List<String> = emptyList(),
    @Column(columnDefinition = "TEXT") val note: String? = null,
    @Column(nullable = false, length = 255) val actor: String = "",
    val actorId: UUID? = null,
    @Column(nullable = false) val actedAt: Instant = Instant.now(),
)
