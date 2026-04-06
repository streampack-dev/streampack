/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssFeed
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface RssFeedRepository : JpaRepository<RssFeed, UUID> {
    fun findByFeedUrl(feedUrl: String): RssFeed?

    fun findBySiteUrl(siteUrl: String): RssFeed?

    fun findAllByActiveTrue(): List<RssFeed>
}
