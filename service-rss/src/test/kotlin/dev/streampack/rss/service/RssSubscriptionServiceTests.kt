/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.rss.model.AddFeedOutcome
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.htmlWithFeedLink
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.sampleRss
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RssSubscriptionServiceTests {

    @Autowired lateinit var feedService: RssSubscriptionService

    @Autowired lateinit var feedRepository: RssFeedRepository

    @Autowired lateinit var entryRepository: RssEntryRepository

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 10)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `adding a valid feed stores the feed and seeds entries`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Tech News", 3)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val outcome = feedService.addFeed("$baseUrl/feed.xml")
        assertInstanceOf(AddFeedOutcome.Added::class.java, outcome)
        val added = outcome as AddFeedOutcome.Added
        assertEquals("Tech News", added.feed.title)
        assertEquals(3, added.entryCount)

        assertEquals(1, feedRepository.count())
        assertEquals(3, entryRepository.count())
    }

    @Test
    fun `adding a website URL discovers and adds the feed`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/rss")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/rss") { exchange ->
            val rss = sampleRss("Discovered Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val outcome = feedService.addFeed(baseUrl)
        assertInstanceOf(AddFeedOutcome.Added::class.java, outcome)
        val added = outcome as AddFeedOutcome.Added
        assertEquals("Discovered Feed", added.feed.title)
        assertEquals("$baseUrl/rss", added.feed.feedUrl)
        assertEquals(2, added.entryCount)
    }

    @Test
    fun `adding an unreachable URL returns DiscoveryFailed`() {
        val outcome = feedService.addFeed("http://localhost:1/nonexistent")
        assertInstanceOf(AddFeedOutcome.DiscoveryFailed::class.java, outcome)
    }

    @Test
    fun `entry guids are derived from uri, falling back to link`() {
        val rssWithMixedGuids =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Mixed Guids</title>
                    <link>http://example.com</link>
                    <item>
                        <title>Has GUID</title>
                        <link>http://example.com/1</link>
                        <guid>custom-guid-1</guid>
                    </item>
                    <item>
                        <title>No GUID</title>
                        <link>http://example.com/2</link>
                    </item>
                </channel>
            </rss>
            """
                .trimIndent()

        httpServer.createContext("/mixed.xml") { exchange ->
            exchange.sendResponseHeaders(200, rssWithMixedGuids.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rssWithMixedGuids.toByteArray()) }
        }

        val outcome = feedService.addFeed("$baseUrl/mixed.xml")
        assertInstanceOf(AddFeedOutcome.Added::class.java, outcome)
        val added = outcome as AddFeedOutcome.Added
        assertEquals(2, added.entryCount)

        val entries = entryRepository.findAll()
        val guids = entries.map { it.guid }.toSet()
        assertEquals(setOf("custom-guid-1", "http://example.com/2"), guids)
    }

    @Test
    fun `adding a feed tolerates duplicate guid entries in the source feed`() {
        val duplicateGuidRss =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Typotheque</title>
                    <link>https://www.typotheque.com/</link>
                    <item>
                        <title>Duplicate Entry</title>
                        <link>https://www.typotheque.com/articles/example</link>
                        <guid>https://www.typotheque.com/articles/example</guid>
                    </item>
                    <item>
                        <title>Duplicate Entry</title>
                        <link>https://www.typotheque.com/articles/example</link>
                        <guid>https://www.typotheque.com/articles/example</guid>
                    </item>
                    <item>
                        <title>Distinct Entry</title>
                        <link>https://www.typotheque.com/articles/another</link>
                        <guid>https://www.typotheque.com/articles/another</guid>
                    </item>
                </channel>
            </rss>
            """
                .trimIndent()

        httpServer.createContext("/duplicate-guid.xml") { exchange ->
            exchange.sendResponseHeaders(200, duplicateGuidRss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(duplicateGuidRss.toByteArray()) }
        }

        val outcome = feedService.addFeed("$baseUrl/duplicate-guid.xml")
        assertInstanceOf(AddFeedOutcome.Added::class.java, outcome)
        val added = outcome as AddFeedOutcome.Added
        assertEquals("Typotheque", added.feed.title)
        assertEquals(2, added.entryCount)
        entryRepository.flush()
        assertEquals(2, entryRepository.count())
    }

    @Test
    fun `adding the same feed URL twice returns AlreadyExists`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Dupe Test", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val first = feedService.addFeed("$baseUrl/feed.xml")
        assertInstanceOf(AddFeedOutcome.Added::class.java, first)

        val second = feedService.addFeed("$baseUrl/feed.xml")
        assertInstanceOf(AddFeedOutcome.AlreadyExists::class.java, second)

        assertEquals(1, feedRepository.count())
    }

    @Test
    fun `second add of known feed URL returns AlreadyExists even if discovery is down`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Dupe Offline", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val first = feedService.addFeed("$baseUrl/feed.xml")
        assertInstanceOf(AddFeedOutcome.Added::class.java, first)

        // Simulate transient outage for discovery/fetch on second add.
        httpServer.stop(0)

        val second = feedService.addFeed("$baseUrl/feed.xml")
        assertInstanceOf(AddFeedOutcome.AlreadyExists::class.java, second)
        assertEquals(1, feedRepository.count())
    }

    @Test
    fun `adding the same site URL twice returns AlreadyExists`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/rss")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/rss") { exchange ->
            val rss = sampleRss("Site Feed", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val first = feedService.addFeed(baseUrl)
        assertInstanceOf(AddFeedOutcome.Added::class.java, first)

        val second = feedService.addFeed(baseUrl)
        assertInstanceOf(AddFeedOutcome.AlreadyExists::class.java, second)

        assertEquals(1, feedRepository.count())
    }

    @Test
    fun `adding site URL then its feed URL directly returns AlreadyExists`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/rss")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/rss") { exchange ->
            val rss = sampleRss("Cross Dedup", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val first = feedService.addFeed(baseUrl)
        assertInstanceOf(AddFeedOutcome.Added::class.java, first)

        // Now add the feed URL directly - should find the same feed
        val second = feedService.addFeed("$baseUrl/rss")
        assertInstanceOf(AddFeedOutcome.AlreadyExists::class.java, second)

        assertEquals(1, feedRepository.count())
    }

    @Test
    fun `adding feed URL directly then site URL returns AlreadyExists`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/rss")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/rss") { exchange ->
            val rss = sampleRss("Reverse Dedup", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val first = feedService.addFeed("$baseUrl/rss")
        assertInstanceOf(AddFeedOutcome.Added::class.java, first)

        // Now add via the site URL - discovery resolves to same feed URL
        val second = feedService.addFeed(baseUrl)
        assertInstanceOf(AddFeedOutcome.AlreadyExists::class.java, second)

        assertEquals(1, feedRepository.count())
    }
}
