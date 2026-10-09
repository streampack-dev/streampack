/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssFeedSubscription
import java.util.UUID
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface RssFeedSubscriptionRepository : JpaRepository<RssFeedSubscription, UUID> {
    fun findByFeedAndDestinationUri(feed: RssFeed, destinationUri: String): RssFeedSubscription?

    fun findByFeedAndActiveTrue(feed: RssFeed): List<RssFeedSubscription>

    /** Loads each feed too: callers read its title after the session has closed. */
    @EntityGraph(attributePaths = ["feed"])
    fun findByDestinationUriAndActiveTrue(destinationUri: String): List<RssFeedSubscription>
}
