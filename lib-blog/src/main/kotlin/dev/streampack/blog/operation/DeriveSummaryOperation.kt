/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.DeriveSummaryRequest
import dev.streampack.blog.model.DeriveSummaryResponse
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Derives a non-persistent heuristic summary for editor preview. */
@Component
class DeriveSummaryOperation(private val markdownRenderingService: MarkdownRenderingService) :
    TypedOperation<DeriveSummaryRequest>(DeriveSummaryRequest::class) {

    override fun handle(payload: DeriveSummaryRequest, message: Message<*>): OperationOutcome {
        val title = payload.title.trim()
        val markdown = payload.markdownSource.trim()
        if (title.isBlank()) return OperationResult.Error("Title is required")
        if (markdown.isBlank()) return OperationResult.Error("Content is required")

        val summary = markdownRenderingService.excerpt(markdown).ifBlank { title }
        return OperationResult.Success(DeriveSummaryResponse(summary))
    }
}
