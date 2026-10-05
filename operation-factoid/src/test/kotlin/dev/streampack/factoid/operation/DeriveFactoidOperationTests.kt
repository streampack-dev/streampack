/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ai.service.AiStructuredResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.DeriveFactoidRequest
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidDraft
import dev.streampack.factoid.operation.DeriveFactoidOperation.Proposal
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.messaging.support.MessageBuilder

/** Drafting a factoid (#132), with a stand-in for the model that says what it's told to. */
@SpringBootTest
class DeriveFactoidOperationTests {
    /** Answers with the proposals queued, in order, and keeps the prompts it was given. */
    class ScriptedAi :
        AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
        val proposals = ArrayDeque<Proposal>()
        val prompts = CopyOnWriteArrayList<String>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> promptForObjectWithRaw(
            systemInstruction: String,
            userPrompt: String,
            responseType: Class<T>,
        ): AiStructuredResponse<T> {
            prompts += userPrompt
            val next = proposals.removeFirstOrNull()
            return AiStructuredResponse(next as T?, next?.toString())
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun scriptedAi() = ScriptedAi()
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var ai: ScriptedAi
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var attributeRepository: FactoidAttributeRepository

    private lateinit var server: HttpServer
    private lateinit var base: String

    @BeforeEach
    fun setup() {
        attributeRepository.deleteAll()
        factoidRepository.deleteAll()
        ai.proposals.clear()
        ai.prompts.clear()
        factoid("osgi", "a module system for Java.", tags = "osgi,java")
        factoid("felix", "an OSGi framework.", tags = "osgi")
        factoid(
            "maven",
            "a build tool for Java projects, and the repository format most of them publish to.",
        )
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            ex.sendResponseHeaders(if (ex.requestURI.path == "/missing") 404 else 200, -1)
            ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun factoid(selector: String, text: String, tags: String? = null) {
        val saved = factoidRepository.save(Factoid(selector = selector, updatedBy = "test"))
        attributeRepository.save(
            FactoidAttribute(
                factoid = saved,
                attributeType = FactoidAttributeType.TEXT,
                attributeValue = text,
            )
        )
        tags?.let {
            attributeRepository.save(
                FactoidAttribute(
                    factoid = saved,
                    attributeType = FactoidAttributeType.TAGS,
                    attributeValue = it,
                )
            )
        }
    }

    private fun derive(
        selector: String,
        context: String = "",
        role: Role? = Role.USER,
    ): OperationResult {
        val user = role?.let {
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "author",
                displayName = "Author",
                role = it,
            )
        }
        return eventGateway.process(
            MessageBuilder.withPayload(DeriveFactoidRequest(selector, context) as Any)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "factoid",
                        replyTo = "derive",
                        user = user,
                    ),
                )
                .build()
        )
    }

    private fun draft(selector: String, context: String = "") =
        assertInstanceOf(OperationResult.Success::class.java, derive(selector, context)).payload
            as FactoidDraft

    @Test
    fun `a draft is held to the rules, its urls answering, its tags in use, its see-also existing`() {
        ai.proposals +=
            Proposal(
                text =
                    "karaf is a small OSGi runtime: a container for bundles, with provisioning and a shell.",
                urls =
                    listOf(
                        "$base/home",
                        "$base/missing",
                        "https://invented.invalid/docs",
                        "ftp://nope",
                    ),
                tags = listOf("osgi", "Java", "brand new", "another new"),
                seeAlso = listOf("osgi", "no such factoid", "karaf"),
            )

        val found = draft("karaf", "Karaf runs bundles on OSGi, like Felix does.")

        assertEquals(
            "a small OSGi runtime: a container for bundles, with provisioning and a shell.",
            found.text,
        )
        assertEquals(listOf("$base/home"), found.urls)
        assertEquals(listOf("$base/missing", "https://invented.invalid/docs"), found.droppedUrls)
        assertEquals(listOf("osgi", "java", "brand new"), found.tags)
        assertEquals(listOf("osgi"), found.seeAlso)
        assertTrue(found.line.startsWith("karaf is a small OSGi runtime"))
        assertTrue(found.line.contains("See also: "), found.line)
        assertEquals(found.line.length, found.lineLength)
        assertTrue(found.fits)
        // The model was offered the factoids the writing mentions, as candidates for see-also.
        assertTrue(
            ai.prompts
                .single()
                .contains(
                    "Candidates for seeAlso (existing factoids the writing mentions): osgi, felix"
                ),
            ai.prompts.single(),
        )
        // A real factoid, as the bot says it, for the voice.
        assertTrue(
            ai.prompts.single().contains("- maven is a build tool for Java projects"),
            ai.prompts.single(),
        )
    }

    @Test
    fun `a draft past the line is asked for once more, shorter`() {
        ai.proposals += Proposal(text = "a runtime ".repeat(40).trim())
        ai.proposals += Proposal(text = "a small OSGi runtime.")

        val found = draft("karaf")

        assertEquals("a small OSGi runtime.", found.text)
        assertTrue(found.fits)
        assertEquals(2, ai.prompts.size)
        assertTrue(ai.prompts[1].contains("Write the text again in under"))
    }

    @Test
    fun `a draft still too long is returned as it is, marked, never cut`() {
        val long = "a runtime ".repeat(40).trim()
        ai.proposals += Proposal(text = long)
        ai.proposals += Proposal(text = long)

        val found = draft("karaf")

        assertEquals(long, found.text)
        assertFalse(found.fits)
    }

    @Test
    fun `a name that's taken isn't drafted, and the model isn't asked`() {
        val result = derive("OSGi")

        assertEquals(
            "A factoid named \"OSGi\" already exists.",
            (result as OperationResult.Error).message,
        )
        assertTrue(ai.prompts.isEmpty())
    }

    @Test
    fun `only for signed-in readers`() {
        assertInstanceOf(OperationResult.Error::class.java, derive("karaf", role = null))
        assertTrue(ai.prompts.isEmpty())
    }
}
