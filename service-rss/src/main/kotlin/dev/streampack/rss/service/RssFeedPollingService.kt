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
import java.net.URI
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
    private val feedTags: FeedTagService,
    private val itemTexts: RssItemTextService,
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
            ),
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
            ),
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
        // A feed whose links look wrong is said so on each poll, for someone to tell its publisher
        SuspectLinks.warning(
                feed.title,
                feed.feedUrl,
                syndFeed.link,
                fetchedEntries.mapNotNull { linkOf(it, feed) ?: it.uri },
            )
            ?.let { logger.warn(it) }
        if (fetchedEntries.isEmpty()) {
            return PollResult.Success(feedRepository.save(feed.copy(lastFetchedAt = Instant.now())))
        }

        val guids = fetchedEntries.mapNotNull { it.uri ?: it.link }
        val existing = entryRepository.findByFeedAndGuidIn(feed, guids)
        val existingGuids = existing.map { it.guid }.toSet()

        val newSyndEntries = fetchedEntries.filter { entry ->
            val guid = entry.uri ?: entry.link
            guid != null && guid !in existingGuids
        }

        val newEntries = newSyndEntries.mapNotNull { entry -> toRssEntry(entry, feed) }
        val redated = redate(existing, fetchedEntries)
        val stored =
            if (newEntries.isNotEmpty()) {
                entryRepository.saveAll(newEntries).also {
                    logger.info("Stored {} new entries for feed \"{}\"", it.size, feed.title)
                }
            } else emptyList()

        // Every entry in the window keeps the feed's own tags, old entries included (#139)
        val byGuid = (redated + stored).associateBy { it.guid }
        // A new entry keeps the feed's full content, for the rating guess (#187)
        val newByGuid = newSyndEntries.associateBy { it.uri ?: it.link }
        itemTexts.recordContent(stored.mapNotNull { e -> newByGuid[e.guid]?.let { e to it } })
        feedTags.record(
            fetchedEntries.mapNotNull { entry ->
                byGuid[entry.uri ?: entry.link]?.let { it to entry }
            },
        )

        val fetched = feedRepository.save(feed.copy(lastFetchedAt = Instant.now()))

        // Notify subscribers about new entries
        val subscriptions = subscriptionRepository.findByFeedAndActiveTrue(feed)
        if (subscriptions.isNotEmpty() && newSyndEntries.isNotEmpty()) {
            for (entry in newSyndEntries) {
                val title = entry.title ?: ""
                val link = linkOf(entry, feed) ?: entry.uri ?: ""
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
        val link = linkOf(syndEntry, feed) ?: guid
        val title = syndEntry.title ?: ""
        val receivedAt = Instant.now()
        return RssEntry(
            feed = feed,
            guid = guid,
            link = link,
            title = title.take(500),
            summary = EntrySummary.of(syndEntry),
            publishedAt = entryTime(syndEntry, receivedAt),
            createdAt = receivedAt,
        )
    }

    /**
     * Gives a time to the stored entries in [existing] that have none, or one later than when they
     * were received, from the same entries in [fetched] (#261). An entry with a usable time keeps
     * it. Returns [existing] with the changed entries replaced by their saved copies.
     */
    private fun redate(existing: List<RssEntry>, fetched: List<SyndEntry>): List<RssEntry> {
        val fetchedByGuid = fetched.associateBy { it.uri ?: it.link }
        return existing.map { entry ->
            val stale = entry.publishedAt?.isAfter(entry.createdAt) ?: true
            val syndEntry = fetchedByGuid[entry.guid]
            if (!stale || syndEntry == null) return@map entry
            val time = entryTime(syndEntry, entry.createdAt)
            if (time == entry.publishedAt) entry
            else entryRepository.save(entry.copy(publishedAt = time))
        }
    }

    private fun deduplicateEntries(entries: List<SyndEntry>): List<SyndEntry> {
        val seenGuids = LinkedHashSet<String>()
        var duplicates = 0
        val deduplicated = entries.filter { entry ->
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

    companion object {
        /**
         * [entry]'s link, resolved against [feed]'s own address when it's relative, as Atom allows
         * (#290): `/~yosh/blog/pratt.html` read from `https://www.unix.dog/~yosh/blog/feed.atom` is
         * `https://www.unix.dog/~yosh/blog/pratt.html`. A link that isn't a valid URI is kept as
         * given.
         */
        fun linkOf(entry: SyndEntry, feed: RssFeed): String? =
            entry.link?.let { link ->
                runCatching { URI(feed.feedUrl).resolve(link.trim()).toString() }.getOrDefault(link)
            }

        /**
         * An entry's own time: `<published>` (or RSS `<pubDate>`), else Atom's `<updated>`, which
         * Atom requires when it makes `<published>` optional. Null when the feed gives neither, or
         * gives a time after [receivedAt]: such an entry sorts as received (#261).
         */
        fun entryTime(entry: SyndEntry, receivedAt: Instant): Instant? =
            (entry.publishedDate ?: entry.updatedDate)?.toInstant()?.takeUnless {
                it.isAfter(receivedAt)
            }
    }
}
