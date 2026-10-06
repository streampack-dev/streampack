/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import dev.streampack.core.model.MessageDirection
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.SQLRestriction
import org.hibernate.annotations.UuidGenerator

/**
 * Immutable protocol-agnostic message log entry.
 *
 * A [direct] entry (from a private message, a DM or a group DM) is kept but never read: the
 * restriction hides it from every entity query, and the repository's native queries exclude it.
 */
@Entity
@SQLRestriction("direct = false")
@Table(name = "message_log")
data class MessageLog(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 500) val provenanceUri: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val direction: MessageDirection = MessageDirection.INBOUND,
    @Column(nullable = false, length = 255) val sender: String = "",
    @Column(nullable = false, columnDefinition = "TEXT") val content: String = "",
    @Column(nullable = false) val timestamp: Instant = Instant.now(),
    @Column(nullable = false) val direct: Boolean = false,
)
