/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.blog.entity.Post
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import dev.streampack.ideas.service.FetchOutcome
import dev.streampack.ideas.service.SuggestedContentFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest(
    properties = ["streampack.generative.prompt-dir=/tmp/streampack-ideas-prompt-tests"]
)
@Import(SuggestArticleOperationTests.TestConfig::class)
class SuggestArticleOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var recordingAiService: RecordingAiService

    private val promptDir: Path = Path.of("/tmp/streampack-ideas-prompt-tests")

    private lateinit var adminUser: User
    private lateinit var regularUser: User

    private fun messageFor(user: UserPrincipal?, payload: String) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "test",
                    replyTo = "local",
                    user = user,
                ),
            )
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @BeforeEach
    fun setUp() {
        postRepository.deleteAll()
        Files.createDirectories(promptDir)
        Files.list(promptDir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        recordingAiService.rawResponse =
            """{"title":"Suggested Title","summary":"AI summary body.","tags":["jvm","kotlin","ai"]}"""
        recordingAiService.lastSystemPrompt = null
        val suffix = UUID.randomUUID().toString().take(8)

        adminUser =
            userRepository.save(
                User(
                    username = "suggest-admin-$suffix",
                    email = "suggest-admin-$suffix@test.com",
                    displayName = "Suggest Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )

        regularUser =
            userRepository.save(
                User(
                    username = "suggest-user-$suffix",
                    email = "suggest-user-$suffix@test.com",
                    displayName = "Suggest User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
    }

    @Test
    fun `admin suggest creates draft with mandatory source url`() {
        val result =
            eventGateway.process(
                messageFor(adminUser.toUserPrincipal(), "suggest https://good.example/article")
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Suggested draft saved"))

        val created = postRepository.findAll().firstOrNull()
        assertNotNull(created)
        val markdown = created!!.markdownSource
        assertEquals("Suggested Title", created.title)
        assertTrue(markdown.contains("Source: https://good.example/article"))
        assertTrue(markdown.contains("Generated from !suggest"))
        assertTrue(created.status.name == "DRAFT")
    }

    @Test
    fun `non-admin cannot use suggest`() {
        val result =
            eventGateway.process(
                messageFor(regularUser.toUserPrincipal(), "suggest https://good.example/article")
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Insufficient privileges"))
        assertEquals(0, postRepository.findAll().size)
    }

    @Test
    fun `suggest rejects non-http schemes`() {
        val result =
            eventGateway.process(
                messageFor(adminUser.toUserPrincipal(), "suggest ftp://example.com/article")
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Usage: suggest <http(s)://url>"))
    }

    @Test
    fun `suggest reports certificate warning on invalid tls`() {
        val result =
            eventGateway.process(
                messageFor(adminUser.toUserPrincipal(), "suggest https://badcert.example/article")
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("TLS certificate validation failed"))
        assertTrue(message.contains("Warning"))
    }

    @Test
    fun `suggest warns when final source remains http`() {
        val result =
            eventGateway.process(
                messageFor(adminUser.toUserPrincipal(), "suggest http://plain.example/article")
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val message = (result as OperationResult.Success).payload.toString()
        assertTrue(message.contains("plain HTTP"))

        val created: Post = postRepository.findAll().first()
        assertTrue(created.markdownSource.contains("Warnings:"))
        assertTrue(created.markdownSource.contains("plain HTTP"))
        assertFalse(created.markdownSource.contains("Source: http://good.example"))
    }

    @Test
    fun `suggest uses external prompt override when configured`() {
        promptDir.resolve("suggest-prompt.txt").toFile().writeText("EXTERNAL SUGGEST PROMPT")

        val result =
            eventGateway.process(
                messageFor(adminUser.toUserPrincipal(), "suggest https://good.example/article")
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val systemPrompt = recordingAiService.lastSystemPrompt
        assertTrue(systemPrompt!!.startsWith("EXTERNAL SUGGEST PROMPT"))
        assertTrue(systemPrompt.contains("Your response should be in JSON format."))
    }

    @TestConfiguration
    class TestConfig {
        @Bean fun aiProperties(): AiProperties = AiProperties(enabled = true)

        @Bean @Primary fun recordingAiService(): RecordingAiService = RecordingAiService()

        @Bean
        @Primary
        fun suggestedContentFetcher(): SuggestedContentFetcher =
            object : SuggestedContentFetcher {
                override fun fetch(url: String): FetchOutcome {
                    return when (url) {
                        "https://good.example/article" ->
                            FetchOutcome.Success(
                                requestedUrl = url,
                                finalUrl = url,
                                title = "Good Example Article",
                                extractedText =
                                    "Kotlin and JVM details with real engineering tradeoffs.",
                            )

                        "http://plain.example/article" ->
                            FetchOutcome.Success(
                                requestedUrl = url,
                                finalUrl = url,
                                title = "Plain HTTP Article",
                                extractedText = "This content was served over plain HTTP.",
                                warnings =
                                    listOf(
                                        "Warning: source resolved to plain HTTP without TLS (no HTTPS redirect observed)."
                                    ),
                            )

                        "https://badcert.example/article" ->
                            FetchOutcome.Failure(
                                message =
                                    "TLS certificate validation failed while fetching the URL",
                                certificateInvalid = true,
                            )

                        else -> FetchOutcome.Failure("Unhandled test URL")
                    }
                }
            }
    }

    class RecordingAiService : AiService(NoopChatModel(), AiProperties(enabled = true)) {
        var lastSystemPrompt: String? = null
        var rawResponse: String? = null

        override fun prompt(systemInstruction: String, userPrompt: String): String? {
            lastSystemPrompt = systemInstruction
            return rawResponse
        }
    }

    class NoopChatModel : org.springframework.ai.chat.model.ChatModel {
        override fun call(
            prompt: org.springframework.ai.chat.prompt.Prompt
        ): org.springframework.ai.chat.model.ChatResponse {
            throw UnsupportedOperationException()
        }
    }
}
