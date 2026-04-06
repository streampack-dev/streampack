/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ask.operation

import dev.streampack.ai.service.AiService
import dev.streampack.ask.model.AskRequest
import dev.streampack.core.model.MessageDirection
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.TranslatingOperation
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * General-purpose LLM query operation. Assembles recent channel context (last 10 messages within a
 * 5-minute window) and forwards the user's question to the AI. Throttled to 5 requests per hour per
 * provenance URI via the framework's token bucket.
 */
@Component
@ConditionalOnProperty(prefix = "streampack.ai", name = ["enabled"], havingValue = "true")
class AskOperation(
    private val aiService: AiService,
    private val messageLogService: MessageLogService,
) : TranslatingOperation<AskRequest>(AskRequest::class) {
    // should be lower than factoid priority, otherwise "!ask why is foo bar" will resolve
    // to a factoid *setting* operation
    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "ask"
    override val throttlePolicy: ThrottlePolicy = ThrottlePolicy(5, Duration.ofHours(1))

    override fun translate(payload: String, message: Message<*>): AskRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("ask ", ignoreCase = true)) return null
        val question = trimmed.substring("ask ".length).trim()
        if (question.isBlank()) return null
        return AskRequest(question)
    }

    override fun handle(payload: AskRequest, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val provenanceUri = provenance?.encode() ?: "unknown"
        val botNick = message.headers[Provenance.BOT_NICK] as? String ?: "bot"
        val channelName = provenance?.replyTo ?: "a chat channel"
        val context = assembleContext(provenanceUri, botNick)

        val systemPrompt = buildSystemPrompt(channelName, context)

        logger.info("Ask query from {}: {}", provenanceUri, payload.question.take(80))
        val answer = aiService.prompt(systemPrompt, payload.question)

        if (answer == null) {
            return OperationResult.Error("Failed to get a response from the AI.")
        }

        return OperationResult.Success(answer)
    }

    /** Assembles recent channel conversation as context for the LLM */
    private fun assembleContext(provenanceUri: String, botNick: String): String {
        val now = Instant.now()
        val windowStart = now.minus(5, ChronoUnit.MINUTES)
        val messages = messageLogService.findMessages(provenanceUri, windowStart, now, 10)

        if (messages.isEmpty()) return ""

        return messages.joinToString("\n") { entry ->
            val prefix =
                if (entry.direction == MessageDirection.OUTBOUND || entry.sender == botNick) {
                    "int"
                } else {
                    "ext"
                }
            "$prefix ${entry.sender}: ${entry.content}"
        }
    }

    private fun buildSystemPrompt(channelName: String, context: String): String {
        val contextSection =
            if (context.isNotBlank()) {
                """

                Recent conversation in $channelName (lines prefixed "ext" are humans, "int" are the bot):
                $context

                Use this context only if relevant to the question.
                """
                    .trimIndent()
            } else {
                ""
            }

        return """
            You are a helpful assistant in $channelName.
            Answer the user's question concisely in 1-2 sentences.
            Be *very* succinct, as the output length is quite constrained.
            If context confers that extreme feelings are involved, mitigate them.
            Be direct and factual. If you do not know, say so.
            Use the most recent and up-to-date information available.
            $contextSection
            """
            .trimIndent()
    }
}
