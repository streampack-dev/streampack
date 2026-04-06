/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ApproveContentRequest
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Transitions a draft post to APPROVED with a publication timestamp */
@Component
class ApproveContentOperation(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val commentRepository: CommentRepository,
    private val postTagRepository: PostTagRepository,
    private val postCategoryRepository: PostCategoryRepository,
) : TypedOperation<ApproveContentRequest>(ApproveContentRequest::class) {

    override val priority = 50

    override fun handle(payload: ApproveContentRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val post =
            postRepository.findActiveByIdWithAuthor(payload.id)
                ?: return OperationResult.Error("Post not found")

        if (post.status != PostStatus.DRAFT) {
            return OperationResult.Error("Post is already approved")
        }

        val now = Instant.now()
        val approved =
            postRepository.save(
                post.copy(
                    status = PostStatus.APPROVED,
                    publishedAt = payload.publishedAt,
                    updatedAt = now,
                )
            )

        val canonicalSlug = slugRepository.findCanonical(approved.id)

        logger.info("Post approved: {} with publishedAt {}", approved.id, payload.publishedAt)

        return OperationResult.Success(
            ContentDetail(
                id = approved.id,
                title = approved.title,
                slug = canonicalSlug?.path ?: "",
                renderedHtml = approved.renderedHtml,
                excerpt = approved.excerpt,
                authorId = approved.author?.id,
                authorDisplayName = approved.author?.displayName ?: "Anonymous",
                status = approved.status,
                publishedAt = approved.publishedAt,
                createdAt = approved.createdAt,
                updatedAt = approved.updatedAt,
                commentCount = commentRepository.countActiveByPost(approved.id).toInt(),
                tags = postTagRepository.findByPost(approved.id).map { it.tag.name },
                categories =
                    postCategoryRepository.findByPost(approved.id).map { it.category.name },
                markdownSource = approved.markdownSource,
            )
        )
    }
}
