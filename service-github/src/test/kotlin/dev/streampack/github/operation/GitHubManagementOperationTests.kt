/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.github.service.GitHubApiClient
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestSecurityConfiguration::class)
@ResourceLock("github-api-endpoint")
class GitHubManagementOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private lateinit var httpServer: HttpServer
    private var originalApiEndpoint: String? = null

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private fun ircProvenance() =
        Provenance(
            protocol = Protocol.IRC,
            serviceId = "libera",
            replyTo = "#java",
            user = adminUser,
        )

    private fun consoleProvenance() =
        Provenance(protocol = Protocol.CONSOLE, replyTo = "local", user = adminUser)

    private fun ircMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, ircProvenance()).build()

    private fun consoleMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, consoleProvenance()).build()

    @BeforeEach
    fun setUp() {
        originalApiEndpoint = GitHubApiClient.apiEndpoint
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        GitHubApiClient.apiEndpoint = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
        GitHubApiClient.apiEndpoint = originalApiEndpoint
    }

    private fun stubValidRepo(owner: String, name: String) {
        httpServer.createContext("/repos/$owner/$name") { exchange ->
            val body = """{"id": 1, "full_name": "$owner/$name"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/issues") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/pulls") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/repos/$owner/$name/releases") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
    }

    private fun addRepo(owner: String, name: String) {
        stubValidRepo(owner, name)
        val result = eventGateway.process(ircMessage("github add $owner/$name"))
        assertInstanceOf(
            OperationResult.Success::class.java,
            result,
            "github add $owner/$name returned $result",
        )
    }

    @Test
    fun `github list with no repos returns appropriate message`() {
        val result = eventGateway.process(ircMessage("github list"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "No GitHub repositories registered",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `github list with repos shows owner slash name`() {
        addRepo("owner", "repo")
        val result = eventGateway.process(ircMessage("github list"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("owner/repo"))
    }

    @Test
    fun `github subscribe and unsubscribe lifecycle`() {
        addRepo("owner", "repo")

        val sub = eventGateway.process(ircMessage("github subscribe owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, sub)
        assertEquals("Subscribed to owner/repo", (sub as OperationResult.Success).payload)

        val dupe = eventGateway.process(ircMessage("github subscribe owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, dupe)
        assertTrue(
            (dupe as OperationResult.Success).payload.toString().contains("Already subscribed")
        )

        val unsub = eventGateway.process(ircMessage("github unsubscribe owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, unsub)
        assertEquals("Unsubscribed from owner/repo", (unsub as OperationResult.Success).payload)

        val notSub = eventGateway.process(ircMessage("github unsubscribe owner/repo"))
        assertInstanceOf(OperationResult.Error::class.java, notSub)
        assertTrue((notSub as OperationResult.Error).message.contains("Not subscribed"))
    }

    @Test
    fun `github subscribe to nonexistent repo returns error`() {
        val result = eventGateway.process(ircMessage("github subscribe owner/missing"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("No registered repository"))
    }

    @Test
    fun `github subscriptions shows current channel subscriptions`() {
        addRepo("owner", "repo")
        eventGateway.process(ircMessage("github subscribe owner/repo"))

        val result = eventGateway.process(ircMessage("github subscriptions"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("owner/repo"))
    }

    @Test
    fun `github subscriptions uses comma separator for multiple repos`() {
        addRepo("owner", "repo-one")
        addRepo("owner", "repo-two")
        eventGateway.process(ircMessage("github subscribe owner/repo-one"))
        eventGateway.process(ircMessage("github subscribe owner/repo-two"))

        val result = eventGateway.process(ircMessage("github subscriptions"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("owner/repo-one"))
        assertTrue(payload.contains("owner/repo-two"))
        assertTrue(payload.contains(", "))
        assertTrue(!payload.contains("\n"))
    }

    @Test
    fun `github subscriptions with none returns appropriate message`() {
        val result = eventGateway.process(ircMessage("github subscriptions"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "No active subscriptions for this channel",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `github remove deactivates repo and subscriptions`() {
        addRepo("owner", "repo")
        eventGateway.process(ircMessage("github subscribe owner/repo"))

        val result = eventGateway.process(ircMessage("github remove owner/repo"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Removed owner/repo"))
        assertTrue(payload.contains("1 subscriptions deactivated"))
    }

    @Test
    fun `github remove nonexistent returns error`() {
        val result = eventGateway.process(ircMessage("github remove owner/missing"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("No registered repository"))
    }

    @Test
    fun `github list is accessible without admin role`() {
        val guestProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val msg =
            MessageBuilder.withPayload("github list")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `github subscriptions is accessible without admin role`() {
        val guestProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val msg =
            MessageBuilder.withPayload("github subscriptions")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `github subscribe without admin role is not handled`() {
        addRepo("owner", "repo")
        val guestProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")
        val msg =
            MessageBuilder.withPayload("github subscribe owner/repo")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `github subscribe with explicit target from console`() {
        addRepo("owner", "repo")
        val result =
            eventGateway.process(
                consoleMessage("github subscribe owner/repo to irc://libera/%23java")
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Subscribed to owner/repo", (result as OperationResult.Success).payload)
    }

    @Test
    fun `github subscriptions for explicit target`() {
        addRepo("owner", "repo")
        eventGateway.process(consoleMessage("github subscribe owner/repo to irc://libera/%23java"))
        val result =
            eventGateway.process(consoleMessage("github subscriptions for irc://libera/%23java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("owner/repo"))
    }

    @Test
    fun `github subscriptions for explicit target uses comma separator for multiple repos`() {
        addRepo("owner", "repo-one")
        addRepo("owner", "repo-two")
        eventGateway.process(
            consoleMessage("github subscribe owner/repo-one to irc://libera/%23java")
        )
        eventGateway.process(
            consoleMessage("github subscribe owner/repo-two to irc://libera/%23java")
        )

        val result =
            eventGateway.process(consoleMessage("github subscriptions for irc://libera/%23java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("owner/repo-one"))
        assertTrue(payload.contains("owner/repo-two"))
        assertTrue(payload.contains(", "))
        assertTrue(!payload.contains("\n"))
    }

    @Test
    fun `github subscriptions for is accessible without admin role`() {
        val guestProvenance = Provenance(protocol = Protocol.CONSOLE, replyTo = "local")
        val msg =
            MessageBuilder.withPayload("github subscriptions for irc://libera/%23java")
                .setHeader(Provenance.HEADER, guestProvenance)
                .build()

        val result = eventGateway.process(msg)
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}
