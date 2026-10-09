/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.entity

import dev.streampack.rss.model.FeedTagStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A feed tag the vocabulary didn't know (#139), and what became of it: [FeedTagStatus.WAITING]
 * until it has earned a place or an admin decides it. [entries] and [feeds] are how many entries,
 * across how many feeds, carried it when it was last seen; [tag] is the BCN tag it became.
 */
@Entity
@Table(name = "rss_feed_tag")
data class RssFeedTag(
    @Id val name: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: FeedTagStatus = FeedTagStatus.WAITING,
    @Column(nullable = false) val entries: Int = 0,
    @Column(nullable = false) val feeds: Int = 0,
    @Column(nullable = false) val firstSeen: Instant = Instant.now(),
    @Column(nullable = false) val lastSeen: Instant = Instant.now(),
    val tag: String? = null,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
)
