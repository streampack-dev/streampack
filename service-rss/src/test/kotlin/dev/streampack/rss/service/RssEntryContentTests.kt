/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndFeed
import dev.streampack.core.fetch.GuardedFetcher
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssTextSource
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssEntryTextRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.service.FeedTagSamples.parse
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional

/**
 * A poll keeps a new entry's full content (#187), RSS `content:encoded` and Atom `<content>` alike,
 * as plain text, for the rating guess. Feeds are served from memory.
 */
@SpringBootTest
@Transactional
class RssEntryContentTests {
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
    @Autowired lateinit var texts: RssEntryTextRepository

    @BeforeEach fun setUp() = inMemory.feeds.clear()

    private val rss =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
            <channel>
                <title>R</title><link>https://r.example</link><description>R</description>
                <item>
                    <title>With content</title>
                    <link>https://r.example/1</link><guid>https://r.example/1</guid>
                    <description>A short summary.</description>
                    <content:encoded><![CDATA[<p>The <b>whole</b> story,</p>
                        <p>in   two paragraphs.</p><script>alert(1)</script>]]></content:encoded>
                </item>
                <item>
                    <title>Without</title>
                    <link>https://r.example/2</link><guid>https://r.example/2</guid>
                    <description>Only a summary.</description>
                </item>
            </channel>
        </rss>
        """
            .trimIndent()

    private val atom =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
            <title>Atom</title><link href="https://b.example"/><id>https://b.example</id>
            <updated>2026-01-01T00:00:00Z</updated>
            <entry>
                <title>Atom entry</title><link href="https://b.example/1"/>
                <id>https://b.example/1</id><updated>2026-01-01T00:00:00Z</updated>
                <summary>Short.</summary>
                <content type="html">&lt;p&gt;An Atom body, &lt;em&gt;in full&lt;/em&gt;.&lt;/p&gt;</content>
            </entry>
        </feed>
        """
            .trimIndent()

    private fun textOf(feed: RssFeed, guid: String) =
        texts.findById(entries.findByFeedAndGuidIn(feed, listOf(guid)).single().id).orElse(null)

    @Test
    fun `a poll keeps RSS content and Atom content as plain text`() {
        inMemory.feeds["mem:r"] = rss
        inMemory.feeds["mem:b"] = atom
        val r = feeds.save(RssFeed(feedUrl = "mem:r", title = "R"))
        val b = feeds.save(RssFeed(feedUrl = "mem:b", title = "B"))
        polling.pollFeed(r)
        polling.pollFeed(b)

        val rText = textOf(r, "https://r.example/1")
        assertEquals(RssTextSource.CONTENT, rText.source)
        assertEquals("The whole story, in two paragraphs.", rText.content)
        assertNull(textOf(r, "https://r.example/2"))
        assertEquals("An Atom body, in full.", textOf(b, "https://b.example/1").content)
    }

    @Test
    fun `content is cut at a word to 20,000 characters`() {
        val long = (1..5000).joinToString(" ") { "word$it" }
        val cut = EntryContent.cut(long, EntryContent.MAX_LENGTH)
        assertTrue(cut.length <= EntryContent.MAX_LENGTH)
        assertTrue(cut.endsWith("..."))
        assertFalse(cut.removeSuffix("...").endsWith("wor"))
        assertTrue(Regex("word\\d+\\.\\.\\.$").containsMatchIn(cut))
        assertEquals("short", EntryContent.cut("short", 10))
    }
}
