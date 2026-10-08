/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.entity

import dev.streampack.taxonomy.model.TagHintKind
import dev.streampack.taxonomy.model.TagReviewStatus
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
 * A new tag that looks doubtful (#140), for an admin to decide on; the contributor is never asked.
 * One per tag. [hintKind] and [hintTags] are the rule-based hint; the `ai` fields are the
 * moderation model's near-miss answer, null when AI is off or found nothing.
 */
@Entity
@Table(name = "tag_review")
data class TagReview(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true) val tag: String = "",
    @Column(nullable = false) val firstSeen: Instant = Instant.now(),
    @Column(nullable = false, length = 50) val source: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val hintKind: TagHintKind = TagHintKind.PLURAL,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val hintTags: List<String> = emptyList(),
    val aiCandidate: String? = null,
    val aiConfidence: Double? = null,
    @Column(columnDefinition = "TEXT") val aiReason: String? = null,
    @Column(length = 100) val aiModel: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: TagReviewStatus = TagReviewStatus.OPEN,
    val actedBy: String? = null,
    val actedAt: Instant? = null,
)
