/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.service

import dev.streampack.ai.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.converter.BeanOutputConverter

/** Thin wrapper around Spring AI ChatModel for prompt-based text generation */
open class AiService(private val chatModel: ChatModel, private val properties: AiProperties) {
    private val logger = LoggerFactory.getLogger(AiService::class.java)

    /** Sends a system instruction and user prompt to the model, returns the response text */
    open fun prompt(systemInstruction: String, userPrompt: String): String? {
        return try {
            chatModel.call(SystemMessage(systemInstruction), UserMessage(userPrompt))
        } catch (e: Exception) {
            logger.error("AI prompt failed: {}", e.message)
            null
        }
    }

    /**
     * Sends a structured-output prompt and converts the response into a typed entity. Returns null
     * on any model or conversion failure.
     */
    open fun <T : Any> promptForObject(
        systemInstruction: String,
        userPrompt: String,
        responseType: Class<T>,
    ): T? {
        return promptForObjectWithRaw(systemInstruction, userPrompt, responseType).value
    }

    /**
     * Same as [promptForObject] but also returns the raw response text for diagnostics/fallback.
     */
    open fun <T : Any> promptForObjectWithRaw(
        systemInstruction: String,
        userPrompt: String,
        responseType: Class<T>,
    ): AiStructuredResponse<T> {
        val converter = BeanOutputConverter(responseType)
        val raw =
            promptWithFormat(systemInstruction, userPrompt, converter.format)
                ?: return AiStructuredResponse(null, null)

        val parsed =
            try {
                converter.convert(raw)
            } catch (e: Exception) {
                logger.warn(
                    "AI structured conversion failed for {}: {}",
                    responseType.simpleName,
                    e.message,
                )
                null
            }
        return AiStructuredResponse(parsed, raw)
    }

    /** Adds a structured-output format contract to the system instruction. */
    open fun promptWithFormat(
        systemInstruction: String,
        userPrompt: String,
        formatInstruction: String,
    ): String? {
        val fullInstruction = systemInstruction.trim() + "\n\n" + formatInstruction.trim()
        return prompt(fullInstruction, userPrompt)
    }
}

data class AiStructuredResponse<T : Any>(val value: T?, val raw: String?)
