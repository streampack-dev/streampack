/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.entity

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

/** Maps a GitHub repository to a notification destination, stored as a Provenance URI */
@Entity
@Table(name = "github_subscriptions")
data class GitHubSubscription(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    val repo: GitHubRepo = GitHubRepo(),
    @Column(nullable = false, length = 2048) val destinationUri: String = "",
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val active: Boolean = true,
)
