/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.blog.model.DeriveSummaryRequest
import dev.streampack.blog.model.DeriveSummaryResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** A stand-in for the model: records what it was asked, and answers [reply]. */
class FakeAiService :
    AiService(
        object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = error("not used")
        },
        AiProperties(enabled = true),
    ) {
    var reply: String? = null
    val calls = mutableListOf<Pair<String, String>>()

    override fun prompt(systemInstruction: String, userPrompt: String): String? {
        calls += systemInstruction to userPrompt
        return reply
    }
}

/** Admins get an AI summary in BCN's editorial voice when AI is enabled (#102). */
@SpringBootTest
@Transactional
class DeriveSummaryAiTests {
    @TestConfiguration
    class Ai {
        @Bean fun aiService() = FakeAiService()
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var ai: FakeAiService

    private val admin = UserPrincipal(UUID.randomUUID(), "admin", "Admin", Role.ADMIN)
    private val reader = UserPrincipal(UUID.randomUUID(), "reader", "Reader", Role.USER)
    private val request =
        DeriveSummaryRequest(
            title = "Midden: heap dumps from the command line",
            markdownSource = "Midden reads heap dumps. It found both leaks. It is fast.",
        )

    @BeforeEach
    fun reset() {
        ai.calls.clear()
        ai.reply = "Midden is a Rust heap-dump analyzer that found both test leaks, fast."
    }

    private fun derive(user: UserPrincipal?): DeriveSummaryResponse {
        val message =
            MessageBuilder.withPayload(request)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog-service",
                        replyTo = "posts",
                        user = user,
                    ),
                )
                .build()
        val result = eventGateway.process(message)
        return (result as OperationResult.Success).payload as DeriveSummaryResponse
    }

    @Test
    fun `an admin gets the model's summary`() {
        val response = derive(admin)

        assertEquals("ai", response.source)
        assertEquals(
            "Midden is a Rust heap-dump analyzer that found both test leaks, fast.",
            response.summary,
        )
        assertEquals(1, ai.calls.size)
        val (system, user) = ai.calls.single()
        assertTrue(user.contains(request.title) && user.contains(request.markdownSource), user)
        // The default prompt carries the editorial stance: lead with the substance, never tease.
        assertTrue(system.contains("Lead with the substance"), system)
        assertTrue(system.contains("Never tease"), system)
    }

    @Test
    fun `a reader gets the heuristic, and the model is never asked`() {
        val response = derive(reader)

        assertEquals("heuristic", response.source)
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun `no one signed in gets the heuristic too`() {
        assertEquals("heuristic", derive(null).source)
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun `an empty or failed answer falls back to the heuristic`() {
        for (reply in listOf(null, "", "   ", "\"\"")) {
            ai.reply = reply
            val response = derive(admin)
            assertEquals("heuristic", response.source, "reply <$reply>")
            assertTrue(response.summary.isNotBlank())
        }
    }

    @Test
    fun `the model's answer is cleaned to plain text`() {
        ai.reply = "  Summary: **\"Midden** finds _both_ leaks, from `a shell`.\"\n\nFast.  "

        val response = derive(admin)

        assertEquals("ai", response.source)
        assertEquals("Midden finds both leaks, from a shell. Fast.", response.summary)
    }

    @Test
    fun `a long answer is cut at a word, within the excerpt's length`() {
        ai.reply = "word ".repeat(200).trim()

        val summary = derive(admin).summary

        assertTrue(summary.length <= 400, "${summary.length}")
        assertTrue(summary.endsWith("..."), summary)
        assertFalse(summary.contains("wor..."), summary)
    }
}
