/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.DeleteAccountRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.OneTimeCodeRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases a user account permanently.
 *
 * Creates a unique anonymous sentinel User to preserve content grouping, reassigns all posts and
 * comments to the sentinel, deletes OTP codes, then hard-deletes the original user record. Service
 * bindings and verification tokens cascade via FK ON DELETE CASCADE.
 */
@Component
class DeleteAccountOperation(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val oneTimeCodeRepository: OneTimeCodeRepository,
) : Operation {
    override val priority = 50

    override fun canHandle(message: Message<*>): Boolean = message.payload is DeleteAccountRequest

    @Transactional
    override fun execute(message: Message<*>): OperationResult {
        val request = message.payload as DeleteAccountRequest
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        val targetUsername = request.username ?: principal.username

        // Non-admin users can only erase themselves
        if (
            targetUsername != principal.username &&
                principal.role != Role.ADMIN &&
                principal.role != Role.SUPER_ADMIN
        ) {
            return OperationResult.Error("Insufficient privileges")
        }

        val targetUser =
            userRepository.findByUsername(targetUsername)
                ?: return OperationResult.Error("User not found")

        if (targetUser.role == Role.SUPER_ADMIN && targetUsername != principal.username) {
            return OperationResult.Error("Cannot delete a super admin")
        }

        if (targetUser.isErased()) {
            return OperationResult.Error("User is already erased")
        }

        // Create anonymous sentinel to preserve content grouping
        val sentinelUsername = "erased-${targetUser.id.toString().substring(0, 8)}"
        val sentinel =
            userRepository.saveAndFlush(
                User(
                    username = sentinelUsername,
                    email = "",
                    displayName = "[deleted]",
                    status = UserStatus.ERASED,
                    role = Role.GUEST,
                )
            )

        // Reassign content to sentinel before deleting the original user
        postRepository.reassignAuthor(targetUser.id, sentinel.id)
        commentRepository.reassignAuthor(targetUser.id, sentinel.id)

        // Clean up OTP codes (not FK-linked, so no cascade)
        if (targetUser.email.isNotBlank()) {
            oneTimeCodeRepository.deleteByEmail(targetUser.email)
        }

        // Hard-delete the original user; service bindings and verification tokens cascade
        userRepository.hardDeleteById(targetUser.id)

        logger.info("Account erased: {} -> sentinel {}", targetUsername, sentinelUsername)

        return OperationResult.Success("Account deleted")
    }
}
