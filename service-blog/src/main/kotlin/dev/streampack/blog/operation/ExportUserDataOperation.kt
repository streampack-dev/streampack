/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.CommentExport
import dev.streampack.blog.model.ExportUserDataRequest
import dev.streampack.blog.model.PostExport
import dev.streampack.blog.model.ProfileExport
import dev.streampack.blog.model.UserDataExport
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Exports a user's data as a structured object for GDPR compliance.
 *
 * Authenticated users can export their own data. Admins can export any user's data for review
 * before account erasure.
 */
@Component
class ExportUserDataOperation(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
) : Operation {
    override val priority = 50

    override fun canHandle(message: Message<*>): Boolean = message.payload is ExportUserDataRequest

    override fun execute(message: Message<*>): OperationResult {
        val request = message.payload as ExportUserDataRequest
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        val targetUsername = request.username ?: principal.username

        // Non-admin users can only export their own data
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

        val profile =
            ProfileExport(
                username = targetUser.username,
                email = targetUser.email,
                displayName = targetUser.displayName,
                role = targetUser.role.name,
                createdAt = targetUser.createdAt,
            )

        val posts =
            postRepository.findByAuthor(targetUser.id).map { post ->
                PostExport(
                    title = post.title,
                    markdownSource = post.markdownSource,
                    status = post.status.name,
                    createdAt = post.createdAt,
                    publishedAt = post.publishedAt,
                )
            }

        val comments =
            commentRepository.findByAuthor(targetUser.id).map { comment ->
                CommentExport(
                    postTitle = comment.post.title,
                    markdownSource = comment.markdownSource,
                    createdAt = comment.createdAt,
                )
            }

        return OperationResult.Success(UserDataExport(profile, posts, comments))
    }
}
