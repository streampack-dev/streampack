/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.blog.webconsole.WebConsoleTestSupport.Companion.await
import dev.streampack.blog.webconsole.WebConsoleTestSupport.Companion.never
import dev.streampack.core.model.Consumed
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.Operation
import dev.streampack.core.service.OperationService
import dev.streampack.core.service.ThrottleService
import dev.streampack.factoid.service.FactoidService
import dev.streampack.polling.service.EgressNotifier
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * The web console end to end (#115): commands in through POST, results out on the stream, on the
 * production ingress channel (an executor: commands run on their own threads, as they do live).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(WebConsoleFlowTests.TestOps::class)
class WebConsoleFlowTests {

    @TestConfiguration
    class TestOps {
        /** "twice": answers at once, then again later, as IRC-era operations may. */
        @Bean
        fun twice(@Qualifier("egressChannel") egress: MessageChannel) =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>) = message.payload == "twice"

                override fun execute(message: Message<*>): OperationOutcome {
                    val provenance = message.headers[Provenance.HEADER] as Provenance
                    Thread.ofVirtual().start {
                        Thread.sleep(150)
                        egress.send(
                            MessageBuilder.withPayload(OperationResult.Success("later") as Any)
                                .setHeader(Provenance.HEADER, provenance)
                                .build()
                        )
                    }
                    return OperationResult.Success("now")
                }
            }

        /** "lines": several lines, with text that must reach the client as text. */
        @Bean
        fun lines() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>) = message.payload == "lines"

                override fun execute(message: Message<*>): OperationOutcome =
                    OperationResult.Success("one\ntwo\n<b>three</b> & \"four\"\n\ndata: five")
            }

        /** "quietly": handled, with nothing to say. */
        @Bean
        fun quietly() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>) = message.payload == "quietly"

                override fun execute(message: Message<*>): OperationOutcome = Consumed("noted")
            }

        /** "crash": fails with internals no client should see. */
        @Bean
        fun crash() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>) = message.payload == "crash"

                override fun execute(message: Message<*>): OperationOutcome =
                    throw IllegalStateException("password=hunter2 at Foo.kt:12")
            }

        /** "superonly": a SUPER_ADMIN command. */
        @Bean
        fun superonly() =
            object : Operation {
                override val priority = 5

                override fun canHandle(message: Message<*>) = message.payload == "superonly"

                override fun execute(message: Message<*>): OperationOutcome =
                    requireRole(message, Role.SUPER_ADMIN) ?: OperationResult.Success("as you wish")
            }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var throttleService: ThrottleService
    @Autowired lateinit var streams: WebConsoleStreams
    @Autowired lateinit var factoidService: FactoidService
    @Autowired lateinit var notifier: EgressNotifier

    private lateinit var console: WebConsoleTestSupport
    private lateinit var adminId: java.util.UUID
    private lateinit var adminToken: String
    private lateinit var otherToken: String

    @BeforeEach
    fun setUp() {
        throttleService.clear()
        console = WebConsoleTestSupport(mockMvc, userRepository, jwtService)
        val admin = console.account("flowadmin", Role.ADMIN)
        adminId = admin.id
        adminToken = console.token(admin)
        otherToken = console.token(console.account("otheradmin", Role.ADMIN))
    }

    @AfterEach
    fun tearDown() {
        streams.closeAll()
    }

    @Test
    fun `factoids are set and read through the console, attributed to the admin`() {
        val stream = console.open(adminToken)

        val set = console.run(adminToken, "aho-corasick is a multi-pattern string search")
        await("the set") { console.results(stream, set).isNotEmpty() }
        val get = console.run(adminToken, "aho-corasick")
        await("the get") { console.results(stream, get).isNotEmpty() }

        val answer = console.results(stream, get).single()
        assertEquals("success", answer.field("status"))
        assertTrue(answer.field("text")!!.contains("multi-pattern string search"), answer.data)
        assertEquals("flowadmin", factoidService.findBySelector("aho-corasick").first().updatedBy)
    }

    @Test
    fun `see-also references reach the console as plain names`() {
        val stream = console.open(adminToken)
        for (line in
            listOf(
                "trie is a tree of prefixes",
                "aho-corasick is a matcher",
                "aho-corasick.seealso=trie",
            )) {
            val id = console.run(adminToken, line)
            await(line) { console.results(stream, id).isNotEmpty() }
        }

        val get = console.run(adminToken, "aho-corasick")
        await("the get") { console.results(stream, get).isNotEmpty() }

        val text = console.results(stream, get).single().field("text")!!
        assertTrue(text.contains("trie"), text)
        assertFalse(text.contains("{{ref:"), text)
    }

    @Test
    fun `a locked factoid is refused through the console too`() {
        val stream = console.open(adminToken)
        for (line in listOf("glass is fragile", "glass.lock")) {
            val id = console.run(adminToken, line)
            await(line) { console.results(stream, id).isNotEmpty() }
        }

        val id = console.run(adminToken, "glass is unbreakable")
        await("the refusal") { console.results(stream, id).isNotEmpty() }

        assertEquals("error", console.results(stream, id).single().field("status"))
    }

    @Test
    fun `a command may answer more than once, every answer carrying its id`() {
        val stream = console.open(adminToken)

        val id = console.run(adminToken, "twice")

        await("both answers") { console.results(stream, id).size == 2 }
        assertEquals(listOf("now", "later"), console.results(stream, id).map { it.field("text") })
    }

    @Test
    fun `text arrives as text, newlines and markup included, inside the event's framing`() {
        val stream = console.open(adminToken)

        val id = console.run(adminToken, "lines")
        await("the lines") { console.results(stream, id).isNotEmpty() }

        assertEquals(
            "one\ntwo\n<b>three</b> & \"four\"\n\ndata: five",
            console.results(stream, id).single().field("text"),
        )
        // One data line per event: the text's own newlines are escaped in its JSON, so they can't
        // end the event or start a field of their own.
        val raw = stream.response.contentAsString
        assertFalse(raw.contains("\ndata: five"), raw)
    }

    @Test
    fun `a command nothing handles says so, with no text`() {
        val stream = console.open(adminToken)

        val id = console.run(adminToken, "zzq xyzzy plugh frobnicate")
        await("the answer") { console.results(stream, id).isNotEmpty() }

        val answer = console.results(stream, id).single()
        assertEquals("unhandled", answer.field("status"))
        assertFalse(answer.has("text"), answer.data)
    }

    @Test
    fun `a command handled quietly says nothing`() {
        val stream = console.open(adminToken)

        val id = console.run(adminToken, "quietly")

        never { console.results(stream, id).isNotEmpty() }
    }

    @Test
    fun `a failing command is answered with a sanitized error, correlated`() {
        val stream = console.open(adminToken)

        val id = console.run(adminToken, "crash")
        await("the error") { console.results(stream, id).isNotEmpty() }

        val answer = console.results(stream, id).single()
        assertEquals("error", answer.field("status"))
        assertEquals(OperationService.COMMAND_FAILED, answer.field("text"))
        assertFalse(stream.response.contentAsString.contains("hunter2"))
    }

    @Test
    fun `an admin's console runs as an admin, not as anything more`() {
        val stream = console.open(adminToken)

        val id = console.run(adminToken, "superonly")
        await("the refusal") { console.results(stream, id).isNotEmpty() }

        assertEquals("error", console.results(stream, id).single().field("status"))
    }

    @Test
    fun `every window an admin has open gets the output, and no other admin does`() {
        val first = console.open(adminToken)
        val second = console.open(adminToken)
        val someoneElse = console.open(otherToken)

        val id = console.run(adminToken, "lines")

        await("both windows") {
            console.results(first, id).isNotEmpty() && console.results(second, id).isNotEmpty()
        }
        never { console.results(someoneElse, id).isNotEmpty() }
    }

    @Test
    fun `a notification addressed to an admin's console reaches it, with no command to answer`() {
        val stream = console.open(adminToken)

        notifier.send("the build finished", "webconsole://web/users/$adminId")

        await("the notification") { console.results(stream, null).isNotEmpty() }
        val event = console.results(stream, null).single()
        assertNull(event.field("correlationId"))
        assertEquals("the build finished", event.field("text"))
    }

    @Test
    fun `nothing reaches a console whose owner isn't an admin any more`() {
        val stream = console.open(adminToken)
        val admin = userRepository.findById(adminId).get()
        userRepository.save(admin.copy(role = Role.USER))

        notifier.send("for admins only", "webconsole://web/users/$adminId")

        never { console.results(stream, null).isNotEmpty() }
    }

    @Test
    fun `console traffic stays out of the public log browser`() {
        val stream = console.open(adminToken)
        val id = console.run(adminToken, "lines")
        await("the answer") { console.results(stream, id).isNotEmpty() }
        val uri =
            Provenance(Protocol.WEBCONSOLE, "web", replyTo = WebConsoleAddress.replyTo(adminId))
                .encode()

        mockMvc
            .get("/logs/provenances") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                jsonPath("$.provenances[*].protocol") {
                    value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("webconsole")))
                }
            }
        mockMvc
            .get("/logs") {
                header("Authorization", "Bearer $adminToken")
                param("provenance", uri)
            }
            .andExpect { status { isNotFound() } }
    }
}
