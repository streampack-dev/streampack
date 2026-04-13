/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssFeedSubscription
import dev.streampack.rss.model.AddFeedOutcome
import dev.streampack.rss.model.RemoveFeedOutcome
import dev.streampack.rss.model.SubscriptionOutcome
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedSubscriptionRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Orchestrates feed registration: discovery, dedup, storage, and entry seeding */
@Service
class RssSubscriptionService(
    private val discoveryService: FeedDiscoveryService,
    private val feedRepository: RssFeedRepository,
    private val entryRepository: RssEntryRepository,
    private val subscriptionRepository: RssFeedSubscriptionRepository,
) {

    private val logger = LoggerFactory.getLogger(RssSubscriptionService::class.java)

    /**
     * Register a feed from a URL. Discovers the feed, stores metadata, and seeds current entries.
     */
    @Transactional
    fun addFeed(url: String): AddFeedOutcome {
        val normalized = normalizeUrl(url)

        // Fast-path dedup: avoid network dependency when this URL is already known.
        findExistingByInputUrl(url, normalized)?.let { existing ->
            logger.debug("Feed already exists: {}", existing.feedUrl)
            return AddFeedOutcome.AlreadyExists(existing)
        }

        // Try discovery with normalized URL first, then original if different
        val result =
            discoveryService.discover(normalized)
                ?: if (normalized != url) discoveryService.discover(url) else null

        if (result == null) {
            logger.info("No feed discovered at {}", url)
            return AddFeedOutcome.DiscoveryFailed(url)
        }

        // Check for existing feed by the resolved feed URL
        val existing = feedRepository.findByFeedUrl(result.feedUrl)
        if (existing != null) {
            logger.debug("Feed already exists: {}", result.feedUrl)
            return AddFeedOutcome.AlreadyExists(existing)
        }

        val syndFeed = result.feed
        val feed =
            feedRepository.save(
                RssFeed(
                    feedUrl = result.feedUrl,
                    siteUrl = syndFeed.link,
                    title = syndFeed.title ?: result.feedUrl,
                    description = syndFeed.description?.take(2000),
                    lastFetchedAt = Instant.now(),
                )
            )

        // Seed all current entries to establish the baseline
        val entries =
            deduplicateEntries(syndFeed.entries).mapNotNull { entry ->
                val guid = entry.uri ?: entry.link ?: return@mapNotNull null
                val link = entry.link ?: guid
                val title = entry.title ?: ""
                val publishedAt = entry.publishedDate?.toInstant()

                RssEntry(
                    feed = feed,
                    guid = guid,
                    link = link,
                    title = title.take(500),
                    publishedAt = publishedAt,
                )
            }

        entryRepository.saveAll(entries)
        logger.info("Added feed \"{}\" with {} entries", feed.title, entries.size)
        return AddFeedOutcome.Added(feed, entries.size)
    }

    private fun deduplicateEntries(entries: List<SyndEntry>): List<SyndEntry> {
        val seenGuids = LinkedHashSet<String>()
        var duplicates = 0
        val deduplicated =
            entries.filter { entry ->
                val guid = entry.uri ?: entry.link ?: return@filter false
                val added = seenGuids.add(guid)
                if (!added) duplicates++
                added
            }
        if (duplicates > 0) {
            logger.info("Ignored {} duplicate RSS entries during feed registration", duplicates)
        }
        return deduplicated
    }

    private fun findExistingByInputUrl(url: String, normalized: String): RssFeed? {
        feedRepository.findByFeedUrl(normalized)?.let {
            return it
        }
        feedRepository.findBySiteUrl(normalized)?.let {
            return it
        }
        if (normalized != url) {
            feedRepository.findByFeedUrl(url)?.let {
                return it
            }
            feedRepository.findBySiteUrl(url)?.let {
                return it
            }
        }
        return null
    }

    /** Subscribe a destination to a feed, resolving the feed URL if needed */
    @Transactional
    fun subscribe(feedUrl: String, destinationUri: String): SubscriptionOutcome {
        val feed = resolveFeed(feedUrl) ?: return SubscriptionOutcome.FeedNotFound(feedUrl)
        val existing = subscriptionRepository.findByFeedAndDestinationUri(feed, destinationUri)
        if (existing != null && existing.active) {
            return SubscriptionOutcome.AlreadySubscribed(feed)
        }
        if (existing != null) {
            // Reactivate an inactive subscription
            subscriptionRepository.save(existing.copy(active = true))
        } else {
            subscriptionRepository.save(
                RssFeedSubscription(feed = feed, destinationUri = destinationUri)
            )
        }
        logger.info("Subscribed {} to feed \"{}\"", destinationUri, feed.title)
        return SubscriptionOutcome.Subscribed(feed)
    }

    /** Unsubscribe a destination from a feed */
    @Transactional
    fun unsubscribe(feedUrl: String, destinationUri: String): SubscriptionOutcome {
        val feed = resolveFeed(feedUrl) ?: return SubscriptionOutcome.FeedNotFound(feedUrl)
        val existing = subscriptionRepository.findByFeedAndDestinationUri(feed, destinationUri)
        if (existing == null || !existing.active) {
            return SubscriptionOutcome.NotSubscribed(feed)
        }
        subscriptionRepository.save(existing.copy(active = false))
        logger.info("Unsubscribed {} from feed \"{}\"", destinationUri, feed.title)
        return SubscriptionOutcome.Unsubscribed(feed)
    }

    /** Deactivate a feed and all its subscriptions */
    @Transactional
    fun removeFeed(feedUrl: String): RemoveFeedOutcome {
        val feed = resolveFeed(feedUrl) ?: return RemoveFeedOutcome.FeedNotFound(feedUrl)
        if (!feed.active) {
            return RemoveFeedOutcome.AlreadyInactive(feed)
        }
        val activeSubscriptions = subscriptionRepository.findByFeedAndActiveTrue(feed)
        activeSubscriptions.forEach { subscriptionRepository.save(it.copy(active = false)) }
        feedRepository.save(feed.copy(active = false))
        logger.info(
            "Removed feed \"{}\" and deactivated {} subscriptions",
            feed.title,
            activeSubscriptions.size,
        )
        return RemoveFeedOutcome.Removed(feed, activeSubscriptions.size)
    }

    /** List all registered feeds */
    fun listFeeds(): List<RssFeed> = feedRepository.findAll()

    /** List active subscriptions for a destination */
    fun listSubscriptions(destinationUri: String): List<RssFeedSubscription> =
        subscriptionRepository.findByDestinationUriAndActiveTrue(destinationUri)

    /** Resolve a URL to an existing feed: exact match first, then discovery fallback */
    fun resolveFeed(url: String): RssFeed? {
        val normalized = normalizeUrl(url)
        // Try exact match on feedUrl
        feedRepository.findByFeedUrl(normalized)?.let {
            return it
        }
        if (normalized != url) {
            feedRepository.findByFeedUrl(url)?.let {
                return it
            }
        }
        // Try discovery to resolve site URLs to feed URLs
        val result = discoveryService.discover(normalized)
        if (result != null) {
            feedRepository.findByFeedUrl(result.feedUrl)?.let {
                return it
            }
        }
        return null
    }

    /** Ensure the URL has a scheme */
    private fun normalizeUrl(url: String): String {
        return if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
    }
}
