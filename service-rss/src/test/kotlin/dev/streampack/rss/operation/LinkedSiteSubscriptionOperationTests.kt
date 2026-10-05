/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.LinkedSiteSubscription
import dev.streampack.core.model.LinkedSiteSubscription.Outcome
import dev.streampack.core.model.LinkedSiteSubscriptionRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.sampleRss
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Following the sites published posts link to (#128), as the blog's outgoing-links pass asks. */
@SpringBootTest
@Transactional
class LinkedSiteSubscriptionOperationTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var feedRepository: RssFeedRepository

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val fetches = AtomicInteger()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
        // Not "localhost", which other tests' feeds may be on.
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun serve(path: String, body: String, type: String = "text/html") {
        server.createContext(path) { ex ->
            fetches.incrementAndGet()
            if (ex.requestURI.path != path) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    /** Asked as the blog asks: no person, no role, not addressed. */
    private fun ask(pageUrl: String): LinkedSiteSubscription {
        val result =
            eventGateway.process(
                MessageBuilder.withPayload(
                        LinkedSiteSubscriptionRequest(
                            pageUrl,
                            "https://blog.example/posts/2026/10/p",
                        )
                            as Any
                    )
                    .setHeader(
                        Provenance.HEADER,
                        Provenance(
                            protocol = Protocol.HTTP,
                            serviceId = "blog",
                            replyTo = "outgoing-links",
                        ),
                    )
                    .build()
            )
        return (result as OperationResult.Success).payload as LinkedSiteSubscription
    }

    private fun blog() {
        serve(
            "/2026/01/post/",
            """<html><head>
            <link rel="alternate" type="application/rss+xml" title="Blog » Post Comments Feed" href="/2026/01/post/feed/">
            <link rel="alternate" type="application/rss+xml" title="Blog » Feed" href="/feed/">
            </head></html>""",
        )
        serve("/2026/01/post/feed/", sampleRss("Comments on: Post", 1, base), "application/rss+xml")
        serve("/feed/", sampleRss("Blog", 2, base), "application/rss+xml")
    }

    @Test
    fun `a linked blog's site feed is added, and once known isn't fetched again`() {
        blog()

        val first = ask("$base/2026/01/post/")

        assertEquals(LinkedSiteSubscription(Outcome.ADDED, feedUrl = "$base/feed/"), first)
        assertNotNull(feedRepository.findByFeedUrl("$base/feed/"))
        val fetched = fetches.get()

        val again = ask("$base/2026/02/another/")

        assertEquals(LinkedSiteSubscription(Outcome.ALREADY_HAVE, feedUrl = "$base/feed/"), again)
        assertEquals(fetched, fetches.get())
    }

    @Test
    fun `a host never subscribed to, or its subdomain, is skipped without a fetch`() {
        assertEquals(Outcome.SKIPPED, ask("https://github.com/streampack-dev/streampack").outcome)
        assertEquals(Outcome.SKIPPED, ask("https://gist.github.com/someone/1").outcome)
        assertEquals(Outcome.SKIPPED, ask("https://en.wikipedia.org/wiki/Java").outcome)
        assertEquals(Outcome.SKIPPED, ask("https://www.youtube.com/watch?v=x").outcome)
        // Personal blogs live on github.io.
        assertFalse(RssProperties().autosubscribe.skipHosts.any { "github.io".endsWith(it) })
    }

    @Test
    fun `a page with only a comments feed has no site feed`() {
        serve(
            "/post",
            """<html><head><link rel="alternate" type="application/rss+xml" title="Comments Feed" href="/comments/feed/"></head></html>""",
        )
        serve("/comments/feed/", sampleRss("Comments for Blog", 1, base), "application/rss+xml")

        assertEquals(Outcome.NO_FEED, ask("$base/post").outcome)
    }

    @Test
    fun `an internal address isn't fetched`() {
        assertEquals(Outcome.NO_FEED, ask("http://10.0.0.1/blog").outcome)
    }
}
