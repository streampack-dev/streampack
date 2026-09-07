/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.entity

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

/** Persistent Mattermost channel configuration associated with a server. */
@Entity
@Table(name = "mattermost_channels")
data class MattermostChannel(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    val server: MattermostServer = MattermostServer(),
    @Column(nullable = false, length = 200) val name: String = "",
    @Column(nullable = false, length = 64) val channelId: String = "",
    @Column(length = 64) val teamId: String? = null,
    @Column(length = 10) val channelType: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
) {
    /** Builds the provenance URI for this channel. */
    fun provenanceUri(): String =
        Provenance(protocol = Protocol.MATTERMOST, serviceId = server.name, replyTo = channelId)
            .encode()
}
