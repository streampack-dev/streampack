/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.UserResolutionService
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.messaging.Message
import org.springframework.web.client.RestClient

/**
 * The adapter's message handling, driven by feeding WebSocket frames directly and serving the REST
 * calls it makes from a stub server. The socket transport itself is exercised against a real
 * Mattermost in the local-development setup, not here.
 */
class MattermostAdapterTests {
    private lateinit var httpServer: HttpServer
    private lateinit var adapter: MattermostAdapter
    private val dispatched = CopyOnWriteArrayList<Message<*>>()
    private val posted = CopyOnWriteArrayList<String>()
    private val authHeaders = CopyOnWriteArrayList<String?>()
    private val membership = CopyOnWriteArrayList<String>()
    private val connectedHooks = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var failNextDispatch = false
    /* The account's teams, and what a channel search on any of them finds */
    @Volatile private var teams = """[{"id":"team01","name":"crew","display_name":"Crew"}]"""
    @Volatile private var searchResults = "[]"

    private val gateway =
        object : EventGateway {
            override fun process(message: Message<*>): OperationResult {
                dispatched += message
                return OperationResult.NotHandled
            }

            override fun send(message: Message<*>) {
                if (failNextDispatch) {
                    failNextDispatch = false
                    throw IllegalStateException("gateway down")
                }
                dispatched += message
            }
        }

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/api/v4/users/me") { exchange ->
            authHeaders += exchange.requestHeaders.getFirst("Authorization")
            val body = """{"id": "botuser01", "username": "nevet"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/api/v4/users/me/teams") { exchange ->
            val body = if (exchange.requestURI.path.endsWith("/teams")) teams else "[]"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/api/v4/teams/") { exchange ->
            val body = searchResults
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/api/v4/channels/chan01/members") { exchange ->
            membership +=
                exchange.requestMethod +
                    " " +
                    exchange.requestURI.path +
                    " " +
                    exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val body =
                if (exchange.requestMethod == "DELETE") """{"status":"OK"}"""
                else """{"channel_id":"chan01","user_id":"botuser01"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(
                if (exchange.requestMethod == "DELETE") 200 else 201,
                body.toByteArray().size.toLong(),
            )
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.createContext("/api/v4/posts") { exchange ->
            authHeaders += exchange.requestHeaders.getFirst("Authorization")
            posted += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val body = """{"id": "newpost01"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(201, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        httpServer.start()
        val users = Mockito.mock(UserResolutionService::class.java)
        adapter =
            MattermostAdapter(
                serverName = "local",
                baseUrl = "http://localhost:${httpServer.address.port}",
                token = "mm-token",
                initialSignalCharacter = "!",
                reconnectDelay = Duration.ZERO,
                eventGateway = gateway,
                userResolutionService = users,
                restClientBuilder = RestClient.builder(),
                onConnected = { connectedHooks.incrementAndGet() },
            )
        adapter.identify()
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun posted(
        id: String,
        message: String,
        userId: String = "alice01",
        channelId: String = "chan01",
        channelType: String = "O",
        type: String = "",
        sender: String = "alice",
    ): String {
        val post =
            """{"id":"$id","user_id":"$userId","channel_id":"$channelId","message":${json(message)},"type":"$type"}"""
        return """{"event":"posted","seq":7,"broadcast":{"channel_id":"$channelId"},
            "data":{"channel_type":"$channelType","channel_name":"town-square","team_id":"team01","sender_name":"$sender","post":${json(post)}}}"""
    }

    private fun json(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun lastProvenance(): Provenance =
        dispatched.last().headers[Provenance.HEADER] as Provenance

    @Test
    fun `identify reads the account and sends the bearer token`() {
        assertEquals(listOf("Bearer mm-token"), authHeaders.distinct())
        assertTrue(adapter.wouldTriggerIngress("!version"))
        assertTrue(adapter.wouldTriggerIngress("@nevet hello"))
        assertFalse(adapter.wouldTriggerIngress("plain text"))
    }

    @Test
    fun `a channel post with the signal character is dispatched as addressed with the prefix stripped`() {
        adapter.handleFrame(posted("p1", "!version"))
        assertEquals(1, dispatched.size)
        val message = dispatched.single()
        assertEquals("version", message.payload)
        assertEquals(true, message.headers[Provenance.ADDRESSED])
        assertEquals("alice", message.headers["nick"])
        // The poster's id, for a private notice about a scrubbed secret (#148)
        assertEquals("alice01", message.headers[Provenance.SENDER_ID])
        // What it was resolved by, for whoami's link line (#247)
        assertEquals(
            dev.streampack.core.model.ServiceIdentity("local", "alice01"),
            message.headers[Provenance.IDENTITY],
        )
        val provenance = lastProvenance()
        assertEquals(Protocol.MATTERMOST, provenance.protocol)
        assertEquals("local", provenance.serviceId)
        assertEquals("chan01", provenance.replyTo)
        assertEquals("town-square", provenance.metadata["channelName"])
        assertEquals("nevet", provenance.metadata[Provenance.BOT_NICK])
    }

    @Test
    fun `mentions address the bot and plain channel chatter is ambient`() {
        adapter.handleFrame(posted("p2", "@nevet: karma kotlin"))
        assertEquals("karma kotlin", dispatched.last().payload)
        assertEquals(true, dispatched.last().headers[Provenance.ADDRESSED])

        adapter.handleFrame(posted("p3", "kotlin++"))
        assertEquals("kotlin++", dispatched.last().payload)
        assertEquals(false, dispatched.last().headers[Provenance.ADDRESSED])
    }

    @Test
    fun `direct messages are always addressed and reply to the DM channel`() {
        adapter.handleFrame(posted("p4", "version", channelId = "dm01", channelType = "D"))
        assertEquals("version", dispatched.last().payload)
        assertEquals(true, dispatched.last().headers[Provenance.ADDRESSED])
        assertEquals("dm01", lastProvenance().replyTo)
    }

    @Test
    fun `own posts, system posts, duplicates, and other events are ignored`() {
        adapter.handleFrame(posted("p5", "!version", userId = "botuser01"))
        adapter.handleFrame(posted("p6", "alice joined", type = "system_join_channel"))
        adapter.handleFrame(posted("p7", "!version"))
        adapter.handleFrame(posted("p7", "!version"))
        adapter.handleFrame("""{"event":"typing","data":{"user_id":"alice01"},"seq":9}""")
        adapter.handleFrame("""{"status":"OK","seq_reply":1}""")
        assertEquals(1, dispatched.size, dispatched.toString())
        assertTrue(adapter.isConnected())
    }

    @Test
    fun `replies are posted to the provenance channel through the REST API`() {
        adapter.sendReply(
            Provenance(Protocol.MATTERMOST, "local", replyTo = "chan01"),
            "kotlin has karma 3",
        )
        assertEquals(1, posted.size)
        assertTrue(posted[0].contains("\"channel_id\":\"chan01\""), posted[0])
        assertTrue(posted[0].contains("\"message\":\"kotlin has karma 3\""), posted[0])
    }

    @Test
    fun `malformed frames are tolerated`() {
        adapter.handleFrame("not json at all")
        adapter.handleFrame("""{"event":"posted","data":{"post":""}}""")
        assertEquals(0, dispatched.size)
        assertNull(dispatched.firstOrNull())
    }

    @Test
    fun `duplicate tracking is bounded and forgets the oldest ids`() {
        val limit = MattermostAdapter.RECENT_POST_LIMIT
        for (i in 0 until limit + 10) adapter.handleFrame(posted("id$i", "!version"))
        assertEquals(limit + 10, dispatched.size)
        adapter.handleFrame(posted("id0", "!version"))
        assertEquals(limit + 11, dispatched.size, "oldest id should have been forgotten")
        adapter.handleFrame(posted("id${limit + 9}", "!version"))
        assertEquals(limit + 11, dispatched.size, "recent id must still be deduplicated")
    }

    @Test
    fun `a post whose dispatch fails is not remembered as seen`() {
        failNextDispatch = true
        adapter.handleFrame(posted("retry1", "!version"))
        assertEquals(0, dispatched.size)
        adapter.handleFrame(posted("retry1", "!version"))
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `the signal character can change while connected`() {
        adapter.signalCharacter = "~"
        adapter.handleFrame(posted("s1", "~version"))
        assertEquals(true, dispatched.last().headers[Provenance.ADDRESSED])
        assertEquals("version", dispatched.last().payload)
        adapter.handleFrame(posted("s2", "!version"))
        assertEquals(false, dispatched.last().headers[Provenance.ADDRESSED])
        assertTrue(adapter.wouldTriggerIngress("~hi"))
        assertFalse(adapter.wouldTriggerIngress("!hi"))
    }

    @Test
    fun `join and leave change channel membership through the API`() {
        assertTrue(adapter.joinChannel("chan01"))
        assertTrue(adapter.leaveChannel("chan01"))
        assertEquals(2, membership.size, membership.toString())
        assertTrue(
            membership[0].startsWith("POST /api/v4/channels/chan01/members") &&
                membership[0].contains("botuser01"),
            membership[0],
        )
        assertTrue(
            membership[1].startsWith("DELETE /api/v4/channels/chan01/members/botuser01"),
            membership[1],
        )
    }

    @Test
    fun `the post-connect hook runs after each successful authentication`() {
        adapter.handleFrame("""{"status":"OK","seq_reply":1}""")
        adapter.handleFrame("""{"status":"OK","seq_reply":2}""")
        // Generous, for a starved machine (a parallel build, #107); it ends as soon as both have
        // run.
        val deadline = System.currentTimeMillis() + 30_000
        while (connectedHooks.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, connectedHooks.get())
    }

    @Test
    fun `status names the account it signed in as`() {
        assertEquals("reconnecting", adapter.status())
        adapter.handleFrame("""{"status":"OK","seq_reply":1}""")
        assertEquals("connected as nevet", adapter.status())
    }

    @Test
    fun `an account on no team is told so, not that there are no channels`() {
        teams = "[]"
        val e = assertThrows<MattermostLookupException> { adapter.listChannels(null) }
        assertEquals(
            "'nevet' is not a member of any team on 'local'; add it to a team on Mattermost",
            e.message,
        )
        assertThrows<MattermostLookupException> { adapter.resolveChannel("lantern") }
    }

    @Test
    fun `a channel that isn't found says what was searched`() {
        teams =
            """[{"id":"team01","name":"crew","display_name":"Crew"},
                {"id":"team02","name":"ops","display_name":"Operations"}]"""
        val e = assertThrows<MattermostLookupException> { adapter.resolveChannel("lantern") }
        assertEquals(
            "No public channel matches 'lantern' on Crew, Operations, the teams 'nevet' is on. " +
                "A private channel is joined by its id, once the account is added to it on Mattermost.",
            e.message,
        )
    }

    @Test
    fun `a display name with spaces finds its channel`() {
        searchResults =
            """[{"id":"chan02","name":"dev-ops","display_name":"Dev Ops","team_id":"team01","type":"O"},
                {"id":"chan03","name":"dev-ops-archive","display_name":"Dev Ops Archive","team_id":"team01","type":"O"}]"""
        assertEquals("chan02", adapter.resolveChannel("Dev Ops")?.id)
        assertEquals("chan02", adapter.resolveChannel("dev-ops")?.id)
    }

    @Test
    fun `several partial matches are listed, not called no match`() {
        searchResults =
            """[{"id":"chan04","name":"lantern-dev","display_name":"Lantern Dev","team_id":"team01","type":"O"},
                {"id":"chan05","name":"lantern-ops","display_name":"Lantern Ops","team_id":"team01","type":"O"}]"""
        val e = assertThrows<MattermostLookupException> { adapter.resolveChannel("lantern") }
        assertEquals(
            "No channel is named exactly 'lantern'; it is part of: Lantern Dev [chan04] on Crew, " +
                "Lantern Ops [chan05] on Crew. Use one's name or id.",
            e.message,
        )
    }
}
