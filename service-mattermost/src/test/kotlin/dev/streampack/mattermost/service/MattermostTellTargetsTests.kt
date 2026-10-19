/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.TellTargetResolution
import dev.streampack.core.service.UserResolutionService
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostServerRepository
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.messaging.Message
import org.springframework.web.client.RestClient

/**
 * `tell` targets on Mattermost (#262), read against a stub server: a user's DM channel from
 * `@name`, a channel's id from its name, and plain refusals for what isn't there.
 */
class MattermostTellTargetsTests {
    private lateinit var httpServer: HttpServer
    private lateinit var adapter: MattermostAdapter
    private val directRequests = CopyOnWriteArrayList<String>()
    private val searches = CopyOnWriteArrayList<String>()

    private val repository = Mockito.mock(MattermostServerRepository::class.java)
    private val manager = Mockito.mock(MattermostConnectionManager::class.java)

    @Suppress("UNCHECKED_CAST")
    private val connections =
        Mockito.mock(ObjectProvider::class.java) as ObjectProvider<MattermostConnectionManager>
    private val targets = MattermostTellTargets(repository, connections)

    private val fooBarId = "f00ba7000000000000000000ab"
    private val dmId = "d1rec7000000000000000000ab"

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/api/v4/users/me/teams") {
            json(it, 200, """[{"id":"team1","name":"eng","display_name":"Engineering"}]""")
        }
        httpServer.createContext("/api/v4/users/me") {
            json(it, 200, """{"id":"botuser01","username":"nevet"}""")
        }
        httpServer.createContext("/api/v4/users/username/joseph.ottinger") {
            json(it, 200, """{"id":"u-joseph","username":"joseph.ottinger"}""")
        }
        httpServer.createContext("/api/v4/users/username/nobody") {
            json(it, 404, """{"message":"not found"}""")
        }
        httpServer.createContext("/api/v4/channels/direct") { exchange ->
            directRequests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            json(exchange, 201, """{"id":"$dmId","type":"D"}""")
        }
        httpServer.createContext("/api/v4/teams/team1/channels/search") { exchange ->
            // Mattermost's search ignores case
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8).lowercase()
            searches += body
            val fooBar =
                """{"id":"$fooBarId","name":"foo-bar","display_name":"Foo Bar","team_id":"team1","type":"O"}"""
            val fooBaz =
                """{"id":"f00ba2000000000000000000ab","name":"foo-baz","display_name":"Foo Baz","team_id":"team1","type":"O"}"""
            json(
                exchange,
                200,
                when {
                    body.contains("foo-bar") || body.contains("foo bar") -> "[$fooBar]"
                    body.contains("\"foo\"") -> "[$fooBar,$fooBaz]"
                    else -> "[]"
                },
            )
        }
        httpServer.start()
        val gateway =
            object : EventGateway {
                override fun process(message: Message<*>): OperationResult =
                    OperationResult.NotHandled

                override fun send(message: Message<*>) {}
            }
        val real =
            MattermostAdapter(
                serverName = "fn",
                baseUrl = "http://localhost:${httpServer.address.port}",
                token = "mm-token",
                initialSignalCharacter = "!",
                reconnectDelay = Duration.ZERO,
                eventGateway = gateway,
                userResolutionService = Mockito.mock(UserResolutionService::class.java),
                restClientBuilder = RestClient.builder(),
            )
        real.identify()
        adapter = Mockito.spy(real)
        Mockito.`when`(repository.findByNameAndDeletedFalse("fn"))
            .thenReturn(MattermostServer(name = "fn", baseUrl = "http://localhost"))
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun connected() {
        Mockito.`when`(connections.ifAvailable).thenReturn(manager)
        Mockito.`when`(manager.getAdapter("fn")).thenReturn(adapter)
        Mockito.doReturn(true).`when`(adapter).isConnected()
    }

    private fun target(replyTo: String, serviceId: String? = "fn") =
        Provenance(protocol = Protocol.MATTERMOST, serviceId = serviceId, replyTo = replyTo)

    private fun resolved(replyTo: String): Provenance =
        assertInstanceOf(
                TellTargetResolution.Resolved::class.java,
                targets.resolve(target(replyTo)),
            )
            .provenance

    private fun refusal(replyTo: String, serviceId: String? = "fn"): String =
        assertInstanceOf(
                TellTargetResolution.Refused::class.java,
                targets.resolve(target(replyTo, serviceId)),
            )
            .reason

    @Test
    fun `a user by @name gets the DM channel with the bot, marked direct`() {
        connected()
        val dm = resolved("@joseph.ottinger")
        assertEquals(dmId, dm.replyTo)
        assertEquals("fn", dm.serviceId)
        assertEquals("D", dm.metadata[DirectConversations.CHANNEL_TYPE])
        assertEquals(1, directRequests.size)
        assertTrue(
            directRequests[0].contains("botuser01") && directRequests[0].contains("u-joseph"),
            directRequests[0],
        )
    }

    @Test
    fun `a channel by URL name, by hash name, or by display name gets its id`() {
        connected()
        for (name in listOf("foo-bar", "#foo-bar", "Foo Bar", "foo bar")) {
            val channel = resolved(name)
            assertEquals(fooBarId, channel.replyTo, name)
            assertEquals("O", channel.metadata[DirectConversations.CHANNEL_TYPE], name)
        }
    }

    @Test
    fun `a channel id is used as it is, without a lookup, connected or not`() {
        assertEquals(fooBarId, resolved(fooBarId).replyTo)
        connected()
        assertEquals(fooBarId, resolved(fooBarId).replyTo)
        assertTrue(searches.isEmpty())
        assertTrue(directRequests.isEmpty())
    }

    @Test
    fun `an unknown user or channel is refused in plain words`() {
        connected()
        assertTrue(refusal("@nobody").contains("no Mattermost user @nobody"), refusal("@nobody"))
        assertTrue(refusal("nowhere").contains("nowhere"), refusal("nowhere"))
        assertTrue(directRequests.isEmpty())
    }

    @Test
    fun `a name that matches several channels is refused, naming them`() {
        connected()
        val reason = refusal("foo")
        assertTrue(reason.contains("Foo Bar") && reason.contains("Foo Baz"), reason)
    }

    @Test
    fun `a name on a disconnected, unknown, or missing server is refused`() {
        assertTrue(refusal("@joseph.ottinger").contains("isn't connected"))
        assertTrue(refusal("foo-bar", serviceId = "elsewhere").contains("no Mattermost server"))
        assertTrue(refusal("foo-bar", serviceId = null).contains("needs its server"))
    }

    @Test
    fun `no refusal shows a DM channel id`() {
        connected()
        Mockito.doReturn(null).`when`(adapter).directChannelId("u-joseph")
        val reason = refusal("@joseph.ottinger")
        assertTrue(reason.contains("couldn't open a direct message"), reason)
        assertFalse(reason.contains(dmId), reason)
    }
}
