/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.entity

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

/** Tracks known release tags for a GitLab project */
@Entity
@Table(name = "gitlab_releases")
data class GitLabRelease(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    val project: GitLabProject = GitLabProject(),
    @Column(nullable = false, length = 255) val tag: String = "",
    @Column(length = 500) val name: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
