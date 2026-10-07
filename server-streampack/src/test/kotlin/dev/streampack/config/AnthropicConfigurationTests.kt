/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions

/** The chat model is built from the configured key, model and token limit, without calling out. */
class AnthropicConfigurationTests {
    private val configuration = AnthropicConfiguration()

    @Test
    fun `no key, no chat model`() {
        assertNull(configuration.anthropicChatModel(AiProperties(enabled = true, apiKey = "")))
    }

    @Test
    fun `a key gives a chat model with the configured model and token limit`() {
        val model =
            configuration.anthropicChatModel(
                AiProperties(
                    enabled = true,
                    apiKey = "sk-ant-test",
                    model = "claude-sonnet-4-5-20250929",
                    maxTokens = 777,
                )
            )
        val anthropic = assertInstanceOf(AnthropicChatModel::class.java, model)
        val options = assertInstanceOf(AnthropicChatOptions::class.java, anthropic.defaultOptions)
        assertEquals("claude-sonnet-4-5-20250929", options.model)
        assertEquals(777, options.maxTokens)
        // Bounded, so a stalled call ends (the client's own default is ten minutes a try)
        assertEquals(java.time.Duration.ofSeconds(60), options.timeout)
        assertEquals(1, options.maxRetries)
        // Nothing about thinking unless asked: Opus 5.5 refuses "disabled"
        assertNull(options.thinking)
        assertNull(options.outputConfig?.effort()?.orElse(null))
    }

    @Test
    fun `the timeout and retries are the configured ones`() {
        val model =
            configuration.anthropicChatModel(
                AiProperties(
                    enabled = true,
                    apiKey = "sk-ant-test",
                    timeout = java.time.Duration.ofSeconds(20),
                    maxRetries = 0,
                )
            )
        val options =
            assertInstanceOf(
                AnthropicChatOptions::class.java,
                assertInstanceOf(AnthropicChatModel::class.java, model).defaultOptions,
            )
        assertEquals(java.time.Duration.ofSeconds(20), options.timeout)
        assertEquals(0, options.maxRetries)
    }

    @Test
    fun `thinking is adaptive when asked for`() {
        val model =
            configuration.anthropicChatModel(
                AiProperties(enabled = true, apiKey = "sk-ant-test", thinking = true)
            )
        val options =
            assertInstanceOf(
                AnthropicChatOptions::class.java,
                assertInstanceOf(AnthropicChatModel::class.java, model).defaultOptions,
            )
        assertEquals(true, options.thinking?.isAdaptive())
    }

    private fun optionsFor(properties: AiProperties): AnthropicChatOptions =
        assertInstanceOf(
            AnthropicChatOptions::class.java,
            assertInstanceOf(
                    AnthropicChatModel::class.java,
                    configuration.anthropicChatModel(properties),
                )
                .defaultOptions,
        )

    @Test
    fun `an effort is sent when set, and a mistyped one is ignored`() {
        val low = optionsFor(AiProperties(enabled = true, apiKey = "sk-ant-test", effort = "LOW"))
        assertEquals(
            com.anthropic.models.messages.OutputConfig.Effort.LOW,
            low.outputConfig?.effort()?.orElse(null),
        )
        val typo =
            optionsFor(AiProperties(enabled = true, apiKey = "sk-ant-test", effort = "lowest"))
        assertNull(typo.outputConfig?.effort()?.orElse(null))
    }

    @Test
    fun `the default model is Opus 5_5`() {
        assertEquals(
            "claude-opus-5-5",
            optionsFor(AiProperties(enabled = true, apiKey = "sk-ant-test")).model,
        )
    }

    /**
     * Sends a default prompt and a moderation prompt through a real chat model to a stand-in for
     * the API, and returns the two request bodies as sent: what the API would have to accept.
     */
    private fun requestsSent(properties: AiProperties): List<JsonNode> {
        val bodies = mutableListOf<JsonNode>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/messages") { exchange ->
            val request = ObjectMapper().readTree(exchange.requestBody.readAllBytes())
            synchronized(bodies) { bodies.add(request) }
            val answer =
                """{"id":"msg_1","type":"message","role":"assistant",
                   "model":${ObjectMapper().writeValueAsString(request["model"].asText())},
                   "content":[{"type":"text","text":"fine"}],
                   "stop_reason":"end_turn","stop_sequence":null,
                   "usage":{"input_tokens":3,"output_tokens":1}}"""
                    .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
        try {
            val chatModel =
                AnthropicChatModel.builder()
                    .options(
                        configuration
                            .chatOptions(properties, properties.model)
                            .mutate()
                            .baseUrl("http://127.0.0.1:${server.address.port}")
                            .build()
                    )
                    .build()
            val ai =
                AiService(chatModel, properties, configuration.anthropicModelOptions(properties))
            assertEquals("fine", ai.prompt("be brief", "hello"))
            assertEquals("fine", ai.moderation().prompt("be brief", "is this spam?"))
        } finally {
            server.stop(0)
        }
        return bodies
    }

    @Test
    fun `a moderation call asks for the moderation model, without the default's effort`() {
        val (general, moderation) =
            requestsSent(
                AiProperties(
                    enabled = true,
                    apiKey = "sk-ant-test",
                    maxTokens = 777,
                    maxRetries = 0,
                    effort = "low",
                )
            )

        assertEquals("claude-opus-5-5", general["model"].asText())
        assertEquals("low", general["output_config"]?.get("effort")?.asText())
        // Never "disabled": Opus 5.5 refuses it
        assertNull(general["thinking"])

        assertEquals("claude-haiku-4-5-20251001", moderation["model"].asText())
        assertEquals(777, moderation["max_tokens"].asInt())
        // Haiku 4.5 rejects an effort, and thinking was chosen for the default model
        assertNull(moderation["output_config"])
        assertNull(moderation["thinking"])
        assertTrue(moderation["messages"].toString().contains("is this spam?"))
    }

    @Test
    fun `a moderation call doesn't carry the default's thinking either`() {
        val (general, moderation) =
            requestsSent(
                AiProperties(
                    enabled = true,
                    apiKey = "sk-ant-test",
                    maxRetries = 0,
                    thinking = true,
                )
            )
        assertEquals("adaptive", general["thinking"]?.get("type")?.asText())
        assertNull(moderation["thinking"])
    }
}
