/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssFeedSubscription
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedSubscriptionRepository
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.sampleRss
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.SubscribableChannel

@SpringBootTest
class RssFeedPollingServiceTests {

    @TestConfiguration
    class CapturingEgressConfig {
        @Bean
        fun capturingEgressSubscriber(
            @Qualifier("egressChannel") egressChannel: SubscribableChannel,
        ): CapturingEgressSubscriber {
            val subscriber = CapturingEgressSubscriber()
            egressChannel.subscribe(subscriber)
            return subscriber
        }
    }

    /** Captures all egress messages for assertion */
    class CapturingEgressSubscriber : EgressSubscriber() {
        val captured = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance): Boolean = true

        override fun deliver(result: OperationResult, provenance: Provenance) {
            captured.add(result to provenance)
        }

        fun clear() = captured.clear()
    }

    @Autowired lateinit var pollingService: RssFeedPollingService
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var entryRepository: RssEntryRepository
    @Autowired lateinit var subscriptionRepository: RssFeedSubscriptionRepository
    @Autowired lateinit var capturingEgressSubscriber: CapturingEgressSubscriber

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    @BeforeEach
    fun setUp() {
        capturingEgressSubscriber.clear()
        httpServer = HttpServer.create(InetSocketAddress(0), 10)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        subscriptionRepository.deleteAll()
        entryRepository.deleteAll()
        feedRepository.deleteAll()
    }

    private fun createFeed(title: String, path: String): RssFeed {
        return feedRepository.save(
            RssFeed(
                feedUrl = "$baseUrl$path",
                title = title,
                lastFetchedAt = Instant.now().minusSeconds(3600),
            ),
        )
    }

    private fun seedEntries(feed: RssFeed, guids: List<String>) {
        guids.forEach { guid ->
            entryRepository.save(
                RssEntry(feed = feed, guid = guid, link = guid, title = "Existing: $guid"),
            )
        }
    }

    private fun subscribeFeed(feed: RssFeed, destinationUri: String): RssFeedSubscription {
        return subscriptionRepository.save(
            RssFeedSubscription(feed = feed, destinationUri = destinationUri),
        )
    }

    @Test
    fun `polling a feed with new entries stores them`() {
        val feed = createFeed("New Entries Feed", "/feed.xml")
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("New Entries Feed", 3)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollFeed(feed)

        val entries =
            entryRepository.findByFeedAndGuidIn(feed, (1..3).map { "http://example.com/entry/$it" })
        assertEquals(3, entries.size)
    }

    /** What [action] logs at WARN from the polling service. */
    private fun warnings(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(RssFeedPollingService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `a feed published with localhost links is warned about on each poll, and still stored`() {
        val feed = createFeed("Blog posts | RSS Feed", "/localhost.xml")
        httpServer.createContext("/localhost.xml") { exchange ->
            val rss =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                    <channel>
                        <title>Blog posts | RSS Feed</title>
                        <link>http://localhost:3000</link>
                        <item><title>AI</title><link>http://localhost:3000/blog/ai</link></item>
                        <item><title>Two</title><link>http://localhost:3000/blog/two</link></item>
                    </channel>
                </rss>
                """
                    .trimIndent()
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val first = warnings { pollingService.pollFeed(feed) }
        val second = warnings { pollingService.pollFeed(feed) }

        val expected =
            "Feed \"Blog posts | RSS Feed\" ($baseUrl/localhost.xml): " +
                "2 of 2 item links look wrong, e.g. http://localhost:3000/blog/ai (a local host); " +
                "its site link looks wrong: http://localhost:3000 (a local host)"
        assertEquals(listOf(expected), first)
        assertEquals(listOf(expected), second)
        // Only said: the items are kept as the feed gave them
        assertEquals(
            2,
            entryRepository
                .findByFeedAndGuidIn(
                    feed,
                    listOf("http://localhost:3000/blog/ai", "http://localhost:3000/blog/two"),
                )
                .size,
        )
    }

    @Test
    fun `a feed with public links is polled without a warning`() {
        val feed = createFeed("Fine Feed", "/fine.xml")
        httpServer.createContext("/fine.xml") { exchange ->
            val rss = sampleRss("Fine Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        assertEquals(listOf<String>(), warnings { pollingService.pollFeed(feed) })
    }

    @Test
    fun `polling ignores duplicate guid entries in one fetch`() {
        val feed = createFeed("Typotheque", "/duplicate.xml")
        val destination =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        subscribeFeed(feed, destination.encode())

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

        httpServer.createContext("/duplicate.xml") { exchange ->
            exchange.sendResponseHeaders(200, duplicateGuidRss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(duplicateGuidRss.toByteArray()) }
        }

        pollingService.pollFeed(feed)
        entryRepository.flush()

        val entries =
            entryRepository.findByFeedAndGuidIn(
                feed,
                listOf(
                    "https://www.typotheque.com/articles/example",
                    "https://www.typotheque.com/articles/another",
                ),
            )
        assertEquals(2, entries.size)
        assertEquals(2, capturingEgressSubscriber.captured.size)
    }

    @Test
    fun `polling a feed with no new entries only updates lastFetchedAt`() {
        val feed = createFeed("All Seen Feed", "/feed.xml")
        // Seed the same entries that the feed will return
        seedEntries(feed, (1..2).map { "http://example.com/entry/$it" })
        val entryCountBefore = entryRepository.count()

        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("All Seen Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollFeed(feed)

        assertEquals(entryCountBefore, entryRepository.count())
        val updatedFeed = feedRepository.findByFeedUrl(feed.feedUrl)
        assertNotNull(updatedFeed)
        assertTrue(updatedFeed!!.lastFetchedAt!!.isAfter(feed.lastFetchedAt))
    }

    @Test
    fun `new entries for subscribed feeds generate notifications on egress`() {
        val feed = createFeed("Notify Feed", "/notify.xml")
        val destination =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        subscribeFeed(feed, destination.encode())

        httpServer.createContext("/notify.xml") { exchange ->
            val rss = sampleRss("Notify Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollFeed(feed)

        // Should have 2 notifications (one per new entry)
        assertEquals(2, capturingEgressSubscriber.captured.size)
        val (result, provenance) = capturingEgressSubscriber.captured[0]
        assertEquals(Protocol.IRC, provenance.protocol)
        assertEquals("libera", provenance.serviceId)
        assertEquals("#java", provenance.replyTo)
        assertTrue(result is OperationResult.Success)
        assertTrue((result as OperationResult.Success).payload.toString().contains("[Notify Feed]"))
    }

    @Test
    fun `unsubscribed feeds get polled and entries stored but no notifications`() {
        val feed = createFeed("No Notify Feed", "/nonot.xml")
        // No subscription created

        httpServer.createContext("/nonot.xml") { exchange ->
            val rss = sampleRss("No Notify Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollFeed(feed)

        // Entries stored
        val entries =
            entryRepository.findByFeedAndGuidIn(feed, (1..2).map { "http://example.com/entry/$it" })
        assertEquals(2, entries.size)

        // No notifications
        assertEquals(0, capturingEgressSubscriber.captured.size)
    }

    @Test
    fun `inactive feeds are not polled`() {
        val feed =
            feedRepository.save(
                RssFeed(
                    feedUrl = "$baseUrl/inactive.xml",
                    title = "Inactive Feed",
                    active = false,
                    lastFetchedAt = Instant.now().minusSeconds(3600),
                ),
            )

        httpServer.createContext("/inactive.xml") { exchange ->
            val rss = sampleRss("Inactive Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollDue(Instant.now())

        // No entries stored because inactive feed should not be polled
        assertEquals(0, entryRepository.count())
    }

    @Test
    fun `a polled entry keeps its summary, as plain text`() {
        val feed = createFeed("Summaries", "/summaries.xml")
        httpServer.createContext("/summaries.xml") { exchange ->
            val rss =
                """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>
                <title>Summaries</title><link>http://example.com</link><description>d</description>
                <item><title>With one</title><link>http://example.com/s/1</link><guid>http://example.com/s/1</guid>
                <description>&lt;p&gt;What it &lt;b&gt;says&lt;/b&gt;.&lt;/p&gt;</description></item>
                <item><title>Without</title><link>http://example.com/s/2</link><guid>http://example.com/s/2</guid></item>
                </channel></rss>"""
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollFeed(feed)

        val entries =
            entryRepository
                .findByFeedAndGuidIn(
                    feed,
                    listOf("http://example.com/s/1", "http://example.com/s/2"),
                )
                .associateBy { it.guid }
        assertEquals("What it says.", entries["http://example.com/s/1"]?.summary)
        assertEquals(null, entries["http://example.com/s/2"]?.summary)
    }

    private fun serve(path: String, body: String) {
        httpServer.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    private fun atom(vararg entries: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"><title>Dates</title><id>urn:dates</id>
        <updated>2026-10-01T00:00:00Z</updated>${entries.joinToString("")}</feed>"""

    private fun atomEntry(id: String, updated: String, published: String? = null) =
        """<entry><title>$id</title><id>http://example.com/a/$id</id>
        <link href="http://example.com/a/$id"/><updated>$updated</updated>""" +
            (published?.let { "<published>$it</published>" } ?: "") +
            "</entry>"

    private fun stored(feed: RssFeed, id: String): RssEntry =
        entryRepository.findByFeedAndGuidIn(feed, listOf("http://example.com/a/$id")).single()

    @Test
    fun `an entry's relative link is stored, logged and announced against the feed's own address`() {
        val feed = createFeed("Relative", "/~yosh/blog/feed.atom")
        subscribeFeed(
            feed,
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java").encode(),
        )
        serve(
            "/~yosh/blog/feed.atom",
            atom(
                """<entry><title>rooted</title><id>urn:uuid:rooted</id>
                <link rel="alternate" href="/~yosh/blog/pratt.html"/>
                <updated>2026-10-25T21:50:00Z</updated></entry>""",
                """<entry><title>beside</title><id>urn:uuid:beside</id>
                <link rel="alternate" href="getopt.html"/>
                <updated>2026-10-20T21:50:00Z</updated></entry>""",
            ),
        )

        val logged = warnings { pollingService.pollFeed(feed) }

        val links =
            entryRepository
                .findByFeedAndGuidIn(feed, listOf("urn:uuid:rooted", "urn:uuid:beside"))
                .associate { it.guid to it.link }
        assertEquals("$baseUrl/~yosh/blog/pratt.html", links["urn:uuid:rooted"])
        assertEquals("$baseUrl/~yosh/blog/getopt.html", links["urn:uuid:beside"])
        assertTrue(logged.none { "not http or https" in it }, logged.toString())
        val said =
            capturingEgressSubscriber.captured.map { (r, _) ->
                (r as OperationResult.Success).payload.toString()
            }
        assertTrue(said.any { it.endsWith("- $baseUrl/~yosh/blog/pratt.html") }, said.toString())
    }

    @Test
    fun `an Atom entry with only updated takes its updated time`() {
        val feed = createFeed("Atom updated", "/updated.xml")
        serve("/updated.xml", atom(atomEntry("only-updated", "2020-03-04T05:06:07Z")))

        pollingService.pollFeed(feed)

        assertEquals(
            Instant.parse("2020-03-04T05:06:07Z"),
            stored(feed, "only-updated").publishedAt,
        )
    }

    @Test
    fun `an Atom entry with published and updated takes its published time`() {
        val feed = createFeed("Atom both", "/both.xml")
        serve(
            "/both.xml",
            atom(atomEntry("both", "2024-02-02T00:00:00Z", published = "2024-01-01T00:00:00Z")),
        )

        pollingService.pollFeed(feed)

        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), stored(feed, "both").publishedAt)
    }

    @Test
    fun `an RSS item takes its pubDate`() {
        val feed = createFeed("RSS dated", "/pubdate.xml")
        serve(
            "/pubdate.xml",
            """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>
            <title>RSS dated</title><link>http://example.com</link><description>d</description>
            <item><title>Dated</title><link>http://example.com/a/rss</link><guid>http://example.com/a/rss</guid>
            <pubDate>Tue, 03 Jun 2025 10:00:00 GMT</pubDate></item></channel></rss>""",
        )

        pollingService.pollFeed(feed)

        assertEquals(Instant.parse("2025-06-03T10:00:00Z"), stored(feed, "rss").publishedAt)
    }

    @Test
    fun `an entry dated in the future sorts as received`() {
        val feed = createFeed("Future", "/future.xml")
        serve(
            "/future.xml",
            atom(
                atomEntry("future", "2999-01-02T00:00:00Z", published = "2999-01-01T00:00:00Z"),
                atomEntry("future-updated", "2999-01-01T00:00:00Z"),
            ),
        )

        pollingService.pollFeed(feed)

        assertNull(stored(feed, "future").publishedAt)
        assertNull(stored(feed, "future-updated").publishedAt)
    }

    @Test
    fun `a stored entry without a time, or with a future one, takes the time on the next poll`() {
        val feed = createFeed("Backfill", "/backfill.xml")
        val received = Instant.parse("2026-10-19T11:02:00Z")
        listOf("undated" to null, "future" to Instant.parse("2026-11-20T00:00:00Z")).forEach {
            (id, at) ->
            entryRepository.save(
                RssEntry(
                    feed = feed,
                    guid = "http://example.com/a/$id",
                    link = "http://example.com/a/$id",
                    title = id,
                    publishedAt = at,
                    createdAt = received,
                ),
            )
        }
        serve(
            "/backfill.xml",
            atom(
                atomEntry("undated", "2020-01-01T00:00:00Z"),
                atomEntry("future", "2021-01-01T00:00:00Z"),
            ),
        )

        pollingService.pollFeed(feed)

        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), stored(feed, "undated").publishedAt)
        assertEquals(Instant.parse("2021-01-01T00:00:00Z"), stored(feed, "future").publishedAt)
    }

    @Test
    fun `a stored entry keeps a time it already has`() {
        val feed = createFeed("Keep", "/keep.xml")
        entryRepository.save(
            RssEntry(
                feed = feed,
                guid = "http://example.com/a/keep",
                link = "http://example.com/a/keep",
                title = "keep",
                publishedAt = Instant.parse("2019-01-01T00:00:00Z"),
                createdAt = Instant.parse("2026-10-19T11:02:00Z"),
            ),
        )
        serve("/keep.xml", atom(atomEntry("keep", "2023-05-05T00:00:00Z")))

        pollingService.pollFeed(feed)

        assertEquals(Instant.parse("2019-01-01T00:00:00Z"), stored(feed, "keep").publishedAt)
    }

    @Test
    fun `feed fetch failure is handled gracefully`() {
        val feed1 = createFeed("Failing Feed", "/fail.xml")
        val feed2 = createFeed("Working Feed", "/work.xml")
        subscribeFeed(feed2, Provenance(protocol = Protocol.CONSOLE, replyTo = "local").encode())

        // First feed returns 500
        httpServer.createContext("/fail.xml") { exchange -> exchange.sendResponseHeaders(500, -1) }
        // Second feed works fine
        httpServer.createContext("/work.xml") { exchange ->
            val rss = sampleRss("Working Feed", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollDue(Instant.now())

        // Working feed's entries should still be stored despite the first feed failing
        val entries =
            entryRepository.findByFeedAndGuidIn(feed2, listOf("http://example.com/entry/1"))
        assertEquals(1, entries.size)
    }

    @Test
    fun `notification carries correct provenance for each subscription target`() {
        val feed = createFeed("Multi Sub Feed", "/multi.xml")
        val irc = Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val console = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        subscribeFeed(feed, irc.encode())
        subscribeFeed(feed, console.encode())

        httpServer.createContext("/multi.xml") { exchange ->
            val rss = sampleRss("Multi Sub Feed", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollFeed(feed)

        // Should have 2 notifications (one per subscriber)
        assertEquals(2, capturingEgressSubscriber.captured.size)
        val protocols = capturingEgressSubscriber.captured.map { it.second.protocol }.toSet()
        assertTrue(protocols.contains(Protocol.IRC))
        assertTrue(protocols.contains(Protocol.CONSOLE))
    }
}
