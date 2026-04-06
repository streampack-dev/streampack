/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.entity

import dev.streampack.core.model.SecretRef
import dev.streampack.core.persistence.SecretRefConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Persistent Slack workspace configuration record */
@Entity
@Table(name = "slack_workspaces")
data class SlackWorkspace(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true, length = 100) val name: String = "",
    @Convert(converter = SecretRefConverter::class)
    @Column(nullable = false, length = 500)
    val botToken: SecretRef = SecretRef.literal(""),
    @Convert(converter = SecretRefConverter::class)
    @Column(nullable = false, length = 500)
    val appToken: SecretRef = SecretRef.literal(""),
    @Column(length = 10) val signalCharacter: String? = null,
    @Column(nullable = false) val autoconnect: Boolean = false,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
) {
    /** Summary string for status display */
    fun toSummary(): String {
        val autoFlag = if (autoconnect) "autoconnect" else "manual"
        return "$name ($autoFlag)"
    }
}
