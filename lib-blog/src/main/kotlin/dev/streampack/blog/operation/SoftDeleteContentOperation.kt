/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.SoftDeleteContentRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin soft-deletes a post, hiding it from public view */
@Component
class SoftDeleteContentOperation(private val postRepository: PostRepository) :
    TypedOperation<SoftDeleteContentRequest>(SoftDeleteContentRequest::class) {

    override val priority = 50

    override fun handle(payload: SoftDeleteContentRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val post =
            postRepository.findById(payload.id).orElse(null)
                ?: return OperationResult.Error("Post not found")

        if (post.deleted) {
            return OperationResult.Error("Post is already deleted")
        }

        postRepository.save(post.copy(deleted = true, updatedAt = Instant.now()))

        logger.info("Post soft-deleted: {}", post.id)

        return OperationResult.Success(
            ContentOperationConfirmation(id = post.id, message = "Post deleted")
        )
    }
}
