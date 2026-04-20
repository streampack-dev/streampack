/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RssEntryRepository : JpaRepository<RssEntry, UUID>, JpaSpecificationExecutor<RssEntry> {
    /** Find entries by feed and a batch of guids, used for dedup during polling */
    fun findByFeedAndGuidIn(feed: RssFeed, guids: List<String>): List<RssEntry>

    fun countByFeed(feed: RssFeed): Long

    @Query(
        "select max(coalesce(entry.publishedAt, entry.createdAt)) from RssEntry entry where entry.feed = :feed"
    )
    fun findLatestItemTimestamp(@Param("feed") feed: RssFeed): Instant?
}
