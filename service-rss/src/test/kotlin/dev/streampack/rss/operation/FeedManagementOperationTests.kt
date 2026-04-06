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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class FeedManagementOperationTests {

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
        Provenance(
            protocol = Protocol.IRC,
            serviceId = "libera",
            replyTo = "#java",
            user = adminUser,
        )

    private fun message(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance()).build()

    private fun consoleProvenance() =
        Provenance(protocol = Protocol.CONSOLE, replyTo = "local", user = adminUser)

    private fun consoleMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, consoleProvenance()).build()

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

    private fun addFeed(title: String, entryCount: Int): String {
        val feedUrl = "$baseUrl/${title.lowercase().replace(" ", "-")}.xml"
        httpServer.createContext("/${title.lowercase().replace(" ", "-")}.xml") { exchange ->
            val rss = sampleRss(title, entryCount)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }
        val result = eventGateway.process(message("feed add $feedUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        return feedUrl
    }

    @Test
    fun `feed list with no feeds returns appropriate message`() {
        val result = eventGateway.process(message("feed list"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("No feeds registered", (result as OperationResult.Success).payload)
    }

    @Test
    fun `feed list with feeds shows URLs and titles`() {
        val feedUrl = addFeed("JVM News", 3)
        val result = eventGateway.process(message("feed list"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("JVM News"))
        assertTrue(payload.contains(feedUrl))
    }

    @Test
    fun `feed subscribe subscribes current channel and returns success`() {
        val feedUrl = addFeed("Tech Feed", 2)
        val result = eventGateway.process(message("feed subscribe $feedUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Subscribed to \"Tech Feed\"", (result as OperationResult.Success).payload)
    }

    @Test
    fun `feed subscribe twice returns already subscribed`() {
        val feedUrl = addFeed("Dupe Sub", 1)
        eventGateway.process(message("feed subscribe $feedUrl"))
        val result = eventGateway.process(message("feed subscribe $feedUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Already subscribed to \"Dupe Sub\"",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed subscribe with nonexistent URL returns error`() {
        val result =
            eventGateway.process(message("feed subscribe http://localhost:1/nonexistent.xml"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("No registered feed found"))
    }

    @Test
    fun `feed unsubscribe returns success`() {
        val feedUrl = addFeed("Unsub Feed", 1)
        eventGateway.process(message("feed subscribe $feedUrl"))
        val result = eventGateway.process(message("feed unsubscribe $feedUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Unsubscribed from \"Unsub Feed\"",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed unsubscribe when not subscribed returns error`() {
        val feedUrl = addFeed("Not Subbed", 1)
        val result = eventGateway.process(message("feed unsubscribe $feedUrl"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("Not subscribed"))
    }

    @Test
    fun `feed subscriptions shows current channel subscriptions`() {
        val feedUrl = addFeed("Sub List Feed", 2)
        eventGateway.process(message("feed subscribe $feedUrl"))
        val result = eventGateway.process(message("feed subscriptions"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Sub List Feed"))
        assertTrue(payload.contains(feedUrl))
    }

    @Test
    fun `feed subscriptions with none returns appropriate message`() {
        val result = eventGateway.process(message("feed subscriptions"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "No active subscriptions for this channel",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed remove deactivates feed and subscriptions`() {
        val feedUrl = addFeed("Remove Me", 1)
        eventGateway.process(message("feed subscribe $feedUrl"))
        val result = eventGateway.process(message("feed remove $feedUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Removed feed \"Remove Me\""))
        assertTrue(payload.contains("1 subscriptions deactivated"))
    }

    @Test
    fun `feed remove with nonexistent URL returns error`() {
        val result = eventGateway.process(message("feed remove http://localhost:1/nonexistent.xml"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("No registered feed found"))
    }

    @Test
    fun `feed remove on already inactive feed returns appropriate message`() {
        val feedUrl = addFeed("Inactive Feed", 1)
        eventGateway.process(message("feed remove $feedUrl"))
        val result = eventGateway.process(message("feed remove $feedUrl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success).payload.toString().contains("already inactive")
        )
    }

    @Test
    fun `feed subscribe resolves site URL to registered feed URL`() {
        httpServer.createContext("/site") { exchange ->
            val html = htmlWithFeedLink("$baseUrl/site-feed.xml")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
        httpServer.createContext("/site-feed.xml") { exchange ->
            val rss = sampleRss("Site Feed", 2)
            exchange.sendResponseHeaders(200, rss.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(rss.toByteArray()) }
        }
        // Add via the direct feed URL
        eventGateway.process(message("feed add $baseUrl/site-feed.xml"))

        // Subscribe via the site URL - should resolve to the registered feed
        val result = eventGateway.process(message("feed subscribe $baseUrl/site"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Subscribed to \"Site Feed\"", (result as OperationResult.Success).payload)
    }

    @Test
    fun `feed subscribe without admin role is not handled`() {
        val feedUrl = addFeed("Auth Test", 1)
        val guestProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val msg =
            MessageBuilder.withPayload("feed subscribe $feedUrl")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `feed list is accessible without admin role`() {
        val guestProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val msg =
            MessageBuilder.withPayload("feed list")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `feed subscriptions is accessible without admin role`() {
        val guestProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val msg =
            MessageBuilder.withPayload("feed subscriptions")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    // --- Explicit target tests ---

    @Test
    fun `feed subscribe with explicit target from console`() {
        val feedUrl = addFeed("Console Target", 2)
        val result =
            eventGateway.process(consoleMessage("feed subscribe $feedUrl to irc://libera/%23java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Subscribed to \"Console Target\"",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed subscribe with explicit target is visible via feed subscriptions for`() {
        val feedUrl = addFeed("Visible Sub", 1)
        eventGateway.process(consoleMessage("feed subscribe $feedUrl to irc://libera/%23java"))
        val result =
            eventGateway.process(consoleMessage("feed subscriptions for irc://libera/%23java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Visible Sub"))
    }

    @Test
    fun `feed unsubscribe with explicit target`() {
        val feedUrl = addFeed("Unsub Target", 1)
        eventGateway.process(consoleMessage("feed subscribe $feedUrl to irc://libera/%23java"))
        val result =
            eventGateway.process(
                consoleMessage("feed unsubscribe $feedUrl to irc://libera/%23java")
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "Unsubscribed from \"Unsub Target\"",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed subscriptions for with no subscriptions returns appropriate message`() {
        val result =
            eventGateway.process(consoleMessage("feed subscriptions for irc://libera/%23empty"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "No active subscriptions for this channel",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `feed subscriptions for is accessible without admin role`() {
        val guestProvenance = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        val msg =
            MessageBuilder.withPayload("feed subscriptions for irc://libera/%23java")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}
