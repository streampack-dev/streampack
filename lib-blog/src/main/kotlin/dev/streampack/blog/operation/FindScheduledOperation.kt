/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.FindScheduledRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.service.AdminPostSummaries
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Lists scheduled posts for admins (#207): approved, not deleted, with a publication date still to
 * come, soonest first. They're in neither the public lists nor the review queue, so without this an
 * admin has no way to find one to change it.
 */
@Component
class FindScheduledOperation(
    private val postRepository: PostRepository,
    private val summaries: AdminPostSummaries,
) : TypedOperation<FindScheduledRequest>(FindScheduledRequest::class) {

    override val priority = 50

    override fun handle(payload: FindScheduledRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val pageResult =
            postRepository.findScheduled(
                Instant.now(),
                PageRequest.of(payload.page, payload.size),
            )

        return OperationResult.Success(
            summaries.listing(
                pageResult,
                (message.headers[Provenance.HEADER] as? Provenance)?.user,
            ),
        )
    }
}
