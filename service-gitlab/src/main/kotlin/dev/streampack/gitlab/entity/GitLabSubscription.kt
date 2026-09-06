/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.entity

import dev.streampack.forge.model.ForgeSubscription
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

/** Maps a GitLab project to a notification destination, stored as a Provenance URI */
@Entity
@Table(name = "gitlab_subscriptions")
data class GitLabSubscription(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    /* Eager: `gitlab subscriptions` renders the project after the service transaction ends */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "project_id", nullable = false)
    val project: GitLabProject = GitLabProject(),
    @Column(nullable = false, length = 2048) override val destinationUri: String = "",
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) override val active: Boolean = true,
) : ForgeSubscription
