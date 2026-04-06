/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.SoftDeleteCommentRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Admin soft-deletes a comment, preserving thread structure */
@Component
class SoftDeleteCommentOperation(private val commentRepository: CommentRepository) :
    TypedOperation<SoftDeleteCommentRequest>(SoftDeleteCommentRequest::class) {

    override val priority = 50

    override fun handle(payload: SoftDeleteCommentRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val comment =
            commentRepository.findById(payload.id).orElse(null)
                ?: return OperationResult.Error("Comment not found")

        if (comment.deleted) {
            return OperationResult.Error("Comment is already deleted")
        }

        commentRepository.save(comment.copy(deleted = true, updatedAt = Instant.now()))

        logger.info("Comment soft-deleted: {}", comment.id)

        return OperationResult.Success(
            ContentOperationConfirmation(id = comment.id, message = "Comment deleted")
        )
    }
}
