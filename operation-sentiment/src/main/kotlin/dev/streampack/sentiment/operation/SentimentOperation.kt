/* Joseph B. Ottinger (C)2026 */
package dev.streampack.sentiment.operation

import dev.streampack.ai.service.AiService
import dev.streampack.core.model.MessageDirection
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.sentiment.model.SentimentRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Analyzes the sentiment of recent conversation for a target channel or user. Admin-only operation
 * that pulls message logs, formats them as a transcript, and sends them to an LLM for scoring.
 *
 * When the target differs from the source channel, the response is sent via DM to avoid leaking
 * cross-channel sentiment publicly.
 */
@Component
@ConditionalOnProperty(prefix = "streampack.ai", name = ["enabled"], havingValue = "true")
class SentimentOperation(
    private val aiService: AiService,
    private val messageLogService: MessageLogService,
) : TranslatingOperation<SentimentRequest>(SentimentRequest::class) {

    override val priority: Int = 40
    override val addressed: Boolean = true
    override val operationGroup: String = "sentiment"

    override fun translate(payload: String, message: Message<*>): SentimentRequest? {
        val target =
            when (val parsed = matcher.match(payload)) {
                is CommandMatchResult.Match -> parsed.captures["target"] as? String
                else -> null
            } ?: return null

        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return null
        val targetUri = resolveTarget(target, provenance)
        return SentimentRequest(targetUri)
    }

    override fun handle(payload: SentimentRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }
        val provenance = message.headers[Provenance.HEADER] as? Provenance

        val botNick = message.headers[Provenance.BOT_NICK] as? String ?: "bot"
        val now = Instant.now()
        val windowStart = now.minus(4, ChronoUnit.HOURS)

        val messages = messageLogService.findMessages(payload.targetUri, windowStart, now, 100)
        if (messages.isEmpty()) {
            return OperationResult.Error("No recent messages found for ${payload.targetUri}")
        }

        val transcript = formatTranscript(messages, botNick)
        logger.info("Analyzing sentiment for {} ({} messages)", payload.targetUri, messages.size)

        val systemPrompt =
            //            """
            //            You are a conversation sentiment analyst. Analyze the following chat
            // transcript.
            //            Lines prefixed with "ext" are from external participants (humans).
            //            Lines prefixed with "int" are from the bot - include them for context but
            // do NOT
            //            factor the bot's messages into the sentiment score.
            //
            //            Score the overall sentiment from -10 (extremely negative/hostile) to +10
            //            (extremely positive/enthusiastic). Note which participants drive the score
            //            most and why.
            //
            //            Keep your response concise - under 300 characters. Format:
            //            Score: N/10. Brief explanation.
            //            """
            """
            You are a channel sentiment analyst.

            Input <= 100 lines. Prefixes:
            ext = humans (score)
            int = bot/system (context only)

            Evaluate:
            • Sentiment (-10 hostile to +10 very positive; 0 neutral)
            • Intensity (low/mod/high)
            • Themes (dominant recent topics)

            Rules:
            • Weight recent ext lines more heavily
            • Do not swing score sharply unless shift is sustained
            • Drivers = participants affecting tone (omit if single speaker)
            • Treat sarcasm cautiously; soften only if context supports it

            Output ONE IRC LINE <= 200 chars:

            Sentiment N/10 (use +/- prefix) | Intensity X | Themes: ... | Summary: ...
            (+ Drivers: A,B only if intensity=high AND multiple users)
            """
                .trimIndent()

        val analysis = aiService.prompt(systemPrompt, transcript)

        if (analysis == null) {
            return OperationResult.Error("Failed to analyze sentiment")
        }

        val sourceUri = provenance?.encode()
        logger.info("Sentiment analysis for {}: {}", payload.targetUri, analysis)
        val isCrossChannel = sourceUri != null && sourceUri != payload.targetUri
        val responseProvenance =
            if (isCrossChannel) {
                Provenance(
                    protocol = provenance.protocol,
                    serviceId = provenance.serviceId,
                    user = provenance.user,
                    replyTo =
                        message.headers["nick"] as? String
                            ?: provenance.user?.displayName
                            ?: provenance.user?.username
                            ?: provenance.replyTo,
                )
            } else {
                null
            }

        return OperationResult.Success(
            "Sentiment for ${payload.targetUri}: $analysis",
            provenance = responseProvenance,
        )
    }

    /** Formats log entries as a transcript with ext/int prefixes */
    private fun formatTranscript(
        messages: List<dev.streampack.core.entity.MessageLog>,
        botNick: String,
    ): String {
        return messages.joinToString("\n") { entry ->
            val prefix =
                if (entry.direction == MessageDirection.OUTBOUND || entry.sender == botNick) {
                    "int"
                } else {
                    "ext"
                }
            "$prefix ${entry.sender} ${entry.content}"
        }
    }

    /** Resolves a target string to a provenance URI, inheriting context from source */
    private fun resolveTarget(target: String, source: Provenance): String {
        if (target.contains("://")) return target
        return Provenance(
                protocol = source.protocol,
                serviceId = source.serviceId,
                replyTo = target,
            )
            .encode()
    }

    private companion object {
        private val matcher =
            CommandPatternMatcher(
                listOf(
                    CommandPattern(
                        name = "sentiment",
                        literals = listOf("sentiment"),
                        args = listOf(CommandArgSpec("target", StringArgType)),
                    )
                )
            )
    }
}
