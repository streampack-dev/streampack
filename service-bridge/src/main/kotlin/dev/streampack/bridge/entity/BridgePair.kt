/* Joseph B. Ottinger (C)2026 */
package dev.streampack.bridge.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/**
 * An exclusive directional bridge between two provenance endpoints.
 *
 * Each provenance URI can participate in at most one pair. The pair tracks which direction(s)
 * content flows: first-to-second, second-to-first, or both (full mirror).
 */
@Entity
@Table(name = "bridge_pair")
data class BridgePair(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 500) val firstUri: String = "",
    @Column(nullable = false, length = 500) val secondUri: String = "",
    @Column(nullable = false) val copyFirstToSecond: Boolean = false,
    @Column(nullable = false) val copySecondToFirst: Boolean = false,
    @Column(nullable = false) val deleted: Boolean = false,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
