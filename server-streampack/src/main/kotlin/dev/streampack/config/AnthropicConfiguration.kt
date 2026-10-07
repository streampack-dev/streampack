/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiModelOptions
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

        val options = chatOptions(properties, properties.model)
        logger.info(
            "Anthropic chat model: {}, at most {} tokens, {} timeout, {} retr{}, thinking {}, effort {}; moderation model: {}",
            properties.model,
            properties.maxTokens,
            properties.timeout,
            properties.maxRetries,
            if (properties.maxRetries == 1) "y" else "ies",
            if (properties.thinking) "on" else "not asked for",
            effort(properties.effort)?.toString()?.lowercase() ?: "the model's default",
            properties.moderationModel,
        )

        return AnthropicChatModel.builder().options(options).build()
    }

    /**
     * The options for a prompt on another model, such as the moderation model: Spring AI uses a
     * prompt's options instead of the chat model's, so they're built whole, as the default's are.
     */
    @Bean
    fun anthropicModelOptions(properties: AiProperties): AiModelOptions = AiModelOptions { model ->
        chatOptions(properties, model)
    }

    /**
     * The options for [model]. The timeout, retries and token limit hold for every model; thinking
     * and effort only for the default model, as they were chosen for it and a cheaper model may
     * refuse them (Haiku 4.5 rejects an effort, and takes thinking only as a token budget).
     */
    fun chatOptions(properties: AiProperties, model: String): AnthropicChatOptions {
        val isDefault = model == properties.model
        return AnthropicChatOptions.builder()
            .apiKey(properties.apiKey)
            .model(model)
            .maxTokens(properties.maxTokens)
            .timeout(properties.timeout)
            .maxRetries(properties.maxRetries)
            // Nothing about thinking unless it's asked for: some models refuse "disabled"
            .let {
                if (isDefault && properties.thinking)
                    it.thinkingAdaptive(ThinkingConfigAdaptive.Display.OMITTED)
                else it
            }
            .let { builder ->
                (if (isDefault) effort(properties.effort) else null)?.let { builder.effort(it) }
                    ?: builder
            }
            .build()
    }

    /** [value] as an effort level, or null: unset, or not one (which is logged, not fatal). */
    private fun effort(value: String?): OutputConfig.Effort? {
        val name = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (name !in EFFORTS) {
            logger.error(
                "streampack.ai.effort '{}' isn't one of {}; using the model's default",
                value,
                EFFORTS,
            )
            return null
        }
        return OutputConfig.Effort.of(name)
    }

    companion object {
        private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}
