/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.entity

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Persistent Slack channel configuration associated with a workspace */
@Entity
@Table(name = "slack_channels")
data class SlackChannel(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    val workspace: SlackWorkspace = SlackWorkspace(),
    @Column(nullable = false, length = 200) val name: String = "",
    @Column(length = 50) val channelId: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
) {
    /** Builds the provenance URI for this channel */
    fun provenanceUri(): String =
        Provenance(Protocol.SLACK, workspace.name, replyTo = name).encode()
}
