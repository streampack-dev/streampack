/* Joseph B. Ottinger (C)2026 */
package dev.streampack.poetry.operation

import dev.streampack.ai.service.AiService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.poetry.model.PoemAnalysisRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Analyzes poem text for meter, rhyme scheme, and form. Catches "a poem:" prefixed text that
 * arrives via loopback from PoemOperation, or accepts typed PoemAnalysisRequest payloads directly.
 */
@Component
@ConditionalOnProperty(prefix = "streampack.ai", name = ["enabled"], havingValue = "true")
class PoemAnalysisOperation(private val aiService: AiService) :
    TranslatingOperation<PoemAnalysisRequest>(PoemAnalysisRequest::class) {

    override val priority: Int = 20
    override val addressed: Boolean = true
    override val operationGroup: String = "poetry"

    override fun translate(payload: String, message: Message<*>): PoemAnalysisRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("a poem:", ignoreCase = true)) return null
        val poemText = trimmed.substringAfter(":").trim().replace("/", "\n")
        if (poemText.isBlank()) return null
        return PoemAnalysisRequest(poemText)
    }

    override fun handle(payload: PoemAnalysisRequest, message: Message<*>): OperationOutcome? {
        logger.info("Analyzing poem text ({} chars)", payload.text.length)

        val systemPrompt =
            """
            You are a poetry critic. Analyze the following poem. 
            Identify its meter, rhyme scheme, and poetic form. 
            Keep your analysis concise (2-3 sentences) and under less 
            than 250 characters.
            """
                .trimIndent()
        val analysis = aiService.prompt(systemPrompt, payload.text)

        return if (analysis != null) {
            OperationResult.Success("Analysis: $analysis")
        } else {
            OperationResult.Error("Failed to analyze poem")
        }
    }
}
