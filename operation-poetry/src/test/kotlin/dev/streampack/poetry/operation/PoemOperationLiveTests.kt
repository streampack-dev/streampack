/* Joseph B. Ottinger (C)2026 */
package dev.streampack.poetry.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.TestPropertySource

/** Live tests that call the real Anthropic API. Run with: -Dlive.tests=true */
@SpringBootTest
@EnabledIfSystemProperty(named = "live.tests", matches = "true")
@TestPropertySource(properties = ["streampack.ai.enabled=true"])
class PoemOperationLiveTests {
    val logger = LoggerFactory.getLogger(PoemOperationLiveTests::class.java)

    @TestConfiguration
    class LiveAnthropicConfig {

        /** Builds a real AiService backed by the Anthropic API */
        @Bean
        @Primary
        fun aiService(properties: AiProperties): AiService {
            val api = AnthropicApi.builder().apiKey(properties.apiKey).build()
            val options =
                AnthropicChatOptions.builder()
                    .model(properties.model)
                    .maxTokens(properties.maxTokens)
                    .build()
            val chatModel =
                AnthropicChatModel.builder().anthropicApi(api).defaultOptions(options).build()
            return AiService(chatModel, properties)
        }
    }

    @Autowired lateinit var poemOperation: PoemOperation

    @Autowired lateinit var poemAnalysisOperation: PoemAnalysisOperation

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance())
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @Test
    fun `live poem generation produces slash-separated output`() {
        val result = poemOperation.execute(message("poem the beauty of sunsets"))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertTrue(success.loopback, "Result should request loopback")
        val poem = success.payload.toString()
        logger.info("Generated poem: $poem")
        assertTrue(poem.startsWith("a poem: "), "Live poem should start with 'a poem: '")
        assertTrue(poem.contains("/"), "Live poem should have slash-separated verses")
        assertTrue(poem.length > 20, "Live poem should have substance")
    }

    @Test
    fun `live poem analysis produces meaningful output`() {
        val poemText =
            "a poem: Shall I compare thee to a summer's day?/" +
                "Thou art more lovely and more temperate:/" +
                "Rough winds do shake the darling buds of May,/" +
                "And summer's lease hath all too short a date."
        val result = poemAnalysisOperation.execute(message(poemText))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val analysis = (result as OperationResult.Success).payload.toString()
        logger.info("Analysis: $analysis")
        assertTrue(analysis.startsWith("Analysis:"), "Should be prefixed with Analysis:")
        assertTrue(analysis.length > 30, "Analysis should have substance")
    }
}
