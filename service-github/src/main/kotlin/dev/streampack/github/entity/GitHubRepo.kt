/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.entity

import dev.streampack.github.model.DeliveryMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A GitHub repository registered for watching */
@Entity
@Table(name = "github_repos")
data class GitHubRepo(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 255) val owner: String = "",
    @Column(nullable = false, length = 255) val name: String = "",
    @Column(length = 500) val token: String? = null,
    @Column(nullable = false) val highestIssueNumber: Int = 0,
    @Column(nullable = false) val highestPrNumber: Int = 0,
    @Column val lastPolledAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, name = "delivery_mode")
    val deliveryMode: DeliveryMode = DeliveryMode.POLLING,
    @Column(name = "webhook_secret", length = 2048) val webhookSecret: String? = null,
    @Column(name = "webhook_configured_at") val webhookConfiguredAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val active: Boolean = true,
) {
    /** Returns the "owner/name" identifier */
    fun fullName(): String = "$owner/$name"
}
