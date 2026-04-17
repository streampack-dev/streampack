/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.repository.RssFeedRepository
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RssOpmlServiceTests {

    @Autowired lateinit var opmlService: RssOpmlService
    @Autowired lateinit var feedRepository: RssFeedRepository

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
    fun `export emits active feeds as opml outlines`() {
        feedRepository.save(
            RssFeed(
                feedUrl = "https://example.com/feed.xml",
                siteUrl = "https://example.com",
                title = "Example Feed",
                active = true,
            )
        )
        feedRepository.save(
            RssFeed(
                feedUrl = "https://inactive.example.com/feed.xml",
                siteUrl = "https://inactive.example.com",
                title = "Inactive Feed",
                active = false,
            )
        )

        val xml = opmlService.exportFeedsAsOpml()

        assertTrue(xml.contains("<opml"), xml)
        assertTrue(xml.contains("xmlUrl=\"https://example.com/feed.xml\""), xml)
        assertTrue(xml.contains("htmlUrl=\"https://example.com\""), xml)
        assertTrue(xml.contains("Example Feed"), xml)
        assertTrue(!xml.contains("inactive.example.com"), xml)
    }

    @Test
    fun `import accepts valid opml and adds feeds`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = FeedDiscoveryServiceTests.sampleRss("Imported Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val summary =
            opmlService.importFeeds(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0">
                  <head><title>Subscriptions</title></head>
                  <body>
                    <outline text="Imported Feed" title="Imported Feed" type="rss" xmlUrl="$baseUrl/feed.xml" htmlUrl="$baseUrl" />
                  </body>
                </opml>
                """
                    .trimIndent()
            )

        assertEquals(1, summary.added)
        assertEquals(1, feedRepository.count())
    }

    @Test
    fun `import falls back to plaintext urls and ignores junk lines`() {
        httpServer.createContext("/one.xml") { exchange ->
            val rss = FeedDiscoveryServiceTests.sampleRss("One Feed", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }
        httpServer.createContext("/two.xml") { exchange ->
            val rss = FeedDiscoveryServiceTests.sampleRss("Two Feed", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val summary =
            opmlService.importFeeds(
                """
                $baseUrl/one.xml
                not a url

                $baseUrl/two.xml
                maybe later
                """
                    .trimIndent()
            )

        assertEquals(2, summary.added)
        assertTrue(summary.ignored >= 2)
        assertEquals(2, feedRepository.count())
    }
}
