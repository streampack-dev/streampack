/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.ai.service.AiService
import dev.streampack.blog.model.DeriveSummaryRequest
import dev.streampack.blog.model.DeriveSummaryResponse
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Derives a non-persistent summary for editor preview. For an admin, with AI enabled, the model
 * writes it in ByteCode.News's editorial voice (#102): don't bury the lede. Everyone else, and any
 * answer the model can't give, gets the heuristic excerpt, as before.
 */
@Component
class DeriveSummaryOperation(
    private val markdownRenderingService: MarkdownRenderingService,
    private val aiServiceProvider: ObjectProvider<AiService>,
    @Value("\${streampack.blog.summary.system-prompt:}") configuredPrompt: String,
) : TypedOperation<DeriveSummaryRequest>(DeriveSummaryRequest::class) {

    private val systemPrompt = configuredPrompt.trim().ifEmpty { DEFAULT_PROMPT }

    override fun handle(payload: DeriveSummaryRequest, message: Message<*>): OperationOutcome {
        val title = payload.title.trim()
        val markdown = payload.markdownSource.trim()
        if (title.isBlank()) return OperationResult.Error("Title is required")
        if (markdown.isBlank()) return OperationResult.Error("Content is required")

        aiSummary(title, markdown, message)?.let {
            return OperationResult.Success(
                DeriveSummaryResponse(it, DeriveSummaryResponse.SOURCE_AI)
            )
        }
        val summary = markdownRenderingService.excerpt(markdown).ifBlank { title }
        return OperationResult.Success(DeriveSummaryResponse(summary))
    }

    /** The model's summary, for an admin with AI enabled; null for anyone else, or no answer. */
    private fun aiSummary(title: String, markdown: String, message: Message<*>): String? {
        val role = (message.headers[Provenance.HEADER] as? Provenance)?.user?.role
        if (role != Role.ADMIN && role != Role.SUPER_ADMIN) return null
        val aiService = aiServiceProvider.ifAvailable ?: return null

        val raw = aiService.prompt(systemPrompt, "Title: $title\n\nArticle (Markdown):\n$markdown")
        val summary = raw?.let(::plainText)
        if (summary.isNullOrEmpty()) {
            logger.info("DeriveSummaryOperation: AI gave no usable summary; using the heuristic")
            return null
        }
        logger.info("DeriveSummaryOperation: AI summary of {} chars", summary.length)
        return summary
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeriveSummaryOperation::class.java)

        /** The excerpt's length, as the heuristic keeps to. */
        private const val MAX_LENGTH = 400

        /** ByteCode.News's editorial stance, the prompt's default: don't bury the lede. */
        const val DEFAULT_PROMPT =
            "You write the summary (dek) for a ByteCode.News article: one to three plain " +
                "sentences that tell a reader exactly what the article is and what it finds, " +
                "shows or recommends. Lead with the substance; if the article answers a question " +
                "or reports a result, give the answer or result. The reader should know what " +
                "they're reading from the summary alone, and read the article for more detail. " +
                "Never tease, withhold the point, ask the reader a question, or use hype or " +
                "clickbait phrasing; don't start with \"In this article\" or similar. Plain text " +
                "only: no Markdown, quotes, labels or emoji."

        private val LABEL = Regex("""^(summary|dek|tl;?dr)\s*:\s*""", RegexOption.IGNORE_CASE)

        /**
         * The model's answer as plain text: Markdown marks, double quotes and a leading label gone,
         * whitespace collapsed, and cut at a word within the excerpt's length.
         */
        fun plainText(raw: String): String {
            val text =
                raw.replace("```", " ")
                    .replace(Regex("""[*_`#]"""), "")
                    .replace(Regex("""["\u201C\u201D]"""), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .replace(LABEL, "")
                    .trim()
            if (text.length <= MAX_LENGTH) return text
            val cut = text.substring(0, MAX_LENGTH - 3)
            val lastSpace = cut.lastIndexOf(' ')
            return (if (lastSpace > 0) cut.substring(0, lastSpace) else cut).trimEnd(
                ',',
                ';',
                ':',
            ) + "..."
        }
    }
}
