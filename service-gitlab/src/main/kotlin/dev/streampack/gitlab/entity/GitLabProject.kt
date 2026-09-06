/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.entity

import dev.streampack.core.model.SecretRef
import dev.streampack.core.persistence.SecretRefConverter
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeProject
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/**
 * A GitLab project registered for watching on one [GitLabInstance]. [projectId] is GitLab's numeric
 * id, used to match webhook deliveries; it is null for projects registered in private webhook mode
 * without an API lookup.
 */
@Entity
@Table(name = "gitlab_projects")
data class GitLabProject(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    /* Eager: the webhook controller reads the host outside any session */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "instance_id", nullable = false)
    override val instance: GitLabInstance = GitLabInstance(),
    @Column(nullable = false, length = 1024, name = "full_path") val fullPath: String = "",
    @Column(name = "gitlab_project_id") val projectId: Long? = null,
    @Convert(converter = SecretRefConverter::class)
    @Column(length = 500)
    override val token: SecretRef? = null,
    @Column(nullable = false) override val highestIssueNumber: Int = 0,
    @Column(nullable = false, name = "highest_mr_number") val highestMrNumber: Int = 0,
    @Column override val lastPolledAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, name = "delivery_mode")
    override val deliveryMode: DeliveryMode = DeliveryMode.POLLING,
    @Column(name = "webhook_secret", length = 2048) override val webhookSecret: String? = null,
    @Column(name = "webhook_configured_at") val webhookConfiguredAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) override val active: Boolean = true,
) : ForgeProject {
    override val path: String
        get() = fullPath

    /** `group/project` on gitlab.com; `host group/project` on any other instance */
    override val displayName: String
        get() = if (instance.isDefault) fullPath else "${instance.host} $fullPath"

    override val highestChangeRequestNumber: Int
        get() = highestMrNumber
}
