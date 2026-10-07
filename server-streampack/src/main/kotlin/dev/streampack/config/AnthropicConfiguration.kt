/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import dev.streampack.ai.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Provides an Anthropic ChatModel when AI is enabled and an API key is configured */
@Configuration
@ConditionalOnProperty(prefix = "streampack.ai", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(AiProperties::class)
class AnthropicConfiguration {
    private val logger = LoggerFactory.getLogger(AnthropicConfiguration::class.java)

    @Bean
    fun anthropicChatModel(properties: AiProperties): ChatModel? {
        if (properties.apiKey.isBlank()) {
            logger.warn(
                "streampack.ai.enabled=true but no API key configured; AI provider disabled"
            )
            return null
        }

        val options =
            AnthropicChatOptions.builder()
                .apiKey(properties.apiKey)
                .model(properties.model)
                .maxTokens(properties.maxTokens)
                .timeout(properties.timeout)
                .maxRetries(properties.maxRetries)
                .build()

        logger.info(
            "Anthropic chat model: {}, at most {} tokens, {} timeout, {} retr{}",
            properties.model,
            properties.maxTokens,
            properties.timeout,
            properties.maxRetries,
            if (properties.maxRetries == 1) "y" else "ies",
        )

        return AnthropicChatModel.builder().options(options).build()
    }
}
