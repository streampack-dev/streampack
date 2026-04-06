/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.rss.config.RssProperties
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

class FeedDiscoveryServiceTests {

    private lateinit var httpServer: HttpServer
    private lateinit var service: FeedDiscoveryService
    private var baseUrl: String = ""

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 10)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
        service = FeedDiscoveryService(RssProperties())
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `direct RSS URL returns parsed feed`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Test RSS Feed", 3)
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = service.discover("$baseUrl/feed.xml")
        assertNotNull(result)
        assertEquals("$baseUrl/feed.xml", result!!.feedUrl)
        assertEquals("Test RSS Feed", result.feed.title)
        assertEquals(3, result.feed.entries.size)
    }

    @Test
    fun `direct Atom URL returns parsed feed`() {
        httpServer.createContext("/atom.xml") { exchange ->
            val atom = sampleAtom("Test Atom Feed", 2)
            exchange.responseHeaders.add("Content-Type", "application/atom+xml")
            exchange.sendResponseHeaders(200, atom.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(atom.toByteArray()) }
        }

        val result = service.discover("$baseUrl/atom.xml")
        assertNotNull(result)
        assertEquals("$baseUrl/atom.xml", result!!.feedUrl)
        assertEquals("Test Atom Feed", result.feed.title)
        assertEquals(2, result.feed.entries.size)
    }

    @Test
    fun `HTML page with feed link discovers and parses the feed`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/rss")
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/rss") { exchange ->
            val rss = sampleRss("Discovered Feed", 5)
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = service.discover(baseUrl)
        assertNotNull(result)
        assertEquals("$baseUrl/rss", result!!.feedUrl)
        assertEquals("Discovered Feed", result.feed.title)
        assertEquals(5, result.feed.entries.size)
    }

    @Test
    fun `HTML page with multiple feed links uses the first valid one`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithMultipleFeedLinks("$baseUrl/bad-feed", "$baseUrl/good-feed")
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/bad-feed") { exchange ->
            val body = "this is not valid xml"
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/good-feed") { exchange ->
            val rss = sampleRss("Second Feed", 1)
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = service.discover(baseUrl)
        assertNotNull(result)
        assertEquals("$baseUrl/good-feed", result!!.feedUrl)
        assertEquals("Second Feed", result.feed.title)
    }

    @Test
    fun `HTML page with anchor feed link discovers and parses the feed`() {
        httpServer.createContext("/") { exchange ->
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head><title>Site</title></head>
                <body><a href="/feed.xml">RSS</a></body>
                </html>
                """
                    .trimIndent()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Anchor Feed", 2)
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = service.discover(baseUrl)
        assertNotNull(result)
        assertEquals("$baseUrl/feed.xml", result!!.feedUrl)
        assertEquals("Anchor Feed", result.feed.title)
    }

    @Test
    fun `HTML page with no feed hints falls back to common feed path`() {
        httpServer.createContext("/") { exchange ->
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head><title>Site</title></head>
                <body><p>No feed hints here.</p></body>
                </html>
                """
                    .trimIndent()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Fallback Feed", 1)
            exchange.responseHeaders.add("Content-Type", "application/rss+xml")
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = service.discover(baseUrl)
        assertNotNull(result)
        assertEquals("$baseUrl/feed.xml", result!!.feedUrl)
        assertEquals("Fallback Feed", result.feed.title)
    }

    @Test
    fun `invalid URL returns null`() {
        val result = service.discover("http://localhost:1/nonexistent")
        assertNull(result)
    }

    @Test
    fun `URL that returns non-RSS non-HTML returns null`() {
        httpServer.createContext("/plain") { exchange ->
            val body = "just plain text without any xml or html"
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        val result = service.discover("$baseUrl/plain")
        assertNull(result)
    }

    @Test
    fun `HTTP error status returns null`() {
        httpServer.createContext("/error") { exchange -> exchange.sendResponseHeaders(404, -1) }

        val result = service.discover("$baseUrl/error")
        assertNull(result)
    }

    @Test
    fun `non-2xx response with XML body is still parsed`() {
        httpServer.createContext("/misconfigured") { exchange ->
            val rss = sampleRss("Misconfigured Server Feed", 2)
            exchange.sendResponseHeaders(404, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = service.discover("$baseUrl/misconfigured")
        assertNotNull(result)
        assertEquals("Misconfigured Server Feed", result!!.feed.title)
        assertEquals(2, result.feed.entries.size)
    }

    @Test
    fun `non-2xx response with non-XML body returns null`() {
        httpServer.createContext("/bad") { exchange ->
            val body = "404 Not Found"
            exchange.sendResponseHeaders(404, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        val result = service.discover("$baseUrl/bad")
        assertNull(result)
    }

    @EnabledIfSystemProperty(named = "live.tests", matches = "true")
    @Test
    fun `live fetch of primate run blog rss`() {
        val liveService = FeedDiscoveryService(RssProperties())
        val result = liveService.discover("https://primate.run/blog.rss")
        assertNotNull(result, "Discovery should succeed for primate.run/blog.rss")
        assertEquals("https://primate.run/blog.rss", result!!.feedUrl)
        assertTrue(result.feed.title.isNotBlank(), "Feed title should not be blank")
        assertTrue(result.feed.entries.isNotEmpty(), "Feed should have at least one entry")
    }

    companion object {
        fun sampleRss(title: String, entryCount: Int): String {
            val items =
                (1..entryCount).joinToString("\n") { i ->
                    """
                <item>
                    <title>Entry $i</title>
                    <link>http://example.com/entry/$i</link>
                    <guid>http://example.com/entry/$i</guid>
                    <pubDate>Mon, 01 Jan 2026 00:00:00 GMT</pubDate>
                </item>
            """
                        .trimIndent()
                }

            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                    <channel>
                        <title>$title</title>
                        <link>http://example.com</link>
                        <description>A test feed</description>
                        $items
                    </channel>
                </rss>
            """
                .trimIndent()
        }

        fun sampleAtom(title: String, entryCount: Int): String {
            val entries =
                (1..entryCount).joinToString("\n") { i ->
                    """
                <entry>
                    <title>Entry $i</title>
                    <link href="http://example.com/entry/$i"/>
                    <id>urn:uuid:entry-$i</id>
                    <updated>2026-01-01T00:00:00Z</updated>
                </entry>
            """
                        .trimIndent()
                }

            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                    <title>$title</title>
                    <link href="http://example.com"/>
                    <id>urn:uuid:test-feed</id>
                    <updated>2026-01-01T00:00:00Z</updated>
                    $entries
                </feed>
            """
                .trimIndent()
        }

        fun htmlWithFeedLink(feedUrl: String): String {
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Test Site</title>
                    <link rel="alternate" type="application/rss+xml" title="RSS" href="$feedUrl"/>
                </head>
                <body><p>Hello</p></body>
                </html>
            """
                .trimIndent()
        }

        fun htmlWithMultipleFeedLinks(vararg feedUrls: String): String {
            val links =
                feedUrls.joinToString("\n") { url ->
                    """<link rel="alternate" type="application/rss+xml" title="RSS" href="$url"/>"""
                }
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Test Site</title>
                    $links
                </head>
                <body><p>Hello</p></body>
                </html>
            """
                .trimIndent()
        }
    }
}
