/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import dev.streampack.ai.config.AiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
        // Off unless asked for: it spends the token budget on what nobody sees
        assertEquals(true, options.thinking?.isDisabled())
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
}
