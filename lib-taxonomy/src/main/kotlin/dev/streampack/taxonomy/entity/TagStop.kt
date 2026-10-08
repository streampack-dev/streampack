/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** A term on the stoplist (#140): dropped from every tag list that's written. */
@Entity
@Table(name = "tag_stop")
data class TagStop(
    @Id val term: String = "",
    @Column(nullable = false) val createdBy: String = "",
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
