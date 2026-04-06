/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.entity

import dev.streampack.factoid.model.FactoidAttributeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** An attribute belonging to a factoid, with proper FK to the parent factoid */
@Entity
@Table(name = "factoid_attributes")
data class FactoidAttribute(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factoid_id", nullable = false)
    val factoid: Factoid = Factoid(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    val attributeType: FactoidAttributeType = FactoidAttributeType.TEXT,
    @Column(length = 512) val attributeValue: String? = null,
    @Column(length = 100) val updatedBy: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
)
