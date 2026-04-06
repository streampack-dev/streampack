/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import dev.streampack.core.integration.TickListener
import dev.streampack.polling.service.EgressNotifier
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedSubscriptionRepository
import jakarta.annotation.PostConstruct
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Polls all active feeds on a tick-driven interval, stores new entries, and notifies subscribers
 */
@Service
class RssFeedPollingService(
    private val feedRepository: RssFeedRepository,
    private val entryRepository: RssEntryRepository,
    private val subscriptionRepository: RssFeedSubscriptionRepository,
    private val discoveryService: FeedDiscoveryService,
    private val egressNotifier: EgressNotifier,
    private val rssProperties: RssProperties,
) : TickListener {
    private val logger = LoggerFactory.getLogger(RssFeedPollingService::class.java)
    private lateinit var lastPollTime: Instant

    /** Delay first poll by 30 seconds so protocol adapters can finish connecting */
    @PostConstruct
    fun initLastPollTime() {
        lastPollTime = Instant.now().minus(rssProperties.pollInterval).plusSeconds(30)
    }

    override fun onTick(now: Instant) {
        if (Duration.between(lastPollTime, now) >= rssProperties.pollInterval) {
            lastPollTime = now
            pollAllFeeds()
        }
    }

    fun pollAllFeeds() {
        val feeds = feedRepository.findAllByActiveTrue()
        logger.debug("Polling {} active feeds", feeds.size)
        for (feed in feeds) {
            try {
                pollFeed(feed)
            } catch (e: Exception) {
                logger.warn("Failed to poll feed \"{}\": {}", feed.title, e.message)
            }
        }
    }

    @Transactional
    fun pollFeed(feed: RssFeed) {
        val syndFeed = discoveryService.fetchFeed(feed.feedUrl)
        if (syndFeed == null) {
            logger.debug("Could not fetch feed \"{}\" at {}", feed.title, feed.feedUrl)
            return
        }

        val fetchedEntries = syndFeed.entries
        if (fetchedEntries.isEmpty()) {
            feedRepository.save(feed.copy(lastFetchedAt = Instant.now()))
            return
        }

        val guids = fetchedEntries.mapNotNull { it.uri ?: it.link }
        val existingGuids = entryRepository.findByFeedAndGuidIn(feed, guids).map { it.guid }.toSet()

        val newSyndEntries =
            fetchedEntries.filter { entry ->
                val guid = entry.uri ?: entry.link
                guid != null && guid !in existingGuids
            }

        val newEntries = newSyndEntries.mapNotNull { entry -> toRssEntry(entry, feed) }
        if (newEntries.isNotEmpty()) {
            entryRepository.saveAll(newEntries)
            logger.info("Stored {} new entries for feed \"{}\"", newEntries.size, feed.title)
        }

        feedRepository.save(feed.copy(lastFetchedAt = Instant.now()))

        // Notify subscribers about new entries
        val subscriptions = subscriptionRepository.findByFeedAndActiveTrue(feed)
        if (subscriptions.isNotEmpty() && newSyndEntries.isNotEmpty()) {
            for (entry in newSyndEntries) {
                val title = entry.title ?: ""
                val link = entry.link ?: entry.uri ?: ""
                val message = formatNotification(feed.title, title, link)
                for (subscription in subscriptions) {
                    egressNotifier.send(message, subscription.destinationUri)
                }
            }
        }
    }

    /** Format a new-entry notification for delivery */
    private fun formatNotification(feedTitle: String, entryTitle: String, link: String): String =
        "[$feedTitle] $entryTitle - $link"

    private fun toRssEntry(syndEntry: SyndEntry, feed: RssFeed): RssEntry? {
        val guid = syndEntry.uri ?: syndEntry.link ?: return null
        val link = syndEntry.link ?: guid
        val title = syndEntry.title ?: ""
        val publishedAt = syndEntry.publishedDate?.toInstant()
        return RssEntry(
            feed = feed,
            guid = guid,
            link = link,
            title = title.take(500),
            publishedAt = publishedAt,
        )
    }
}
