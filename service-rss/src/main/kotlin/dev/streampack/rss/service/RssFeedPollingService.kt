/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import dev.streampack.polling.schedule.DueBatchPollingService
import dev.streampack.polling.schedule.PollResult
import dev.streampack.polling.schedule.PollSchedule
import dev.streampack.polling.service.EgressNotifier
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedSubscriptionRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Polls due feeds in bounded batches (issue #40), stores new entries, and notifies subscribers.
 * Each feed carries its own next poll time, so reads spread over time instead of one sweep.
 */
@Service
class RssFeedPollingService(
    private val feedRepository: RssFeedRepository,
    private val entryRepository: RssEntryRepository,
    private val subscriptionRepository: RssFeedSubscriptionRepository,
    private val discoveryService: FeedDiscoveryService,
    private val egressNotifier: EgressNotifier,
    private val rssProperties: RssProperties,
) : DueBatchPollingService<RssFeed>(rssProperties.schedulerInterval, rssProperties.batchSize) {
    private val logger = LoggerFactory.getLogger(RssFeedPollingService::class.java)

    override fun findDue(now: Instant, limit: Int): List<RssFeed> =
        feedRepository.findByActiveTrueAndNextPollAtLessThanEqualOrderByNextPollAtAsc(
            now,
            PageRequest.of(0, limit),
        )

    override fun poll(source: RssFeed): PollResult<RssFeed> = pollFeed(source)

    override fun scheduleAfterSuccess(source: RssFeed, now: Instant) {
        feedRepository.save(
            source.copy(
                nextPollAt = PollSchedule.afterSuccess(now, rssProperties.pollInterval),
                pollFailures = 0,
            )
        )
    }

    override fun scheduleAfterFailure(source: RssFeed, now: Instant, reason: String?) {
        val failures = source.pollFailures + 1
        feedRepository.save(
            source.copy(
                nextPollAt =
                    PollSchedule.afterFailure(
                        now,
                        rssProperties.pollInterval,
                        failures,
                        rssProperties.maxBackoff,
                    ),
                pollFailures = failures,
            )
        )
    }

    override fun describe(source: RssFeed): String = "feed \"${source.title}\" (${source.feedUrl})"

    /**
     * Fetch one feed, store its new entries, and notify subscribers. Returns the feed with its
     * fetch time recorded, or a failure when the feed could not be fetched or parsed.
     */
    @Transactional
    fun pollFeed(feed: RssFeed): PollResult<RssFeed> {
        val syndFeed =
            discoveryService.fetchFeed(feed.feedUrl)
                ?: return PollResult.Failure("could not fetch or parse ${feed.feedUrl}")

        val fetchedEntries = deduplicateEntries(syndFeed.entries)
        if (fetchedEntries.isEmpty()) {
            return PollResult.Success(feedRepository.save(feed.copy(lastFetchedAt = Instant.now())))
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

        val fetched = feedRepository.save(feed.copy(lastFetchedAt = Instant.now()))

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
        return PollResult.Success(fetched)
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
            logger.info("Ignored {} duplicate RSS entries while polling feed updates", duplicates)
        }
        return deduplicated
    }
}
