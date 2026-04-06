/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.PurgeErasedContentRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Hard-deletes all content belonging to an erased user sentinel, then removes the sentinel itself.
 *
 * Admin/super-admin only. Validates that the target user has ERASED status before proceeding.
 * Comments must be deleted before posts to satisfy FK constraints on parent_comment_id.
 */
@Component
class PurgeErasedContentOperation(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
) : Operation {
    override val priority = 50

    override fun canHandle(message: Message<*>): Boolean =
        message.payload is PurgeErasedContentRequest

    @Transactional
    override fun execute(message: Message<*>): OperationResult {
        val request = message.payload as PurgeErasedContentRequest
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val sentinel =
            userRepository.findById(request.sentinelUserId).orElse(null)
                ?: return OperationResult.Error("User not found")

        if (!sentinel.isErased()) {
            return OperationResult.Error("Target user is not an erased sentinel")
        }

        // Delete comments first (FK on parent_comment_id), then posts
        commentRepository.hardDeleteByAuthor(sentinel.id)
        postRepository.hardDeleteByAuthor(sentinel.id)

        // Remove the sentinel itself
        userRepository.hardDeleteById(sentinel.id)

        logger.info("Purged all content and sentinel for {}", sentinel.username)
        return OperationResult.Success("Content purged")
    }
}
