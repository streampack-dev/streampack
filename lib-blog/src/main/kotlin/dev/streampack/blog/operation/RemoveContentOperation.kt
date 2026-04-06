/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.RemoveContentRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import jakarta.persistence.EntityManager
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin permanently deletes a post; DB cascades to slugs, comments, and taxonomy joins */
@Component
class RemoveContentOperation(
    private val postRepository: PostRepository,
    private val entityManager: EntityManager,
) : TypedOperation<RemoveContentRequest>(RemoveContentRequest::class) {

    override val priority = 50

    override fun handle(payload: RemoveContentRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        if (!postRepository.existsById(payload.id)) {
            return OperationResult.Error("Post not found")
        }

        postRepository.hardDeleteById(payload.id)
        entityManager.clear()

        logger.info("Post permanently removed: {}", payload.id)

        return OperationResult.Success(
            ContentOperationConfirmation(id = payload.id, message = "Post permanently removed")
        )
    }
}
