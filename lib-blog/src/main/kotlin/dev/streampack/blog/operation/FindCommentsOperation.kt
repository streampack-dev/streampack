/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.model.CommentNode
import dev.streampack.blog.model.CommentThreadResponse
import dev.streampack.blog.model.FindCommentsRequest
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.TypedOperation
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Retrieves all comments for a post and assembles them into a threaded tree */
@Component
class FindCommentsOperation(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
) : TypedOperation<FindCommentsRequest>(FindCommentsRequest::class) {

    override fun handle(payload: FindCommentsRequest, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val principal = provenance?.user

        val post =
            postRepository.findActiveById(payload.postId)
                ?: return OperationResult.Error("Post not found")

        val allComments = commentRepository.findByPostWithAuthor(payload.postId)
        val activeCount = commentRepository.countActiveByPost(payload.postId).toInt()

        val tree = buildTree(allComments, principal)

        logger.debug("Found {} comments for post {}", allComments.size, post.id)

        return OperationResult.Success(
            CommentThreadResponse(postId = post.id, comments = tree, totalActiveCount = activeCount)
        )
    }

    /** Assembles flat comment list into a nested tree, masking deleted comments */
    private fun buildTree(comments: List<Comment>, principal: UserPrincipal?): List<CommentNode> {
        val childrenByParent = comments.groupBy { it.parentComment?.id }
        return buildChildren(null, childrenByParent, principal)
    }

    private fun buildChildren(
        parentId: UUID?,
        childrenByParent: Map<UUID?, List<Comment>>,
        principal: UserPrincipal?,
    ): List<CommentNode> {
        val children = childrenByParent[parentId] ?: return emptyList()
        return children.map { comment -> toNode(comment, childrenByParent, principal) }
    }

    private fun toNode(
        comment: Comment,
        childrenByParent: Map<UUID?, List<Comment>>,
        principal: UserPrincipal?,
    ): CommentNode {
        val isDeleted = comment.deleted
        val editable = computeEditable(comment, principal)
        val children = buildChildren(comment.id, childrenByParent, principal)

        return if (isDeleted) {
            CommentNode(
                id = comment.id,
                authorId = null,
                authorDisplayName = "Anonymous",
                renderedHtml = "[deleted]",
                markdownSource = null,
                createdAt = comment.createdAt,
                updatedAt = comment.updatedAt,
                deleted = true,
                editable = false,
                children = children,
            )
        } else {
            CommentNode(
                id = comment.id,
                authorId = comment.author.id,
                authorDisplayName = comment.author.displayName,
                renderedHtml = comment.renderedHtml,
                markdownSource = if (editable) comment.markdownSource else null,
                createdAt = comment.createdAt,
                updatedAt = comment.updatedAt,
                deleted = false,
                editable = editable,
                children = children,
            )
        }
    }

    /** Author can edit within 5 minutes; admin can edit anytime; everyone else cannot */
    private fun computeEditable(comment: Comment, principal: UserPrincipal?): Boolean {
        if (comment.deleted) return false
        if (principal == null) return false

        val isAdmin = principal.role == Role.ADMIN || principal.role == Role.SUPER_ADMIN
        if (isAdmin) return true

        val isAuthor = comment.author.id == principal.id
        if (!isAuthor) return false

        val elapsed = Duration.between(comment.createdAt, Instant.now())
        return elapsed.toMinutes() < 5
    }
}
