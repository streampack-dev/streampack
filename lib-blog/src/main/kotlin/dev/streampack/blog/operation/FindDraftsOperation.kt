/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.ContentSummary
import dev.streampack.blog.model.FindDraftsRequest
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Lists active or soft-deleted drafts for the admin review queue */
@Component
class FindDraftsOperation(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val postTagRepository: PostTagRepository,
    private val postCategoryRepository: PostCategoryRepository,
) : TypedOperation<FindDraftsRequest>(FindDraftsRequest::class) {

    override val priority = 50

    override fun handle(payload: FindDraftsRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val pageResult =
            if (payload.deleted) {
                postRepository.findDeletedDrafts(PageRequest.of(payload.page, payload.size))
            } else {
                postRepository.findDrafts(PageRequest.of(payload.page, payload.size))
            }

        val summaries =
            pageResult.content.map { post ->
                val canonicalSlug = slugRepository.findCanonical(post.id)
                ContentSummary(
                    id = post.id,
                    title = post.title,
                    slug = canonicalSlug?.path ?: "",
                    excerpt = post.excerpt,
                    authorDisplayName = post.author?.displayName ?: "Anonymous",
                    publishedAt = post.publishedAt,
                    tags = postTagRepository.findByPost(post.id).map { it.tag.name },
                    categories = postCategoryRepository.findByPost(post.id).map { it.category.name },
                )
            }

        return OperationResult.Success(
            ContentListResponse(
                posts = summaries,
                page = pageResult.number,
                totalPages = pageResult.totalPages,
                totalCount = pageResult.totalElements,
            )
        )
    }
}
