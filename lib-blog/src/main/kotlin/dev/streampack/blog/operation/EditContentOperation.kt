/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.EditContentRequest
import dev.streampack.blog.repository.*
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.blog.service.SlugGenerationService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import java.util.*
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Modifies an existing post's title and markdown content */
@Component
class EditContentOperation(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val commentRepository: CommentRepository,
    private val markdownRenderingService: MarkdownRenderingService,
    private val slugGenerationService: SlugGenerationService,
    private val tagRepository: TagRepository,
    private val postTagRepository: PostTagRepository,
    private val categoryRepository: CategoryRepository,
    private val postCategoryRepository: PostCategoryRepository,
) : TypedOperation<EditContentRequest>(EditContentRequest::class) {

    override fun handle(payload: EditContentRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance context")

        val principal = provenance.user ?: return OperationResult.Error("Authentication required")

        val post =
            postRepository.findActiveByIdWithAuthor(payload.id)
                ?: return OperationResult.Error("Post not found")

        val isAdmin = principal.role == Role.ADMIN || principal.role == Role.SUPER_ADMIN
        if ((payload.title ?: "").trim().isBlank()) {
            return OperationResult.Error("Title is required")
        }
        if ((payload.markdownSource ?: "").trim().isBlank()) {
            return OperationResult.Error("Content is required")
        }
        // Editing is admin-only; submissions are fire-and-forget for non-admin users.
        if (!isAdmin) {
            return OperationResult.Error("Not authorized to edit this post")
        }

        val saved = postRepository.save(payload.applyTo(post, markdownRenderingService))
        val updated =
            postRepository.findActiveByIdWithAuthor(saved.id)
                ?: return OperationResult.Error("Post not found")

        val tagNames = replaceTags(updated, payload.tags ?: emptyList())
        val categoryNames = replaceCategories(updated, payload.categoryIds ?: emptyList())

        val canonicalSlug = slugRepository.findCanonical(updated.id)

        logger.info("Post edited: {}", updated.id)

        return OperationResult.Success(
            ContentDetail(
                id = updated.id,
                title = updated.title,
                slug = canonicalSlug?.path ?: "",
                renderedHtml = updated.renderedHtml,
                excerpt = updated.excerpt,
                authorId = updated.author?.id,
                authorDisplayName = updated.author?.displayName ?: "Anonymous",
                status = updated.status,
                publishedAt = updated.publishedAt,
                sortOrder = updated.sortOrder,
                createdAt = updated.createdAt,
                updatedAt = updated.updatedAt,
                commentCount = commentRepository.countActiveByPost(updated.id).toInt(),
                tags = tagNames,
                categories = categoryNames,
                markdownSource = updated.markdownSource,
            )
        )
    }

    /** Removes existing tag associations and creates new ones from the request */
    private fun replaceTags(post: Post, tagNames: List<String>): List<String> {
        postTagRepository.deleteByPost(post.id)
        val resolved =
            tagNames
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { name ->
                    tagRepository.findByName(name)
                        ?: tagRepository.save(
                            Tag(name = name, slug = slugGenerationService.slugify(name))
                        )
                }
        resolved.forEach { tag -> postTagRepository.save(PostTag(post = post, tag = tag)) }
        return resolved.map { it.name }
    }

    /** Removes existing category associations and creates new ones from the request */
    private fun replaceCategories(post: Post, categoryIds: List<UUID>): List<String> {
        postCategoryRepository.deleteByPost(post.id)
        val resolved =
            categoryIds.distinct().mapNotNull { id ->
                categoryRepository.findById(id).orElse(null)?.takeIf { !it.deleted }
            }
        resolved.forEach { category ->
            postCategoryRepository.save(PostCategory(post = post, category = category))
        }
        return resolved.map { it.name }
    }
}
