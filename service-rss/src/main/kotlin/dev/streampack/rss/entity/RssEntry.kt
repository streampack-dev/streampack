/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.entity

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

/** A single entry from an RSS or Atom feed, used as a baseline for new-entry detection */
@Entity
@Table(name = "rss_entries")
data class RssEntry(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feed_id", nullable = false)
    val feed: RssFeed = RssFeed(),
    @Column(nullable = false, length = 2048) val guid: String = "",
    @Column(nullable = false, length = 2048) val link: String = "",
    @Column(nullable = false, length = 500) val title: String = "",
    @Column val publishedAt: Instant? = null,
    @Column(nullable = false) val accessCount: Long = 0,
    @Column val lastAccessedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
