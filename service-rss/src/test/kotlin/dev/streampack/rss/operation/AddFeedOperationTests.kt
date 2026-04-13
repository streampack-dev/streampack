/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.htmlWithFeedLink
import dev.streampack.rss.service.FeedDiscoveryServiceTests.Companion.sampleRss
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class AddFeedOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local", user = adminUser)

    private fun message(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance()).build()

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
    fun `feed add with RSS URL returns success with title and entry count`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("JVM News", 4)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = eventGateway.process(message("feed add $baseUrl/feed.xml"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Added feed \"JVM News\" with 4 entries",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed add with website URL discovers feed and returns success`() {
        httpServer.createContext("/") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/rss")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/rss") { exchange ->
            val rss = sampleRss("Site Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = eventGateway.process(message("feed add $baseUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Added feed \"Site Feed\" with 2 entries",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed add tolerates duplicate guid entries in upstream feed`() {
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

        val result = eventGateway.process(message("feed add $baseUrl/duplicate-guid.xml"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Added feed \"Typotheque\" with 2 entries",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed add with invalid URL returns error`() {
        val result = eventGateway.process(message("feed add http://localhost:1/nonexistent"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "No RSS/Atom feed found at http://localhost:1/nonexistent",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `text that does not match feed add is not handled`() {
        val result = eventGateway.process(message("just a regular message"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `feed add is case insensitive`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("Case Test", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val result = eventGateway.process(message("Feed Add $baseUrl/feed.xml"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `messages from IRC protocol are accepted`() {
        httpServer.createContext("/feed.xml") { exchange ->
            val rss = sampleRss("IRC Feed", 1)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }

        val ircProvenance =
            Provenance(
                protocol = Protocol.IRC,
                serviceId = "libera",
                replyTo = "#java",
                user = adminUser,
            )
        val msg =
            MessageBuilder.withPayload("feed add $baseUrl/feed.xml")
                .setHeader(Provenance.HEADER, ircProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `feed add without admin role is not handled`() {
        val guestProvenance =
            Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")
        val msg =
            MessageBuilder.withPayload("feed add http://example.com/feed.xml")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `feed add with regular user role is not handled`() {
        val userPrincipal =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "regular",
                displayName = "Regular",
                role = Role.USER,
            )
        val userProvenance =
            Provenance(
                protocol = Protocol.IRC,
                serviceId = "libera",
                replyTo = "#java",
                user = userPrincipal,
            )
        val msg =
            MessageBuilder.withPayload("feed add http://example.com/feed.xml")
                .setHeader(Provenance.HEADER, userProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }
}
