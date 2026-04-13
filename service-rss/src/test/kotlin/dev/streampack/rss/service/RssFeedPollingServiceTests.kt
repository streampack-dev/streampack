/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
            @Qualifier("egressChannel") egressChannel: SubscribableChannel
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
            )
        )
    }

    private fun seedEntries(feed: RssFeed, guids: List<String>) {
        guids.forEach { guid ->
            entryRepository.save(
                RssEntry(feed = feed, guid = guid, link = guid, title = "Existing: $guid")
            )
        }
    }

    private fun subscribeFeed(feed: RssFeed, destinationUri: String): RssFeedSubscription {
        return subscriptionRepository.save(
            RssFeedSubscription(feed = feed, destinationUri = destinationUri)
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
                )
            )

        httpServer.createContext("/inactive.xml") { exchange ->
            val rss = sampleRss("Inactive Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        pollingService.pollAllFeeds()

        // No entries stored because inactive feed should not be polled
        assertEquals(0, entryRepository.count())
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

        pollingService.pollAllFeeds()

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
