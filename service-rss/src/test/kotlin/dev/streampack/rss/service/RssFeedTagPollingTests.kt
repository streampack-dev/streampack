/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndFeed
import dev.streampack.core.fetch.GuardedFetcher
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.model.FeedTagStatus
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedTagRepository
import dev.streampack.rss.service.FeedTagSamples.atom
import dev.streampack.rss.service.FeedTagSamples.parse
import dev.streampack.rss.service.FeedTagSamples.rss
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional

/**
 * A poll keeps feed entries' own tags (#139), from RSS and Atom alike, for new entries and for
 * entries already stored that are still in the feed. The feeds are served from memory, not over
 * HTTP, so nothing here waits on a port or a clock.
 */
@SpringBootTest
@Transactional
class RssFeedTagPollingTests {
    /** Feeds by address, parsed in memory, in place of fetching them. */
    class InMemoryFeeds(fetcher: GuardedFetcher) : FeedDiscoveryService(fetcher) {
        val feeds = ConcurrentHashMap<String, String>()

        override fun fetchFeed(feedUrl: String): SyndFeed? = feeds[feedUrl]?.let(::parse)
    }

    @TestConfiguration
    class InMemoryFeedsConfig {
        @Bean @Primary fun inMemoryFeeds(fetcher: GuardedFetcher) = InMemoryFeeds(fetcher)
    }

    @Autowired lateinit var polling: RssFeedPollingService
    @Autowired lateinit var inMemory: InMemoryFeeds
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var feedTags: FeedTagService
    @Autowired lateinit var waiting: RssFeedTagRepository

    @BeforeEach fun setUp() = inMemory.feeds.clear()

    private fun feed(url: String, xml: String): RssFeed {
        inMemory.feeds[url] = xml
        return feeds.save(RssFeed(feedUrl = url, title = url))
    }

    private fun tagsOf(feed: RssFeed, guid: String): EntryTags {
        val entry = entries.findByFeedAndGuidIn(feed, listOf(guid)).single()
        return feedTags.tagsFor(listOf(entry.id)).getValue(entry.id)
    }

    @Test
    fun `a poll keeps RSS categories and Atom terms`() {
        val a =
            feed("mem:a", rss("https://a.example", mapOf("1" to listOf("Spring-Boot", "Quarkus"))))
        val b = feed("mem:b", atom("https://b.example", mapOf("1" to listOf("Quarkus"))))
        polling.pollFeed(a)
        polling.pollFeed(b)
        assertEquals(listOf("Quarkus", "Spring-Boot"), tagsOf(a, "https://a.example/1").categories)
        assertEquals(listOf("Quarkus"), tagsOf(b, "https://b.example/1").categories)
        val quarkus = waiting.findById("quarkus").orElseThrow()
        assertEquals(FeedTagStatus.WAITING, quarkus.status)
        assertEquals(2, quarkus.entries)
        assertEquals(2, quarkus.feeds)
        assertEquals(1, waiting.findById("spring boot").orElseThrow().entries)
    }

    @Test
    fun `an entry stored before tags were kept gains them on the next poll`() {
        val a = feed("mem:a", rss("https://a.example", mapOf("1" to listOf())))
        polling.pollFeed(a)
        assertEquals(emptyList<String>(), tagsOf(a, "https://a.example/1").categories)
        inMemory.feeds["mem:a"] = rss("https://a.example", mapOf("1" to listOf("Quarkus")))
        polling.pollFeed(a)
        assertEquals(listOf("Quarkus"), tagsOf(a, "https://a.example/1").categories)
        assertEquals(1, entries.countByFeed(a))
    }
}
