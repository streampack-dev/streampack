/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedSubscriptionRepository
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.sampleRss
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** Feeds are polled in bounded batches, oldest due first, with backoff on failure (#40). */
@SpringBootTest(
    properties =
        [
            "streampack.rss.batch-size=2",
            "streampack.rss.poll-interval=PT1H",
            "streampack.rss.max-backoff=PT4H",
        ]
)
class RssDueBatchPollingTests {
    @Autowired lateinit var pollingService: RssFeedPollingService
    @Autowired lateinit var subscriptionService: RssSubscriptionService
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var entryRepository: RssEntryRepository
    @Autowired lateinit var subscriptionRepository: RssFeedSubscriptionRepository

    private lateinit var httpServer: HttpServer
    private val hits = CopyOnWriteArrayList<String>()
    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 10)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
        hits.clear()
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        subscriptionRepository.deleteAll()
        entryRepository.deleteAll()
        feedRepository.deleteAll()
    }

    private fun serve(path: String, title: String, status: Int = 200) {
        httpServer.createContext(path) { exchange ->
            hits += path
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1)
                return@createContext
            }
            val rss = sampleRss(title, 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }
    }

    private fun feed(path: String, dueAt: Instant, failures: Int = 0): RssFeed {
        serve(path, path)
        return feedRepository.save(
            RssFeed(
                feedUrl = "$baseUrl$path",
                title = path,
                nextPollAt = dueAt,
                pollFailures = failures,
            )
        )
    }

    @Test
    fun `a tick polls only the batch size, oldest due first, and advances next poll time`() {
        val now = Instant.now()
        feed("/c.xml", now.minusSeconds(10))
        feed("/a.xml", now.minusSeconds(3000))
        feed("/b.xml", now.minusSeconds(2000))
        feed("/later.xml", now.plusSeconds(600))

        assertEquals(2, pollingService.pollDue(now))
        assertEquals(listOf("/a.xml", "/b.xml"), hits.toList())
        val a = feedRepository.findByFeedUrl("$baseUrl/a.xml")!!
        assertTrue(a.nextPollAt.isAfter(now.plus(Duration.ofMinutes(59))), a.nextPollAt.toString())
        assertTrue(
            a.lastFetchedAt != null && !a.lastFetchedAt!!.isBefore(now),
            a.lastFetchedAt.toString(),
        )

        assertEquals(1, pollingService.pollDue(now.plusSeconds(90)))
        assertEquals(listOf("/a.xml", "/b.xml", "/c.xml"), hits.toList())
        assertEquals(0, pollingService.pollDue(now.plusSeconds(180)))
    }

    @Test
    fun `a failing feed backs off exponentially up to the cap and recovers on success`() {
        val now = Instant.now()
        serve("/dead.xml", "dead", status = 500)
        val dead =
            feedRepository.save(
                RssFeed(
                    feedUrl = "$baseUrl/dead.xml",
                    title = "dead",
                    nextPollAt = now.minusSeconds(1),
                )
            )

        pollingService.pollDue(now)
        var current = feedRepository.findById(dead.id).get()
        assertEquals(1, current.pollFailures)
        assertEquals(now.plus(Duration.ofHours(1)).epochSecond, current.nextPollAt.epochSecond)

        pollingService.pollDue(current.nextPollAt)
        current = feedRepository.findById(dead.id).get()
        assertEquals(2, current.pollFailures)
        pollingService.pollDue(current.nextPollAt)
        pollingService.pollDue(feedRepository.findById(dead.id).get().nextPollAt)
        current = feedRepository.findById(dead.id).get()
        assertEquals(4, current.pollFailures)
        val third = feedRepository.findById(dead.id).get()
        assertTrue(
            Duration.between(now, third.nextPollAt) <= Duration.ofHours(1 + 2 + 4 + 4),
            third.nextPollAt.toString(),
        )

        /* Back to life: one success resets the failure count and schedules a normal interval */
        httpServer.removeContext("/dead.xml")
        serve("/dead.xml", "dead")
        val at = current.nextPollAt
        pollingService.pollDue(at)
        current = feedRepository.findById(dead.id).get()
        assertEquals(0, current.pollFailures)
        assertTrue(
            current.nextPollAt.isAfter(at.plus(Duration.ofMinutes(59))),
            current.nextPollAt.toString(),
        )
    }

    @Test
    fun `a newly added feed is scheduled one interval out rather than immediately due`() {
        serve("/fresh.xml", "fresh")
        val before = Instant.now()
        subscriptionService.addFeed("$baseUrl/fresh.xml")
        val fresh = feedRepository.findByFeedUrl("$baseUrl/fresh.xml")!!
        assertTrue(
            fresh.nextPollAt.isAfter(before.plus(Duration.ofMinutes(59))),
            fresh.nextPollAt.toString(),
        )
        assertEquals(0, pollingService.pollDue(Instant.now()))
    }
}
