/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.service

import dev.streampack.ai.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.converter.BeanOutputConverter

/** Thin wrapper around Spring AI ChatModel for prompt-based text generation */
open class AiService(private val chatModel: ChatModel, private val properties: AiProperties) {
    private val logger = LoggerFactory.getLogger(AiService::class.java)

    /**
     * Sends a system instruction and user prompt to the model, returns the response text, or null
     * if it failed. Every call is logged with how long it took, and a failure with its stack trace:
     * including a missing or mismatched class (a [LinkageError]), which is a deployment problem and
     * would otherwise pass every `catch (Exception)` above it unseen.
     */
    open fun prompt(systemInstruction: String, userPrompt: String): String? {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            val response =
                chatModel.call(
                    Prompt(listOf(SystemMessage(systemInstruction), UserMessage(userPrompt)))
                )
            val answer = answerText(response)
            if (answer == null) {
                logger.warn(
                    "AI prompt answered in {} ms with no text: {}",
                    elapsed(),
                    describe(response),
                )
            } else {
                logger.info(
                    "AI prompt answered in {} ms ({} characters asked, {} answered)",
                    elapsed(),
                    systemInstruction.length + userPrompt.length,
                    answer.length,
                )
            }
            answer
        } catch (e: Exception) {
            logger.error("AI prompt failed after {} ms", elapsed(), e)
            null
        } catch (e: LinkageError) {
            logger.error(
                "AI prompt failed after {} ms: a class is missing or doesn't match",
                elapsed(),
                e,
            )
            null
        }
    }

    /**
     * The answer's text: every result's that isn't the model's thinking, joined; null if there's
     * none. A model may answer in several blocks, thinking first (its text often left out, only a
     * signature kept), and Spring AI makes each a result of its own, so the first result alone may
     * hold no answer at all.
     */
    private fun answerText(response: ChatResponse?): String? =
        response
            ?.results
            .orEmpty()
            .filterNot(::isThinking)
            .mapNotNull { it.output.text?.takeIf(String::isNotBlank) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /** A thinking or redacted-thinking block, as Spring AI's Anthropic client marks them */
    private fun isThinking(result: Generation): Boolean {
        val metadata = result.output.metadata
        return metadata.containsKey("signature") ||
            metadata.containsKey("data") ||
            metadata["thinking"] == true
    }

    /** What came back, for the log, when it held no answer */
    private fun describe(response: ChatResponse?): String {
        if (response == null) return "no response"
        val results =
            response.results.joinToString(", ") { result ->
                val kind = if (isThinking(result)) "thinking" else "text"
                "$kind (${result.output.text?.length ?: 0} characters, finish " +
                    "${result.metadata.finishReason})"
            }
        return "${response.results.size} result(s) [$results], usage ${response.metadata.usage}"
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
