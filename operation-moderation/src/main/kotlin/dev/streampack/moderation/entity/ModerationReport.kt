/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.entity

import dev.streampack.moderation.model.ReportStatus
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
 * What the hourly review found about one person in one channel (#150), for an admin to decide on.
 *
 * [sender] is the name as the message log has it, under [provenanceUri]; [userId] is their account
 * when the protocol identity resolved to one. [signals] counts what raised their score. The verdict
 * fields are the moderation model's answer, and null when AI is off or didn't answer.
 * [excerptLineIds] are the message log lines the review read (theirs and the lines around them),
 * [flaggedLineIds] theirs that raised a signal, [citedLineIds] the ones the model pointed at. Line
 * ids are kept as strings, and stay after a purge removes the lines. [lineWeights] and
 * [lineSignals] say, per flagged line, what it added and which signals it raised (#169); both are
 * null on reports filed before they were kept.
 */
@Entity
@Table(name = "moderation_report")
data class ModerationReport(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 500) val provenanceUri: String = "",
    @Column(nullable = false, length = 20) val protocol: String = "",
    @Column(length = 255) val serviceId: String? = null,
    @Column(nullable = false, length = 255) val sender: String = "",
    val userId: UUID? = null,
    @Column(nullable = false) val score: Double = 0.0,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val signals: Map<String, Int> = emptyMap(),
    val verdictAbusive: Boolean? = null,
    @Column(columnDefinition = "TEXT") val verdictReason: String? = null,
    @Column(length = 100) val verdictModel: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val excerptLineIds: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val flaggedLineIds: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val citedLineIds: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val lineWeights: Map<String, Double>? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val lineSignals: Map<String, List<String>>? = null,
    @Column(nullable = false) val windowStart: Instant = Instant.now(),
    @Column(nullable = false) val windowEnd: Instant = Instant.now(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: ReportStatus = ReportStatus.OPEN,
    @Column(length = 255) val actedBy: String? = null,
    val actedById: UUID? = null,
    val actedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
)
