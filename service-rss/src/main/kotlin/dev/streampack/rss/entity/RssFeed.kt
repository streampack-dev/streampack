/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** An RSS or Atom feed that has been registered for consumption */
@Entity
@Table(name = "rss_feeds")
data class RssFeed(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true, length = 2048) val feedUrl: String = "",
    @Column(length = 2048) val siteUrl: String? = null,
    @Column(nullable = false, length = 500) val title: String = "",
    @Column(length = 2000) val description: String? = null,
    @Column val lastFetchedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val active: Boolean = true,
)
