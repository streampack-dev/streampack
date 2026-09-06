/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.entity

import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.subscription.SubscriptionEvents
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** Maps a GitHub repository to a notification destination, stored as a Provenance URI */
@Entity
@Table(name = "github_subscriptions")
data class GitHubSubscription(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    /* Eager: `github subscriptions` renders the repository after the service transaction ends (#66) */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "repo_id", nullable = false)
    val repo: GitHubRepo = GitHubRepo(),
    @Column(nullable = false, length = 2048) override val destinationUri: String = "",
    /** Base event kinds plus any pipeline filters; see [SubscriptionEvents] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    override val events: List<String> = SubscriptionEvents.BASE,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) override val active: Boolean = true,
) : ForgeSubscription
