/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.entity

import dev.streampack.core.model.SecretRef
import dev.streampack.core.persistence.SecretRefConverter
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeProject
import jakarta.persistence.Column
import jakarta.persistence.Convert
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
    @Convert(converter = SecretRefConverter::class)
    @Column(length = 500)
    override val token: SecretRef? = null,
    @Column(nullable = false) override val highestIssueNumber: Int = 0,
    @Column(nullable = false) val highestPrNumber: Int = 0,
    @Column val lastPolledAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, name = "delivery_mode")
    override val deliveryMode: DeliveryMode = DeliveryMode.POLLING,
    @Column(name = "webhook_secret", length = 2048) override val webhookSecret: String? = null,
    @Column(name = "webhook_configured_at") val webhookConfiguredAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) override val active: Boolean = true,
) : ForgeProject {
    /** Returns the "owner/name" identifier */
    fun fullName(): String = "$owner/$name"

    override val displayName: String
        get() = fullName()

    override val highestChangeRequestNumber: Int
        get() = highestPrNumber
}
