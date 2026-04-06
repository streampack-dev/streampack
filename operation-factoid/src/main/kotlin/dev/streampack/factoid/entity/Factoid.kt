/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Master factoid record keyed by a unique lowercase selector */
@Entity
@Table(name = "factoids")
data class Factoid(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true, length = 200) val selector: String = "",
    @Column(nullable = false) val locked: Boolean = false,
    @Column(length = 100) val updatedBy: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    val lastAccessedAt: Instant? = null,
    @Column(nullable = false) val accessCount: Long = 0,
)
