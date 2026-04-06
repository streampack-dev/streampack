/* Joseph B. Ottinger (C)2026 */
package dev.streampack.poetry.operation

import dev.streampack.ai.service.AiService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.poetry.model.PoemRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Generates a poem about a topic via AI and loops the result back for analysis */
@Component
@ConditionalOnProperty(prefix = "streampack.ai", name = ["enabled"], havingValue = "true")
class PoemOperation(private val aiService: AiService) :
    TranslatingOperation<PoemRequest>(PoemRequest::class) {

    override val priority: Int = 65
    override val addressed: Boolean = true
    override val operationGroup: String = "poetry"

    override fun translate(payload: String, message: Message<*>): PoemRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("poem ", ignoreCase = true)) return null
        val topic = trimmed.substring("poem ".length).trim()
        if (topic.isBlank()) return null
        return PoemRequest(topic)
    }

    override fun handle(payload: PoemRequest, message: Message<*>): OperationOutcome? {
        logger.info("Generating poem about '{}' in form '{}'", payload.topic, payload.form)

        val systemPrompt =
            """
            You are a poet. Write a ${payload.form} about the given topic.
            Output ONLY the poem text, no title or commentary.
            The poem should be short, fitting in under 200 characters if possible.
        """
                .trimIndent()
        val poem = aiService.prompt(systemPrompt, payload.topic)

        return if (poem != null) {
            val formatted = "a poem: " + poem.lines().joinToString("/") { it.trim() }
            OperationResult.Success(formatted, loopback = true)
        } else {
            OperationResult.Error("Failed to generate poem about '${payload.topic}'")
        }
    }
}
