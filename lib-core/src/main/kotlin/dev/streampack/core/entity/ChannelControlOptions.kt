/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Protocol-agnostic channel governance flags, keyed by provenance URI */
@Entity
@Table(name = "channel_control_options")
data class ChannelControlOptions(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 500) val provenanceUri: String = "",
    @Column(nullable = false) val autojoin: Boolean = false,
    @Column(nullable = false) val automute: Boolean = false,
    @Column(nullable = false) val visible: Boolean = true,
    @Column(nullable = false) val logged: Boolean = true,
    @Column(nullable = false) val active: Boolean = true,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
)
