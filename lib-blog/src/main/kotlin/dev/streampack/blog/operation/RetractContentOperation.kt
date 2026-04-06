/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.RetractContentRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Author withdraws their own draft post (soft delete) */
@Component
class RetractContentOperation(private val postRepository: PostRepository) :
    TypedOperation<RetractContentRequest>(RetractContentRequest::class) {

    override fun handle(payload: RetractContentRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance context")

        val principal = provenance.user ?: return OperationResult.Error("Authentication required")

        val post =
            postRepository.findActiveByIdWithAuthor(payload.id)
                ?: return OperationResult.Error("Post not found")

        if (post.author == null || post.author!!.id != principal.id) {
            return OperationResult.Error("Only the author can retract a post")
        }

        if (post.status != PostStatus.DRAFT) {
            return OperationResult.Error("Cannot retract an approved post")
        }

        postRepository.save(post.copy(deleted = true, updatedAt = Instant.now()))

        logger.info("Post retracted: {}", post.id)

        return OperationResult.Success(
            ContentOperationConfirmation(id = post.id, message = "Post retracted")
        )
    }
}
