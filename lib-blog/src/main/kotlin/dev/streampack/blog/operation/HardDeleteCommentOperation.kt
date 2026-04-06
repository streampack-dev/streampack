/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.HardDeleteCommentRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import jakarta.persistence.EntityManager
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin permanently deletes a comment; DB cascades to child comments */
@Component
class HardDeleteCommentOperation(
    private val commentRepository: CommentRepository,
    private val entityManager: EntityManager,
) : TypedOperation<HardDeleteCommentRequest>(HardDeleteCommentRequest::class) {

    override val priority = 50

    override fun handle(payload: HardDeleteCommentRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        if (!commentRepository.existsById(payload.id)) {
            return OperationResult.Error("Comment not found")
        }

        commentRepository.hardDeleteById(payload.id)
        entityManager.clear()

        logger.info("Comment permanently removed: {}", payload.id)

        return OperationResult.Success(
            ContentOperationConfirmation(id = payload.id, message = "Comment permanently removed")
        )
    }
}
