/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssFeed
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface RssFeedRepository : JpaRepository<RssFeed, UUID> {
    fun findByFeedUrl(feedUrl: String): RssFeed?

    fun findBySiteUrl(siteUrl: String): RssFeed?

    fun findAllByActiveTrue(): List<RssFeed>

    /** Active feeds due at or before [now], oldest due first; page size bounds the batch */
    fun findByActiveTrueAndNextPollAtLessThanEqualOrderByNextPollAtAsc(
        now: Instant,
        pageable: Pageable,
    ): List<RssFeed>
}
