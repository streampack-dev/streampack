/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.service.UserResolutionService
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.messaging.Message
import org.springframework.web.client.RestClient

/** Codes by Mattermost DM: username lookup on the server, a direct channel, a post. */
class MattermostCodeDeliveryTests {
    private lateinit var httpServer: HttpServer
    private lateinit var adapter: MattermostAdapter
    private val posted = CopyOnWriteArrayList<String>()
    private val directRequests = CopyOnWriteArrayList<String>()

    private fun json(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(
            status,
            if (body.isEmpty()) -1 else body.toByteArray().size.toLong(),
        )
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/api/v4/users/me") {
            json(it, 200, """{"id":"botuser01","username":"nevet"}""")
        }
        httpServer.createContext("/api/v4/users/username/alice") {
            json(
                it,
                200,
                """{"id":"u-alice","username":"alice","first_name":"Alice","last_name":"Liddell","nickname":""}""",
            )
        }
        httpServer.createContext("/api/v4/users/username/nobody") {
            json(it, 404, """{"message":"not found"}""")
        }
        httpServer.createContext("/api/v4/channels/direct") { exchange ->
            directRequests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            json(exchange, 201, """{"id":"dm01","type":"D"}""")
        }
        httpServer.createContext("/api/v4/posts") { exchange ->
            posted += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            json(exchange, 201, """{"id":"p1"}""")
        }
        httpServer.start()
        val gateway =
            object : EventGateway {
                override fun process(message: Message<*>): OperationResult =
                    OperationResult.NotHandled

                override fun send(message: Message<*>) {}
            }
        adapter =
            MattermostAdapter(
                serverName = "work",
                baseUrl = "http://localhost:${httpServer.address.port}",
                token = "mm-token",
                initialSignalCharacter = "!",
                reconnectDelay = Duration.ZERO,
                eventGateway = gateway,
                userResolutionService = Mockito.mock(UserResolutionService::class.java),
                restClientBuilder = RestClient.builder(),
            )
        adapter.identify()
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun delivery() = MattermostCodeDelivery { name ->
        if (name == "work") adapter else null
    }

    @Test
    fun `a username on a connected server resolves to a binding-shaped recipient`() {
        val recipient = delivery().resolve(CodeIdentity(CodeChannel.MATTERMOST, "Alice", "work"))
        assertNotNull(recipient)
        assertEquals("work/u-alice", recipient!!.key)
        assertEquals(Protocol.MATTERMOST, recipient.protocol)
        assertEquals("work", recipient.serviceId)
        assertEquals("u-alice", recipient.externalIdentifier)
        assertEquals("alice", recipient.username)
        assertEquals("Alice Liddell", recipient.displayName)
        assertNull(recipient.email)
    }

    @Test
    fun `unknown usernames and unknown or disconnected servers resolve to nothing`() {
        assertNull(delivery().resolve(CodeIdentity(CodeChannel.MATTERMOST, "nobody", "work")))
        assertNull(delivery().resolve(CodeIdentity(CodeChannel.MATTERMOST, "alice", "elsewhere")))
        assertNull(delivery().resolve(CodeIdentity(CodeChannel.MATTERMOST, "alice", null)))
        assertEquals(listOf("work"), delivery().servers())
    }

    @Test
    fun `delivery opens a direct channel with the user and posts the code there`() {
        val recipient = delivery().resolve(CodeIdentity(CodeChannel.MATTERMOST, "alice", "work"))!!
        delivery().deliver(recipient, "482913")
        assertEquals(1, directRequests.size)
        assertTrue(
            directRequests[0].contains("botuser01") && directRequests[0].contains("u-alice"),
            directRequests[0],
        )
        assertEquals(1, posted.size)
        assertTrue(posted[0].contains("\"channel_id\":\"dm01\""), posted[0])
        assertTrue(posted[0].contains("482913"), posted[0])
    }
}
