/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.FindDraftsRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.service.AdminPostSummaries
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Lists active or soft-deleted drafts for the admin review queue */
@Component
class FindDraftsOperation(
    private val postRepository: PostRepository,
    private val summaries: AdminPostSummaries,
) : TypedOperation<FindDraftsRequest>(FindDraftsRequest::class) {

    override val priority = 50

    override fun handle(payload: FindDraftsRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val pageResult =
            if (payload.deleted) {
                postRepository.findDeletedDrafts(PageRequest.of(payload.page, payload.size))
            } else {
                postRepository.findDrafts(PageRequest.of(payload.page, payload.size))
            }

        return OperationResult.Success(
            summaries.listing(
                pageResult,
                (message.headers[Provenance.HEADER] as? Provenance)?.user,
            ),
        )
    }
}
